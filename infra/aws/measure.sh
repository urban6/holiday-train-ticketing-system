#!/usr/bin/env bash
#
# 한 번의 측정 런: 초기화 → 워밍업 → 초기화 → 본 측정 → 검증·지표 수집.
#
#   WAS_HOST=10.0.1.20 REDIS_HOST=10.0.1.30 ./measure.sh enqueue
#   WAS_HOST=... REDIS_HOST=... USERS=300000 VUS=1000 ./measure.sh enqueue
#   WAS_HOST=... REDIS_HOST=... WAITERS=5000 DURATION=2m ./measure.sh status
#   WAS_HOST=... REDIS_HOST=... RATE=10000 DURATION=5s ./measure.sh burst
#
# enqueue와 burst는 다른 천장을 잰다. 같은 축에 두면 안 된다.
#   enqueue  closed model — 응답을 받아야 다음을 보낸다. 천장은 CPU다.
#   burst    open  model — 서버 사정과 무관하게 도착한다. 천장은 커널 accept 큐다.
# burst를 쓸 때는 WAS 박스에서 런 전후로 accept 큐 초과를 함께 세야 원인이 확정된다:
#   nstat -az TcpExtListenOverflows TcpExtListenDrops TcpExtTCPReqQFullDrop
#
# 워밍업이 들어 있는 이유는 로컬에서 확인한 워밍업 민감도다 — 같은 코드·같은 부하인데
# 1런과 3런이 1.8배 차이 났다. JIT 램프라 인스턴스를 바꿔도 사라지지 않는다.
# 수백 건짜리 워밍업은 아무 효과가 없어서 기본값이 60만 건이다.
#
# 재기동 없이 여러 조건을 이어서 잴 때는 WARMUP=0으로 끄되, 뒤 조건일수록 JVM이 더 데워져
# 유리해지므로 조건 순서를 바꿔 가며 번갈아 재야 한다.
#
# 진입(enqueue)과 조회(status) 수치를 같은 축에 놓고 비교하면 안 된다 —
# 진입은 1인당 1회지만 조회는 1인당 N회다.

set -euo pipefail

SCENARIO=${1:-enqueue}
case "$SCENARIO" in
    enqueue | status | burst) ;;
    *)
        echo "usage: $0 <enqueue|status|burst>" >&2
        exit 1
        ;;
esac

: "${WAS_HOST:?WAS_HOST를 지정할 것 (WAS 프라이빗 IP)}"
: "${REDIS_HOST:?REDIS_HOST를 지정할 것 (Redis 프라이빗 IP)}"

command -v k6 > /dev/null || { echo "k6가 없다" >&2; exit 1; }
command -v redis-cli > /dev/null || { echo "redis-cli가 없다 — 초기화와 검증에 필요하다" >&2; exit 1; }

K6_DIR="$(cd "$(dirname "$0")/../../k6" && pwd)"
BASE_URL="http://$WAS_HOST:8080"

WARMUP=${WARMUP:-600000}
USERS=${USERS:-300000}
VUS=${VUS:-1000}
WAITERS=${WAITERS:-5000}
DURATION=${DURATION:-2m}
RATE=${RATE:-10000}
MAXVUS=${MAXVUS:-30000}

# 창 키는 Asia/Seoul 날짜다. TTL이 마감 + 유예라 초기화하지 않으면 같은 날 계속 누적된다.
WINDOW=$(TZ=Asia/Seoul date +%Y%m%d)
R=(redis-cli -h "$REDIS_HOST")

# active 키까지 지워야 하므로 패턴이 'waiting:'이 아니다.
reset_queue() {
    "${R[@]}" --scan --pattern '*:holiday:*' | xargs -r "${R[@]}" UNLINK > /dev/null
    echo "-- 초기화 완료 (남은 키: $("${R[@]}" --scan --pattern '*:holiday:*' | wc -l))"
}

metric() {
    curl -sf "$BASE_URL/actuator/metrics/$1" \
        | sed 's/.*"value"://; s/}.*//' || echo "n/a"
}

echo "== $SCENARIO · WAS $WAS_HOST · Redis $REDIS_HOST · 창 $WINDOW =="
reset_queue

