#!/usr/bin/env bash
#
# 역할별 EC2 인스턴스를 띄우고, user-data(cloud-init)로 부팅 시점에
# bootstrap.sh + 패키지 설치 + 설정 파일 배치까지 끝낸 상태로 준비한다.
#
#   cp provision.env.example provision.env   # 최초 1회, AMI_ID 등 채우기
#   ./provision.sh was 8                     # WAS 8대
#   ./provision.sh redis 6 --wait            # Redis 6대 (마스터 3 + 레플리카 3)
#   ./provision.sh k6 --wait                 # 뜰 때까지 기다렸다 프라이빗 IP 출력
#
# user-data가 대신 끝내는 것: 커널·리소스 한도 튜닝(bootstrap.sh), 역할별 apt 설치,
# WAS는 waiting.service 설치(잡·env는 아직 없어 start는 안 함), Redis는 클러스터 모드
# 설정 배치 + 재시작까지.
#
# 여전히 손으로 해야 하는 것(README.md "순서" 절): JAR scp + 기동, DB 시크릿
# (init-db.sh → WAS1 한정 실행 → 나머지엔 scp), 그리고 redis-cli --cluster create.
# 앞의 둘은 실행마다 값이 바뀌거나 시크릿을 다뤄서 user-data에 구우면 오히려 위험해지고,
# 클러스터 형성은 6대의 IP가 다 나온 뒤에야 할 수 있어 부팅 시점에는 알 수 없다.
#
# 이 스크립트는 실제 AWS 자원을 만들고 과금이 발생한다. 실행 전에 provision.env가
# 가리키는 서브넷·보안 그룹이 맞는지 한 번 더 확인할 것.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROLE=${1:-}
case "$ROLE" in
    was | k6 | redis) ;;
    *)
        echo "usage: $0 <was|k6|redis> [count] [--wait]" >&2
        exit 1
        ;;
esac
shift

COUNT=1
WAIT=0
for arg in "$@"; do
    case "$arg" in
        --wait) WAIT=1 ;;
        ''|*[!0-9]*)
            echo "count는 숫자여야 한다: $arg" >&2
            exit 1
            ;;
        *) COUNT=$arg ;;
    esac
done

ENV_FILE="$SCRIPT_DIR/provision.env"
if [ ! -f "$ENV_FILE" ]; then
    echo "provision.env가 없다. cp provision.env.example provision.env 후 채울 것" >&2
    exit 1
fi
# shellcheck source=/dev/null
source "$ENV_FILE"
for v in AMI_ID SUBNET_ID SECURITY_GROUP_ID KEY_NAME; do
    if [ -z "${!v:-}" ]; then
        echo "provision.env에 $v가 비어 있다" >&2
        exit 1
    fi
done

case "$ROLE" in
    was)   INSTANCE_TYPE=${WAS_INSTANCE_TYPE:-c8g.large} ;;
    redis) INSTANCE_TYPE=${REDIS_INSTANCE_TYPE:-c8g.large} ;;
    k6)    INSTANCE_TYPE=${K6_INSTANCE_TYPE:-c8g.2xlarge} ;;
esac

# WAS만 다른 이미지를 쓸 수 있다. 미리 구워 둔 WAS AMI에는 waiting.service가 enable된
# 채로 들어 있어서, 그 이미지로 Redis나 k6를 띄우면 그 노드에서도 WAS가 같이 뜬다 —
# 재려던 노드에 측정 대상이 아닌 JVM이 붙는 셈이라 조용히 수치를 흐린다.
# WAS_AMI_ID를 비워 두면 세 역할이 모두 AMI_ID(맨 이미지)를 쓴다.
if [ "$ROLE" = was ] && [ -n "${WAS_AMI_ID:-}" ]; then
    AMI_ID=$WAS_AMI_ID
fi

USER_DATA_FILE=$(mktemp)
trap 'rm -f "$USER_DATA_FILE"' EXIT

