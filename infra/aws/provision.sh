#!/usr/bin/env bash
#
# 역할별 EC2 인스턴스를 띄우고, user-data(cloud-init)로 부팅 시점에
# bootstrap.sh + 패키지 설치 + 설정 파일 배치까지 끝낸 상태로 준비한다.
#
#   cp provision.env.example provision.env   # 최초 1회, AMI_ID 등 채우기
#   ./provision.sh was 8                     # WAS 8대
#   ./provision.sh redis                     # Redis 1대 (기본 count=1)
#   ./provision.sh kafka --wait              # Kafka 브로커 1대 (Kafka 경유 측정에만)
#   ./provision.sh k6 --wait                 # 뜰 때까지 기다렸다 프라이빗 IP 출력
#
# user-data가 대신 끝내는 것: 커널·리소스 한도 튜닝(bootstrap.sh), 역할별 apt 설치,
# WAS는 waiting.service 설치(잡·env는 아직 없어 start는 안 함), Redis는 redis.conf 배치 +
# 재시작까지. Kafka는 설정 생성·스토리지 포맷·기동까지 — 브로커는 시크릿을 다루지 않고
# 값이 인스턴스 안에서 다 나와서 손으로 남길 게 없다.
#
# 여전히 손으로 해야 하는 것(README.md "순서" 절 3~4단계): JAR scp + 기동, DB 시크릿
# (init-db.sh → WAS1 한정 실행 → 나머지엔 scp). 둘 다 실행마다 값이 바뀌거나 시크릿을
# 다뤄서 user-data에 구우면 오히려 위험해진다.
#
# 이 스크립트는 실제 AWS 자원을 만들고 과금이 발생한다. 실행 전에 provision.env가
# 가리키는 서브넷·보안 그룹이 맞는지 한 번 더 확인할 것.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROLE=${1:-}
case "$ROLE" in
    was | k6 | redis | kafka) ;;
    *)
        echo "usage: $0 <was|k6|redis|kafka> [count] [--wait]" >&2
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
    # WAS·Redis와 달리 large가 아니다. 브로커가 받는 초당 메시지 수는 진입 요청 수와
    # 같아서 8대 포화(약 138,000 req/s)를 그대로 맞는데, 그 지점에서 브로커가 먼저
    # 막히면 재려던 Redis 대신 브로커를 재게 된다. 브로커는 측정 대상이 아니라
    # 측정 장비 쪽이므로 k6와 같은 취급으로 여유를 준다.
    kafka) INSTANCE_TYPE=${KAFKA_INSTANCE_TYPE:-c8g.xlarge} ;;
esac

USER_DATA_FILE=$(mktemp)
trap 'rm -f "$USER_DATA_FILE"' EXIT

# WAS와 Kafka가 같은 JDK를 쓴다. 브로커 쪽만 버전이 달라지면 "같은 런타임에서 쟀다"가
# 깨지므로 한 곳에서 낸다.
emit_corretto() {
    cat <<'CORRETTO_BLOCK'
wget -qO - https://apt.corretto.aws/corretto.key | gpg --dearmor -o /usr/share/keyrings/corretto.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto.gpg] https://apt.corretto.aws stable main" > /etc/apt/sources.list.d/corretto.list
apt-get update -y
CORRETTO_BLOCK
}

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
            printf '%s\n' '# --- WAS: Corretto 21 + psql client (init-db.sh가 씀) ---'
            emit_corretto
            printf '%s\n' 'apt-get install -y java-21-amazon-corretto-jdk postgresql-client'
            printf '%s\n' "cat > /etc/systemd/system/waiting.service << 'PROVISION_UNIT_EOF'"
            cat "$SCRIPT_DIR/waiting.service"
            printf '%s\n' 'PROVISION_UNIT_EOF'
            cat <<'WAS_ENABLE'
systemctl daemon-reload
systemctl enable waiting
# JAR와 /etc/waiting/env가 아직 없다 — 배포 단계(scp + init-db.sh)에서 start한다.
WAS_ENABLE
            ;;
        redis)
            cat <<'REDIS_BLOCK'
# --- Redis ---
apt-get update -y
apt-get install -y redis-server
REDIS_BLOCK
            printf '%s\n' "cat > /tmp/redis.conf.base << 'PROVISION_REDISCONF_EOF'"
            cat "$SCRIPT_DIR/../redis/redis.conf"
            printf '%s\n' 'PROVISION_REDISCONF_EOF'
            printf '%s\n' '[ -f /etc/redis/redis.conf.debian-orig ] || cp /etc/redis/redis.conf /etc/redis/redis.conf.debian-orig'
            if [ -n "${REDIS_MAXMEMORY:-}" ]; then
                printf 'sed "s/^maxmemory .*/maxmemory %s/" /tmp/redis.conf.base > /tmp/redis.conf.new\n' "$REDIS_MAXMEMORY"
            else
                printf '%s\n' 'cp /tmp/redis.conf.base /tmp/redis.conf.new'
            fi
            cat <<'REDIS_APPLY'