if [ "$WARMUP" -gt 0 ]; then
    echo
    echo "== 워밍업 $WARMUP 건 (수치는 버린다) =="
    k6 run --quiet -e "BASE_URL=$BASE_URL" -e "USERS=$WARMUP" -e "VUS=$VUS" \
        "$K6_DIR/enqueue.js" > /dev/null
    reset_queue
fi

echo
echo "== 본 측정 =="
if [ "$SCENARIO" = enqueue ]; then
    k6 run -e "BASE_URL=$BASE_URL" -e "USERS=$USERS" -e "VUS=$VUS" "$K6_DIR/enqueue.js"
    EXPECTED=$USERS
elif [ "$SCENARIO" = burst ]; then
    # 과부하가 목적이라 도착의 상당수가 거부된다. 기대값은 보낸 수가 아니라
    # 성공한 201의 수이고, 그건 런이 끝나 봐야 안다 — 아래 검증에서 채운다.
    k6 run -e "BASE_URL=$BASE_URL" -e "RATE=$RATE" -e "DURATION=$DURATION" \
        -e "MAXVUS=$MAXVUS" "$K6_DIR/burst.js"
    EXPECTED=""
else
    # 조회 시나리오는 VU마다 1회씩 진입한 뒤 폴링한다.
    k6 run -e "BASE_URL=$BASE_URL" -e "WAITERS=$WAITERS" -e "DURATION=$DURATION" "$K6_DIR/status.js"
    EXPECTED=$WAITERS
fi

echo
echo "== 검증 =="
# 먼저 seq가 멈출 때까지 기다린다. k6가 graceful stop으로 손을 떼도 서버는 in-flight
# 요청을 계속 처리하는데, 그 상태에서 seq와 ZCARD를 따로 읽으면 세 값의 시점이 달라
# "waiting + active"가 seq보다 커 보인다. 시퀀스보다 항목이 많을 수는 없으므로
# 그건 시스템이 아니라 측정의 오류다 — status.lua가 ZCARD와 ZRANK를 한 스크립트로
# 묶은 것과 같은 이유이고, 실제로 rate 80,000 런에서 이 모양으로 드러났다.
SEQ=""
while :; do
    CUR=$("${R[@]}" GET "waiting:holiday:$WINDOW:seq")
    [ "${CUR:-0}" = "${SEQ:-x}" ] && break
    SEQ=${CUR:-0}
    sleep 2
done

# seq가 보낸 요청 수와 정확히 같아야 한다. 어긋나면 순번이 유실됐거나 겹친 것이다 —
# INCR과 ZADD를 Lua로 묶은 것이 부하 중에도 지켜졌는지를 보는 값이다.
#
# burst는 예외다. 과부하에서 거부된 요청은 INCR까지 못 가므로 seq가 도착 수보다 작고,
# 반대로 서버가 처리했는데 클라이언트가 응답을 못 받으면 seq가 201 수보다 크다
# (후자가 "자기가 줄 선 걸 모르는 사람"이고, enqueue.lua가 poll에 미리 써 두어
#  스위퍼가 회수할 수 있게 해 둔 바로 그 경우다). 그래서 k6의 201 수와 대조한다.
printf 'seq                  %s (기대 %s)\n' "${SEQ:-0}" "${EXPECTED:-k6의 201 성공 수}"
printf 'ZCARD waiting        %s\n' "$("${R[@]}" ZCARD "waiting:holiday:$WINDOW")"
printf 'ZCARD active         %s  <- queue.capacity를 넘으면 안 된다\n' \
    "$("${R[@]}" ZCARD "active:holiday:$WINDOW")"
printf 'used_memory_human    %s\n' \
    "$("${R[@]}" INFO memory | awk -F: '/^used_memory_human:/{print $2}' | tr -d '\r')"

echo
echo "-- 큐 지표 (스케줄러 주기에만 갱신된다) --"
printf 'queue.waiting        %s\n' "$(metric queue.waiting)"
printf 'queue.active         %s\n' "$(metric queue.active)"
printf 'queue.promoted       %s  <- ÷ 경과 시간 × 영업시간 = 대기열 총량 상한\n' "$(metric queue.promoted)"
printf 'queue.swept          %s\n' "$(metric queue.swept)"