{
    printf '%s\n' '#!/bin/bash'
    printf '%s\n' 'set -euxo pipefail'
    printf '\n'

    # ── bootstrap.sh를 그대로 임베드 — 로직을 두 곳에 중복해 두지 않는다 ──
    printf '%s\n' "cat > /tmp/bootstrap.sh << 'PROVISION_BOOTSTRAP_EOF'"
    cat "$SCRIPT_DIR/bootstrap.sh"
    printf '%s\n' 'PROVISION_BOOTSTRAP_EOF'
    printf 'bash /tmp/bootstrap.sh %s\n' "$ROLE"
    printf '\n'

    case "$ROLE" in
        was)
            cat <<'CORRETTO_BLOCK'
# --- WAS: Corretto 21 + psql client (init-db.sh가 씀) ---
#
# 미리 구워 둔 WAS AMI에는 이 셋이 이미 들어 있다. 그때 다시 돌아도 깨지지 않아야 한다 —
# gpg --dearmor는 대상 파일이 있으면 그냥 실패하고(--yes가 없으면 덮지 않는다), user-data는
# set -e라 거기서 스크립트가 통째로 멈춘다. 그 뒤의 유닛 설치와 옛 JAR 정리가 조용히
# 건너뛰어져서, 증상은 "cloud-init status: error" 한 줄로만 남는다.
if [ ! -f /usr/share/keyrings/corretto.gpg ]; then
    wget -qO - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto.gpg
fi
echo "deb [signed-by=/usr/share/keyrings/corretto.gpg] https://apt.corretto.aws stable main" > /etc/apt/sources.list.d/corretto.list
apt-get update -y
apt-get install -y java-21-amazon-corretto-jdk postgresql-client
CORRETTO_BLOCK
            printf '%s\n' "cat > /etc/systemd/system/waiting.service << 'PROVISION_UNIT_EOF'"
            cat "$SCRIPT_DIR/waiting.service"
            printf '%s\n' 'PROVISION_UNIT_EOF'
            cat <<'WAS_ENABLE'
systemctl daemon-reload
systemctl enable waiting

# 미리 구워 둔 AMI에는 구울 당시의 JAR와 env가 남아 있다. env는 이미 없어진 Redis IP와
# 삭제된 RDS를 가리키고, JAR은 그때의 코드다. 남겨 두면 배포에서 JAR만 덮었을 때 옛 env가
# 그대로 살아 조용히 엉뚱한 주소를 본다. 여기서 확실히 멈추고 지운다.
#
# 파일명은 waiting.service의 ExecStart와 같아야 한다(waiting-0.0.1.jar). 글롭으로 지우는
# 이유는 버전이 올라가면 이름이 바뀌기 때문이다.
systemctl stop waiting || true
rm -f /opt/waiting/waiting-*.jar /etc/waiting/env

# JAR와 /etc/waiting/env가 아직 없다 — 배포 단계(scp + init-db.sh)에서 start한다.
WAS_ENABLE
            ;;
        redis)
            cat <<'REDIS_BLOCK'
# --- Redis: 클러스터 모드 노드 하나 ---
apt-get update -y
apt-get install -y redis-server
REDIS_BLOCK
            printf '%s\n' "cat > /tmp/redis.conf.base << 'PROVISION_REDISCONF_EOF'"
            cat "$SCRIPT_DIR/../redis/redis-cluster-aws.conf"
            printf '%s\n' 'PROVISION_REDISCONF_EOF'
            printf '%s\n' '[ -f /etc/redis/redis.conf.debian-orig ] || cp /etc/redis/redis.conf /etc/redis/redis.conf.debian-orig'
            if [ -n "${REDIS_MAXMEMORY:-}" ]; then
                printf 'sed "s/^maxmemory .*/maxmemory %s/" /tmp/redis.conf.base > /tmp/redis.conf.new\n' "$REDIS_MAXMEMORY"
            else
                printf '%s\n' 'cp /tmp/redis.conf.base /tmp/redis.conf.new'
            fi
            cat <<'REDIS_APPLY'
