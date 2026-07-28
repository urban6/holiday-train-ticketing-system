#!/usr/bin/env bash
#
# 역할별 커널·리소스 한도 튜닝.
#
#   sudo ./bootstrap.sh <was|k6|redis>
#
# 맞추지 않으면 커널이나 부하 도구가 먼저 터지고, 그 한계를 앱의 한계로 착각하게 된다.
# 로컬 측정에서 kern.ipc.somaxconn=1024와 server.tomcat.accept-count=1000이
# 필요했던 것과 같은 부류다.
#
# 지금 적용하면서 /etc/sysctl.d와 /etc/security/limits.d에도 남긴다.
# 재부팅으로 조용히 원복된 상태에서 잰 런은 앞선 런과 나란히 둘 수 없다.

set -euo pipefail

ROLE=${1:-}
case "$ROLE" in
    was | k6 | redis) ;;
    *)
        echo "usage: sudo $0 <was|k6|redis>" >&2
        exit 1
        ;;
esac

if [ "$(id -u)" -ne 0 ]; then
    echo "root로 실행할 것 (sudo)" >&2
    exit 1
fi

SYSCTL_FILE=/etc/sysctl.d/99-ticketing.conf
LIMITS_FILE=/etc/security/limits.d/99-ticketing.conf

echo "== 역할: $ROLE =="

# ── 공통 ──────────────────────────────────────────────────────────────────
#
# somaxconn: accept 대기열 상한. 톰캣의 accept-count=1000보다 커야 의미가 있다.
#            부족하면 커널이 SYN을 조용히 떨어뜨리고, k6에는 연결 오류로만 보인다.
# ip_local_port_range: 임시 포트 범위. 특히 k6 쪽이 먼저 고갈된다 —
#            keep-alive를 써도 VU 수천에 TIME_WAIT가 겹치면 기본 범위로는 모자란다.
# tcp_tw_reuse: TIME_WAIT 소켓을 나가는 연결에 재사용한다. 위와 같은 이유다.
{
    echo "# holiday-train-ticketing-system 측정용. bootstrap.sh가 생성함."
    echo "net.core.somaxconn = 4096"
    echo "net.ipv4.ip_local_port_range = 10000 65535"
    echo "net.ipv4.tcp_tw_reuse = 1"
} > "$SYSCTL_FILE"

# nofile: VUS 4,000이면 소켓만 4,000개다. 기본값 1024로는 본 측정 전에 터진다.
#         sysctl과 달리 이 파일은 다음 로그인부터 적용된다.
{
    echo "# holiday-train-ticketing-system 측정용. bootstrap.sh가 생성함."
    echo "* soft nofile 65535"
    echo "* hard nofile 65535"
} > "$LIMITS_FILE"

# ── WAS 전용 ──────────────────────────────────────────────────────────────
if [ "$ROLE" = was ]; then
    # 전용 계정으로 돌린다. waiting.service의 User와 같은 이름이어야 한다.
    id -u waiting > /dev/null 2>&1 || useradd --system --home-dir /opt/waiting --shell /sbin/nologin waiting
    install -d -o waiting -g waiting -m 755 /opt/waiting

    # 시크릿이 들어갈 자리다. 디렉터리를 미리 0750으로 만들어 두는 건, 나중에 env를
    # 손으로 복사할 때 world-readable로 남는 흔한 실수를 한 겹 막기 위해서다.
    install -d -o root -g waiting -m 750 /etc/waiting
    if [ -f /etc/waiting/env ]; then
        chown root:waiting /etc/waiting/env
        chmod 640 /etc/waiting/env
        echo "-- /etc/waiting/env 권한을 640 root:waiting으로 맞췄다"
    else
        echo "-- /etc/waiting/env 가 아직 없다. env.example을 복사해 채울 것"
    fi
fi

# ── Redis 전용 ────────────────────────────────────────────────────────────
if [ "$ROLE" = redis ]; then
    # overcommit_memory=1: Redis가 기동 때 경고하는 그것. 지금은 영속성을 껐으므로
    # 포크가 일어나지 않지만, 되살릴 때 백그라운드 저장이 조용히 실패하지 않게 해 둔다.
    echo "vm.overcommit_memory = 1" >> "$SYSCTL_FILE"

    # THP는 Redis의 지연 분포를 흔든다. 재부팅하면 돌아오므로 유닛으로 못 박는다 —
    # 원복된 줄 모르고 잰 런은 p99만 달라져서 알아채기가 특히 어렵다.
    cat > /etc/systemd/system/disable-thp.service << 'EOF'
[Unit]
Description=Disable transparent hugepages (Redis latency)
Before=redis-server.service redis.service

[Service]
Type=oneshot
ExecStart=/bin/sh -c 'echo never > /sys/kernel/mm/transparent_hugepage/enabled'
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable --now disable-thp.service
fi

sysctl --system > /dev/null
echo

echo "-- 적용된 값 --"
sysctl -n net.core.somaxconn | xargs printf 'net.core.somaxconn        = %s\n'
sysctl -n net.ipv4.ip_local_port_range | xargs printf 'ip_local_port_range       = %s %s\n'
sysctl -n net.ipv4.tcp_tw_reuse | xargs printf 'net.ipv4.tcp_tw_reuse     = %s\n'
if [ "$ROLE" = redis ]; then
    sysctl -n vm.overcommit_memory | xargs printf 'vm.overcommit_memory      = %s\n'
    printf 'transparent_hugepage      = %s\n' "$(cat /sys/kernel/mm/transparent_hugepage/enabled)"
fi

echo
echo "nofile 한도는 다시 로그인해야 올라간다. 확인: ulimit -n  (지금 세션: $(ulimit -n))"