cat >> /tmp/redis.conf.new << 'REDIS_APPEND_EOF'

dir /var/lib/redis
logfile /var/log/redis/redis-server.log
pidfile /run/redis/redis-server.pid
REDIS_APPEND_EOF
install -m 640 -o redis -g redis /tmp/redis.conf.new /etc/redis/redis.conf
systemctl restart redis-server
REDIS_APPLY
            ;;
        kafka)
            printf '%s\n' '# --- Kafka: KRaft 단일 브로커 ---'
            emit_corretto
            printf '%s\n' 'apt-get install -y java-21-amazon-corretto-jdk'
            # 로컬 compose가 고정한 apache/kafka 태그와 같은 값이어야 한다.
            # 브로커와 클라이언트(kafka-clients)의 major가 갈리면 재현이 깨진다.
            printf 'KAFKA_VERSION=%s\n' "${KAFKA_VERSION:-4.2.1}"
            printf '%s\n' "cat > /etc/systemd/system/kafka.service << 'PROVISION_KAFKA_UNIT_EOF'"
            cat "$SCRIPT_DIR/kafka.service"
            printf '%s\n' 'PROVISION_KAFKA_UNIT_EOF'
            cat <<'KAFKA_BLOCK'
curl -fsSL -o /tmp/kafka.tgz \
    "https://archive.apache.org/dist/kafka/${KAFKA_VERSION}/kafka_2.13-${KAFKA_VERSION}.tgz"
tar xzf /tmp/kafka.tgz -C /tmp
cp -r "/tmp/kafka_2.13-${KAFKA_VERSION}/." /opt/kafka/
chown -R kafka:kafka /opt/kafka

# advertised.listeners에 들어갈 주소. 여기가 틀리면 기동은 멀쩡하고 produce만 조용히
# 타임아웃된다 — 부트스트랩 응답으로 받은 주소로 다시 붙으려다 실패하기 때문이다.
# 로컬 compose가 localhost를 쓰는 것과 같은 문제이고, EC2에서는 그 답이 프라이빗 IP다.
# 커밋된 파일에 적어 둘 수 없어서 부팅 시점에 IMDS로 꺼낸다.
IMDS_TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
PRIVATE_IP=$(curl -s -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    http://169.254.169.254/latest/meta-data/local-ipv4)

# 값은 infra/docker-compose.yml의 kafka 환경변수와 하나씩 대응한다. 로컬과 AWS가
# 다른 브로커 설정으로 돌면 "로컬에서 정합성, AWS에서 수치"라는 분업이 성립하지 않는다.
cat > /opt/kafka/config/server.properties << KAFKA_CONF_EOF
process.roles=broker,controller
node.id=1

# 컨트롤러 쿼럼은 자기 자신하고만 말한다(단일 노드). 네트워크를 건너지 않으므로
# localhost가 맞고, 프라이빗 IP를 적으면 IP가 바뀔 때 조용히 못 뜬다.
controller.quorum.voters=1@localhost:9093
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
listeners=PLAINTEXT://:9092,CONTROLLER://:9093
advertised.listeners=PLAINTEXT://${PRIVATE_IP}:9092

log.dirs=/var/lib/kafka/data

# 복제할 상대가 없다. 기본값(3)이면 내부 토픽 생성부터 실패한다.
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1

# 컨슈머가 붙을 때마다 기본 3초를 기다리면 기동 직후 측정이 그만큼 밀린다.
# WAS 8대 × 컨슈머 4개 = 32개가 순차로 붙으므로 로컬보다 더 크게 작용한다.
group.initial.rebalance.delay.ms=0

# 진입 토픽은 앱의 KafkaAdmin이 만든다(EnqueueKafkaConfig). 자동 생성을 열어 두면
# 컨슈머가 먼저 붙는 순간 브로커 기본값(1 파티션)으로 토픽이 생겨 application.yml의
# partitions가 조용히 무시된다 — 컨슈머 31개가 노는데 아무 데도 안 나오는 상태다.
auto.create.topics.enable=false
KAFKA_CONF_EOF
chown kafka:kafka /opt/kafka/config/server.properties

# 이미 포맷된 디스크에 다시 format을 걸면 실패한다. 재부팅·재실행에 안전하게 둔다.
if [ ! -f /var/lib/kafka/data/meta.properties ]; then
    CLUSTER_ID=$(/opt/kafka/bin/kafka-storage.sh random-uuid)
    runuser -u kafka -- /opt/kafka/bin/kafka-storage.sh format \
        --cluster-id "$CLUSTER_ID" --config /opt/kafka/config/server.properties
fi

systemctl daemon-reload
systemctl enable --now kafka
KAFKA_BLOCK
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