# Debian 패키지가 정하는 경로들. 커밋된 conf에 적으면 로컬(Homebrew)에서 틀린 값이 된다.
cat >> /tmp/redis.conf.new << 'REDIS_APPEND_EOF'

dir /var/lib/redis
logfile /var/log/redis/redis-server.log
pidfile /run/redis/redis-server.pid
REDIS_APPEND_EOF

# 클러스터의 노드끼리는 gossip으로 알게 된 주소로 서로 붙는다. bind가 0.0.0.0이면
# 노드가 자기 주소를 잘못 고를 수 있고, 그러면 --cluster create는 성공해 놓고 그 뒤
# 재분배나 failover에서 조용히 어긋난다. 인스턴스의 프라이빗 IP를 못 박는다 —
# 커밋된 파일에 적어 둘 수 없어 부팅 시점에 IMDS로 꺼낸다(IMDSv2라 토큰이 먼저다).
IMDS_TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/local-ipv4)
cat >> /tmp/redis.conf.new << REDIS_ANNOUNCE_EOF
cluster-announce-ip ${PRIVATE_IP}
REDIS_ANNOUNCE_EOF

install -m 640 -o redis -g redis /tmp/redis.conf.new /etc/redis/redis.conf

# 이전 클러스터의 기억이 남아 있으면 --cluster create가 "not empty"로 거절한다.
# 새 인스턴스에는 없지만, 같은 노드를 다시 프로비저닝할 때를 위해 지운다.
rm -f /var/lib/redis/nodes.conf

systemctl restart redis-server
REDIS_APPLY
            ;;
        k6)
            printf 'K6_VERSION=%s\n' "${K6_VERSION:-v2.1.0}"
            cat <<'K6_BLOCK'
# --- k6: dl.k6.io/deb에 arm64가 없어 release tarball을 쓴다 ---
apt-get update -y
apt-get install -y redis-tools sysstat
curl -fsSL -o /tmp/k6.tar.gz "https://github.com/grafana/k6/releases/download/${K6_VERSION}/k6-${K6_VERSION}-linux-arm64.tar.gz"
tar xzf /tmp/k6.tar.gz -C /tmp
install -m 755 "/tmp/k6-${K6_VERSION}-linux-arm64/k6" /usr/local/bin/k6
K6_BLOCK
            ;;
    esac
} > "$USER_DATA_FILE"

echo "-- 역할: $ROLE · 대수: $COUNT · 타입: $INSTANCE_TYPE --"

INSTANCE_IDS=$(aws ec2 run-instances \
    --image-id "$AMI_ID" \
    --instance-type "$INSTANCE_TYPE" \
    --count "$COUNT" \
    --security-group-ids "$SECURITY_GROUP_ID" \
    --subnet-id "$SUBNET_ID" \
    --key-name "$KEY_NAME" \
    --user-data "file://$USER_DATA_FILE" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$ROLE},{Key=Role,Value=$ROLE}]" \
    --query 'Instances[].InstanceId' --output text)

echo "-- 인스턴스 ID --"
echo "$INSTANCE_IDS" | tr '\t' '\n'

if [ "$WAIT" = 1 ]; then
    echo "-- running 대기 중 --"
    # shellcheck disable=SC2086
    aws ec2 wait instance-running --instance-ids $INSTANCE_IDS
    echo "-- 프라이빗 IP --"
    # shellcheck disable=SC2086
    aws ec2 describe-instances --instance-ids $INSTANCE_IDS \
        --query 'Reservations[].Instances[].[InstanceId,PrivateIpAddress]' --output table
else
    echo "IP 확인: aws ec2 describe-instances --instance-ids $(echo "$INSTANCE_IDS" | tr '\n' ' ') --query 'Reservations[].Instances[].[InstanceId,PrivateIpAddress]' --output table"
fi
