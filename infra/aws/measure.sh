#!/usr/bin/env bash
#
# 한 번의 측정 런: 초기화 → 워밍업 → 초기화 → 본 측정 → 검증·지표 수집.
#
#   WAS_HOST=10.0.1.20 REDIS_HOST=10.0.1.30 ./measure.sh enqueue
#   WAS_HOST=... REDIS_HOST=... USERS=300000 VUS=1000 ./measure.sh enqueue
#   WAS_HOST=... REDIS_HOST=... WAITERS=5000 DURATION=2m ./measure.sh status
#   WAS_HOST=... REDIS_HOST=... RATE=10000 DURATION=5s ./measure.sh burst
#   WAS_HOST=... REDIS_HOST=... PREFILL=1000000 ./measure.sh mixed
#
# 2단계(NLB + WAS N대)에서는 WAS_HOST에 NLB DNS를 주고, METRICS_HOST에 스케줄러를 보유한
# WAS의 프라이빗 IP를 따로 준다 — queue.* 지표가 프로세스 로컬이라 NLB 너머로는 못 읽는다.
#   WAS_HOST=<nlb-dns> METRICS_HOST=10.0.1.21 REDIS_HOST=... ./measure.sh enqueue
#
# Kafka를 거치는 진입(queue.enqueue-via-kafka)을 잴 때는 METRICS_HOSTS도 준다.
# 스케줄러는 1대에만 있지만 컨슈머는 전 대에 있어서, queue.enqueue.dropped만
# 한 대가 아니라 전부를 더해야 아래 검증 등식이 맞는다.
#   METRICS_HOST=10.0.1.21 METRICS_HOSTS=10.0.1.21,10.0.1.22,... ./measure.sh mixed
#
# enqueue와 burst는 서로 다른 것을 잰다. 같은 축에 두면 안 된다.
#   enqueue  closed model — 응답을 받아야 다음을 보낸다. 포화되는 곳은 CPU다.
#   burst    open  model — 서버 사정과 무관하게 도착한다. "어디서 거부되는가"를 잰다.
#
# 다만 실측에서는 **거부가 일어나지 않았다.** 도착률을 처리 능력 위로 밀어도 accept 큐 초과가
# 8대 전부 0이었다 — 가상 스레드라 톰캣이 계속 accept만 하고 넘치는 대신 안에서 줄을 선다.
# 초과분은 서버 거부가 아니라 k6의 dropped_iterations로 나타난다. 그래도 매번 함께 센다.
# 안 세면 "거부가 없었다"를 확인할 수 없고, 조건이 바뀌면 결론도 바뀐다:
#   nstat -az TcpExtListenOverflows TcpExtListenDrops TcpExtTCPReqQFullDrop
#
# burst의 MAXVUS는 서버가 아니라 **생성기가 여는 동시 연결 수**다. 5만까지 올리면 처리량이
# 오히려 27% 떨어진다 — 그건 서버의 성질이 아니라 그 조건이 만든 부하다. 낮게 잡고 시작한다.
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
#
# mixed는 앞 셋과 목적이 다르다. 처리량이 아니라 **진입 스파이크가 조회 지연으로 얼마나
# 번지는가**를 재고, 그게 queue.enqueue-via-kafka를 켜고 끄며 볼 유일한 수치다.
# 조회 지연이 스파이크 전(quiet)·중(spike)으로 갈려 나오므로 보는 것은 둘의 비다.
# 다른 셋과 달리 워밍업 뒤에 초기화하지 않는다 — 큐가 비어 있으면 폴링 VU가 전부
# 앞머리에 서서 아무것도 재지 못한다(k6/mixed.js 헤더).
#
# Redis 쪽은 본 측정 구간만 따로 계측해 아래 "-- Redis --" 블록에 찍는다.
# 다만 여기서 나오는 건 "이 부하에서 Redis가 실제로 한 일"이지 "Redis가 낼 수 있는 최대치"가
# 아니다 — WAS가 먼저 포화돼 Redis가 끝까지 밀리지 않는다. 그 분모는 k6/redis-baseline.sh가
# 앱을 빼고 같은 enqueue.lua를 직접 때려서 잰다. 둘을 나눠야 "앱이 Redis의 몇 %를 쓰는가"가 나온다.
# 비교 단위는 EVALSHA 호출/초다 — 앱의 req/s가 곧 EVALSHA 호출/초라 그대로 나란히 놓인다.

set -euo pipefail

SCENARIO=${1:-enqueue}
case "$SCENARIO" in
    enqueue | status | burst | mixed) ;;
    *)
        echo "usage: $0 <enqueue|status|burst|mixed>" >&2
        exit 1
        ;;
esac

: "${WAS_HOST:?WAS_HOST를 지정할 것 (WAS 프라이빗 IP, 2단계는 NLB DNS)}"
: "${REDIS_HOST:?REDIS_HOST를 지정할 것 (Redis 프라이빗 IP)}"

command -v k6 > /dev/null || { echo "k6가 없다" >&2; exit 1; }
command -v redis-cli > /dev/null || { echo "redis-cli가 없다 — 초기화와 검증에 필요하다" >&2; exit 1; }

K6_DIR="$(cd "$(dirname "$0")/../../k6" && pwd)"
BASE_URL="http://$WAS_HOST:8080"

# queue.* 지표는 프로세스 로컬 카운터다. 1단계(WAS 1대)는 WAS_HOST가 곧 그 인스턴스라
# METRICS_HOST를 따로 줄 필요가 없다. 2단계(NLB 뒤 WAS N대)에서 WAS_HOST가 NLB를 가리키면
# 요청이 매번 다른 인스턴스로 갈 수 있어 검증값이 런마다 들쭉날쭉해진다 — 그때는
# METRICS_HOST에 스케줄러를 보유한(queue.scheduler-enabled=true) WAS의 프라이빗 IP를 준다.
METRICS_HOST=${METRICS_HOST:-$WAS_HOST}
METRICS_URL="http://$METRICS_HOST:8080"

# 위 METRICS_HOST는 스케줄러를 가진 한 대다. 승격·회수는 그 한 대에서만 일어나므로
# queue.promoted / queue.swept / queue.waiting / queue.active는 거기서 읽는 것이 맞다.
#
# queue.enqueue.dropped만 다르다. 컨슈머는 전 대에 있어서 카운터가 흩어지고, 한 대만
# 읽으면 8대 중 1대치가 나온다 — 검증 등식(seq == 접수 수 - dropped)이 그만큼 어긋난다.
# 안 주면 METRICS_HOST 한 대로 떨어지므로 1단계·Kafka 미사용에서는 신경 쓸 것이 없다.
METRICS_HOSTS=${METRICS_HOSTS:-$METRICS_HOST}

WARMUP=${WARMUP:-600000}
USERS=${USERS:-300000}
VUS=${VUS:-1000}
WAITERS=${WAITERS:-5000}
DURATION=${DURATION:-2m}
RATE=${RATE:-10000}
MAXVUS=${MAXVUS:-30000}

# mixed 전용. 폴링 VU가 설 자리를 만드는 깊이이고, 워밍업과 달리 지우지 않는다.
# 기본 100만은 status.lua의 주기 상한 구간(6만 번째 뒤쪽, application.yml:123)을 한참
# 넘겨서, 폴링 VU가 어디에 서든 그 뒤로 깊이가 남아 있게 하는 값이다.
PREFILL=${PREFILL:-1000000}
SPIKE_AT=${SPIKE_AT:-20}

# 창 키는 Asia/Seoul 날짜다. TTL이 마감 + 유예라 초기화하지 않으면 같은 날 계속 누적된다.
WINDOW=$(TZ=Asia/Seoul date +%Y%m%d)
R=(redis-cli -h "$REDIS_HOST")

# 초당 ops 표본이 한 줄씩 쌓이는 곳. 런마다 덮어쓰므로 나란히 두려면 밖에서 옮긴다.
REDIS_STAT_LOG=${REDIS_STAT_LOG:-/tmp/redis-ops.$SCENARIO.log}

# seq 초당 표본이 쌓이는 곳. 위와 같은 이유로 런마다 덮어쓴다.
SEQ_STAT_LOG=${SEQ_STAT_LOG:-/tmp/seq.$SCENARIO.log}

# active 키까지 지워야 하므로 패턴이 'waiting:'이 아니다.
reset_queue() {
    "${R[@]}" --scan --pattern '*:holiday:*' | xargs -r "${R[@]}" UNLINK > /dev/null
    echo "-- 초기화 완료 (남은 키: $("${R[@]}" --scan --pattern '*:holiday:*' | wc -l))"
}

metric() {
    curl -sf "$METRICS_URL/actuator/metrics/$1" \
        | sed 's/.*"value"://; s/}.*//' || echo "n/a"
}

# 같은 지표를 METRICS_HOTS 전부에서 읽어 더한다. 누적 카운터에만 쓴다 —
# queue.waiting 같은 게이지는 대마다 같은 Redis를 본 값이라 더하면 대수만큼 뻥튀기된다.
# 한 대라도 못 읽으면 합계가 조용히 작아지므로 그때는 숫자 대신 n/a를 낸다.
metric_sum() {
    local total=0 host value
    for host in ${METRICS_HOSTS//,/ }; do
        value=$(curl -sf "http://$host:8080/actuator/metrics/$1" \
            | sed 's/.*"value"://; s/}.*//') || { echo "n/a"; return; }
        [ -n "$value" ] || { echo "n/a"; return; }
        total=$(awk -v a="$total" -v b="$value" 'BEGIN { printf "%.0f", a + b }')
    done
    echo "$total"
}

# ── Redis 계측 ────────────────────────────────────────────────────────────
#
# 여기서 새로 아는 값은 usec_per_call과 CPU% 둘뿐이다. 명령/초는 요청당 명령 수가
# Lua에 고정돼 있어(enqueue 5개, status 4개) 요청/초에 곱한 값이라 새 정보가 아니다.
# 쓰임새는 하나 — WAS를 늘렸는데 처리량이 기대만큼 안 오를 때 원인이 Redis인지 가르는 것.
# ops/sec이 올랐는데 usec_per_call이 그대로면 Redis는 아직 여유가 있다.
#
# "Redis가 낼 수 있는 최대치"는 여기서 안 나온다. WAS가 먼저 포화돼 Redis가 끝까지
# 밀리지 않기 때문이다. 그 분모는 k6/redis-baseline.sh가 앱을 빼고 따로 잰다.

# used_cpu_user + used_cpu_sys (초). 프로세스 시작 이후 누적이라 CONFIG RESETSTAT으로
# 리셋되지 않는다 — 런 전후로 읽어 차를 낸다.
redis_cpu_seconds() {
    "${R[@]}" INFO cpu | awk -F: '/^used_cpu_(sys|user):/ {s += $2} END {printf "%.2f", s}'
}

# 초당 명령 수를 1초마다 한 줄씩 쌓는다. Redis가 자기 창으로 계산해 주는 값을 그대로 쓴다.
#
# redis-cli --stat을 쓰지 않는 이유: 출력이 터미널이 아니면 버퍼링되어, 런이 끝나고 죽일 때
# 그 버퍼가 통째로 날아간다. 실제로 처음에 그렇게 짜서 로그가 빈 채로 나왔다.
# 이 표본 수집 자체가 초당 INFO 하나를 더 보내지만, 수만 ops 옆에서는 무시할 수준이다
# (아래 commandstats의 cmdstat_info가 그 횟수다).
sample_redis_ops() {
    while :; do
        "${R[@]}" INFO stats 2> /dev/null \
            | awk -F: '/^instantaneous_ops_per_sec:/ { print $2 + 0 }'
        sleep 1
    done > "$REDIS_STAT_LOG"
}

redis_peak_ops() {
    awk '{ n = $1 + 0; if (n > max) max = n } END { print max + 0 }' \
        "$REDIS_STAT_LOG" 2> /dev/null || echo 0
}

# ── 진입이 Redis에 들어간 실제 속도 ────────────────────────────────────────
#
# queue.enqueue-via-kafka를 켜고 끄며 볼 첫 수치다. 상한이 실제로 걸렸는지는 여기서만
# 보인다 — k6가 보는 req/s는 접수(202)까지의 속도라 상한과 무관하게 그대로 나온다.
#
# 총량 ÷ 런 시간으로 내지 않는다. mixed는 스파이크가 끝난 뒤로도 폴링이 한참 더 돌아서
# 분모가 유입 구간이 아니라 런 전체가 되고, 실제 속도의 몇 분의 1로 찍힌다.
# 대신 seq를 1초마다 떠서 초당 증가분을 낸다 — 유입이 없는 초는 0이라 저절로 빠진다.
sample_seq() {
    while :; do
        "${R[@]}" GET "waiting:holiday:$WINDOW:seq" 2> /dev/null | awk '{ print $1 + 0 }'
        sleep 1
    done > "$SEQ_STAT_LOG"
}

# 증가한 초들만 모아 중앙값·최댓값·초 수를 낸다.
#
# 중앙값을 보는 이유는 상한이 걸리면 그 구간이 평평해지기 때문이다 — 첫 초와 마지막 초는
# 표본 경계에 걸려 반 토막이 나므로 평균은 그만큼 낮게 나오고, 최댓값은 경계가 겹치는
# 순간 한 번 튀면 그 값이 된다. 상한이 걸렸는지는 평평한 구간의 높이로 판정한다.
#
# 정렬을 awk 안에서 하지 않는다. asort는 gawk 확장이라 macOS(BSD awk)와 측정 대상인
# Ubuntu(mawk) 어느 쪽에도 없다 — 위 commandstats 정렬을 sort로 뺀 것과 같은 이유다.
seq_ingest_rate() {
    awk 'NR > 1 { d = $1 - prev; if (d > 0) print d } { prev = $1 }' \
        "$SEQ_STAT_LOG" 2> /dev/null \
        | sort -n \
        | awk '
            { v[n++] = $1 }
            END {
                if (n == 0) { print "n/a n/a 0"; exit }
                printf "%.0f %.0f %d\n", v[int((n + 1) / 2) - 1], v[n - 1], n
            }
        '
}

# seq가 더 이상 오르지 않을 때까지 기다렸다가 그 값을 낸다.
#
# k6가 graceful stop으로 손을 떼도 서버는 in-flight 요청을 계속 처리하는데, 그 상태에서
# seq와 ZCARD를 따로 읽으면 세 값의 시점이 달라 "waiting + active"가 seq보다 커 보인다.
# 시퀀스보다 항목이 많을 수는 없으므로 그건 시스템이 아니라 측정의 오류다 — status.lua가
# ZCARD와 ZRANK를 한 스크립트로 묶은 것과 같은 이유이고, 실제로 rate 80,000 런에서
# 이 모양으로 드러났다.
#
# Kafka를 켜면 이 대기가 훨씬 길어진다. 진입이 접수되는 속도와 Redis에 들어가는 속도가
# 갈라져서, k6가 끝난 뒤에도 컨슈머가 ingest-per-second로 계속 밀어 넣기 때문이다.
await_seq() {
    local seq="" cur
    while :; do
        cur=$("${R[@]}" GET "waiting:holiday:$WINDOW:seq")
        [ "${cur:-0}" = "${seq:-x}" ] && break
        seq=${cur:-0}
        sleep 2
    done
    echo "${seq:-0}"
}

STAT_PID=""
stop_redis_stat() {
    [ -n "$STAT_PID" ] && kill "$STAT_PID" 2> /dev/null
    STAT_PID=""
    return 0
}

# seq 표본은 Redis 표본과 따로 멈춘다. Redis 쪽은 k6가 끝나는 순간 멈춰야 검증 루프의
# GET·ZCARD가 안 섞이지만, seq 쪽은 그 검증 루프가 도는 동안이 곧 밀린 진입이 들어가는
# 구간이라 거기까지 떠야 한다.
SEQ_PID=""
stop_seq_stat() {
    [ -n "$SEQ_PID" ] && kill "$SEQ_PID" 2> /dev/null
    SEQ_PID=""
    return 0
}

# k6가 실패해 set -e로 빠져나가도 표본 수집이 남지 않게 한다.
trap 'stop_redis_stat; stop_seq_stat' EXIT

echo "== $SCENARIO · WAS $WAS_HOST · Redis $REDIS_HOST · 창 $WINDOW =="
reset_queue

if [ "$WARMUP" -gt 0 ]; then
    echo
    echo "== 워밍업 $WARMUP 건 (수치는 버린다) =="
    k6 run --quiet -e "BASE_URL=$BASE_URL" -e "USERS=$WARMUP" -e "VUS=$VUS" \
        "$K6_DIR/enqueue.js" > /dev/null

    # 지우기 전에 다 들어오기를 기다린다. Kafka를 거치면 k6가 끝난 시점에 워밍업이
    # 아직 브로커에 남아 있어서, 여기서 곧바로 지우면 그 뒤에 컨슈머가 계속 밀어 넣는다.
    # 버려야 할 워밍업이 본 측정의 깊이에 얹히고, Kafka를 끈 런과 깊이가 달라져 둘을
    # 나란히 둘 수 없게 된다 — 실제로 Kafka on 런에서 깊이가 두 배로 나왔다.
    await_seq > /dev/null
    reset_queue
fi

# 워밍업과 반대로 이건 지우지 않는다. 폴링 VU가 설 자리를 만드는 것이 목적이라,
# 지워 버리면 전원이 큐 앞머리에 서서 주기가 하한에 붙고 일부는 곧바로 입장해 버린다.
# 진입 경로를 그대로 쓰므로 Kafka를 켜면 이 단계도 컨슈머를 거친다 — 본 측정을 시작하기
# 전에 다 들어가 있어야 해서 seq가 멈출 때까지 기다린다.
if [ "$SCENARIO" = mixed ] && [ "$PREFILL" -gt 0 ]; then
    echo
    echo "== 깊이 쌓기 $PREFILL 건 (지우지 않는다) =="
    k6 run --quiet -e "BASE_URL=$BASE_URL" -e "USERS=$PREFILL" -e "VUS=$VUS" \
        "$K6_DIR/enqueue.js" > /dev/null
    await_seq > /dev/null
    printf -- '-- 깊이: waiting %s · active %s\n' \
        "$("${R[@]}" ZCARD "waiting:holiday:$WINDOW")" \
        "$("${R[@]}" ZCARD "active:holiday:$WINDOW")"
fi

echo
echo "== 본 측정 =="
# 워밍업과 그 뒤 초기화(SCAN·UNLINK)까지 끝난 뒤에 리셋해야 이 런만의 값이 남는다.
"${R[@]}" CONFIG RESETSTAT > /dev/null
CPU_BEFORE=$(redis_cpu_seconds)
# Redis CPU와 같은 이유로 런 전후의 차를 낸다. 이 카운터는 JVM 기동 이후 누적이라,
# 앱을 재기동하지 않고 조건을 바꿔 가며 이어 재면 앞선 런의 몫이 그대로 얹혀 있다.
DROPPED_BEFORE=$(metric_sum queue.enqueue.dropped)
sample_redis_ops &
STAT_PID=$!
sample_seq &
SEQ_PID=$!
# 죽일 때 셸이 "Terminated"를 측정 출력 한가운데 찍지 않게 작업 목록에서 뗀다.
disown "$STAT_PID" 2> /dev/null || true
disown "$SEQ_PID" 2> /dev/null || true
SECONDS=0

# NO_REUSE는 k6가 시스템 환경변수를 상속해서 그냥 두면 넘어가지만, USERS·VUS처럼 명시로
# 넘긴다 — 빠뜨리면 연결 재사용이 켜져 처리량이 34% 후하게 나오는데 출력만 봐서는 어느
# 조건으로 잰 런인지 알 수 없다(README「NO_REUSE」).
NO_REUSE=${NO_REUSE:-0}

if [ "$SCENARIO" = enqueue ]; then
    k6 run -e "BASE_URL=$BASE_URL" -e "USERS=$USERS" -e "VUS=$VUS" \
        -e "NO_REUSE=$NO_REUSE" "$K6_DIR/enqueue.js"
    EXPECTED=$USERS
elif [ "$SCENARIO" = burst ]; then
    # 과부하가 목적이라 도착의 상당수가 거부된다. 기대값은 보낸 수가 아니라
    # 성공한 201의 수이고, 그건 런이 끝나 봐야 안다 — 아래 검증에서 채운다.
    k6 run -e "BASE_URL=$BASE_URL" -e "RATE=$RATE" -e "DURATION=$DURATION" \
        -e "MAXVUS=$MAXVUS" "$K6_DIR/burst.js"
    EXPECTED=""
elif [ "$SCENARIO" = mixed ]; then
    # 폴링이 돌아가는 중에 진입 스파이크가 들어간다. 기대값은 burst와 같은 이유로
    # 런이 끝나 봐야 알고, 그 수는 k6 요약의 accepted 카운터에 있다.
    #
    # PREFILL로 이미 들어간 몫이 seq에 남아 있으므로, 검증에서는 그만큼을 빼고 본다.
    SEQ_BEFORE=$("${R[@]}" GET "waiting:holiday:$WINDOW:seq")
    SEQ_BEFORE=${SEQ_BEFORE:-0}
    k6 run -e "BASE_URL=$BASE_URL" -e "WAITERS=$WAITERS" -e "RATE=$RATE" \
        -e "DURATION=$DURATION" -e "MAXVUS=$MAXVUS" -e "SPIKE_AT=$SPIKE_AT" \
        "$K6_DIR/mixed.js"
    EXPECTED=""
else
    # 조회 시나리오는 VU마다 1회씩 진입한 뒤 폴링한다.
    k6 run -e "BASE_URL=$BASE_URL" -e "WAITERS=$WAITERS" -e "DURATION=$DURATION" "$K6_DIR/status.js"
    EXPECTED=$WAITERS
fi

# 아래 검증 루프가 GET·ZCARD를 계속 보내므로 지표를 여기서 떠 둔다. 그 뒤에 읽으면 섞인다.
# k6가 graceful stop으로 손을 뗀 뒤 서버가 처리하는 in-flight 몫은 여기 안 들어오지만,
# 검증 루프의 폴링이 섞이는 쪽이 더 크게 틀린다.
ELAPSED=$SECONDS
stop_redis_stat
CPU_AFTER=$(redis_cpu_seconds)
PEAK_OPS=$(redis_peak_ops)
COMMANDSTATS=$("${R[@]}" INFO commandstats | tr -d '\r' | grep '^cmdstat_' || true)
LATENCYSTATS=$("${R[@]}" INFO latencystats | tr -d '\r' | grep '^latency_percentiles_usec_' || true)

echo
echo "== 검증 =="
# 먼저 seq가 멈출 때까지 기다린다(근거는 await_seq 주석).
SEQ=$(await_seq)
# 여기까지 떠야 밀린 진입이 들어가는 구간이 표본에 다 들어온다.
stop_seq_stat

# seq가 보낸 요청 수와 정확히 같아야 한다. 어긋나면 순번이 유실됐거나 겹친 것이다 —
# INCR과 ZADD를 Lua로 묶은 것이 부하 중에도 지켜졌는지를 보는 값이다.
#
# burst는 예외다. 과부하에서 거부된 요청은 INCR까지 못 가므로 seq가 도착 수보다 작고,
# 반대로 서버가 처리했는데 클라이언트가 응답을 못 받으면 seq가 201 수보다 크다
# (후자가 "자기가 줄 선 걸 모르는 사람"이고, enqueue.lua가 poll에 미리 써 두어
#  스위퍼가 회수할 수 있게 해 둔 바로 그 경우다). 그래서 k6의 201 수와 대조한다.
#
# Kafka를 켜면 또 하나가 달라진다. 접수(202)와 등록 사이에 컨슈머가 있고, 접수된 지
# stale-after를 넘긴 메시지는 등록하지 않고 버린다. 그래서 등식에 항이 하나 붙는다:
#
#   seq == 접수 응답 수 - queue.enqueue.dropped
#
# 아래 dropped는 METRICS_HOSTS 전부를 더한 값이다. 컨슈머가 전 대에 있어서 한 대만
# 읽으면 대수분의 1이 나온다.
printf 'seq                  %s (기대 %s)\n' "${SEQ:-0}" "${EXPECTED:-k6의 접수 성공 수}"
if [ "$SCENARIO" = mixed ]; then
    # PREFILL 몫을 빼야 이번 스파이크가 넣은 수가 된다. k6 요약의 accepted와 대조한다.
    printf 'seq (스파이크 몫)    %s  <- k6 요약의 accepted 와 대조\n' \
        "$(awk -v a="${SEQ:-0}" -v b="$SEQ_BEFORE" 'BEGIN { printf "%.0f", a - b }')"
fi
printf 'ZCARD waiting        %s\n' "$("${R[@]}" ZCARD "waiting:holiday:$WINDOW")"
printf 'ZCARD active         %s  <- queue.capacity를 넘으면 안 된다\n' \
    "$("${R[@]}" ZCARD "active:holiday:$WINDOW")"
printf 'used_memory_human    %s\n' \
    "$("${R[@]}" INFO memory | awk -F: '/^used_memory_human:/{print $2}' | tr -d '\r')"

# ── 진입이 Redis에 들어간 실제 속도 ────────────────────────────────────────
#
# Kafka를 켜고 끄며 볼 첫 수치다(근거는 seq_ingest_rate 주석).
# 켠 상태라면 중앙값이 ingest-per-second × WAS 대수에 붙어야 한다.
#
#   약 60,000/s   상한이 걸렸다. 인스턴스당 값과 partitions가 둘 다 맞다.
#   약 480,000/s  QUEUE_KAFKA_INGEST_PER_SECOND를 대수로 안 나눴다(env.example).
#   런마다 흔들림  partitions가 전체 컨슈머 스레드 수보다 적다(application.yml).
#
# 끈 상태에서는 진입이 곧 Redis 쓰기라 이 값이 그냥 처리량이 된다. 그게 비교 대상이다.
read -r INGEST_MED INGEST_MAX INGEST_SECS <<< "$(seq_ingest_rate)"
printf '\n실효 유입률          %s/s (중앙값) · %s/s (최대) · 유입 %s초\n' \
    "$INGEST_MED" "$INGEST_MAX" "$INGEST_SECS"
printf '                     <- seq 초당 증가분. 원본: %s\n' "$SEQ_STAT_LOG"

echo
echo "-- Redis (본 측정 구간만 · 경과 ${ELAPSED}s) --"
printf 'ops/sec 피크         %s  <- instantaneous_ops_per_sec 초당 표본의 최댓값\n' "$PEAK_OPS"
# 단일 스레드라 코어 1개가 분모다. 100%에 붙으면 그때부터는 WAS를 늘려도 안 는다.
printf 'CPU (코어 1개 대비)  %s%%\n' \
    "$(awk -v a="$CPU_BEFORE" -v b="$CPU_AFTER" -v t="$ELAPSED" \
        'BEGIN { if (t > 0) printf "%.1f", (b - a) / t * 100; else print "n/a" }')"
echo
# usec_per_call이 이 계측의 본체다. 요청/초가 올랐는데 이 값이 그대로면 Redis는 여유가 있고,
# 같이 늘었으면 Redis가 지분을 먹기 시작한 것이다.
echo "명령별 (calls 내림차순):"
# calls 값을 앞에 붙여 정렬하고 다시 뗀다. sort -t= -k2 는 쓰지 않는다 —
# BSD sort(macOS)는 받지만 GNU sort(측정 대상인 Ubuntu)는 구분자가 비었다며 거부한다.
echo "${COMMANDSTATS:-  (없음)}" \
    | awk -F'calls=' 'NF > 1 { split($2, a, ","); print a[1] "\t" $0; next } { print "0\t" $0 }' \
    | sort -rn | cut -f2- | head -8 | sed 's/^/  /'
if [ -n "$LATENCYSTATS" ]; then
    echo
    echo "지연 백분위 (usec):"
    echo "$LATENCYSTATS" | sed 's/^/  /'
fi
printf '\n--stat 원본: %s\n' "$REDIS_STAT_LOG"

echo
echo "-- 큐 지표 (스케줄러 주기에만 갱신된다) --"
# 넷 다 METRICS_HOST 한 대에서 읽는다. 게이지 둘은 어느 대에서 읽어도 같은 Redis를 본
# 값이고, 카운터 둘은 스케줄러를 가진 그 한 대에만 쌓인다.
printf 'queue.waiting        %s\n' "$(metric queue.waiting)"
printf 'queue.active         %s\n' "$(metric queue.active)"
printf 'queue.promoted       %s  <- ÷ 경과 시간 × 영업시간 = 대기열 총량 상한\n' "$(metric queue.promoted)"
printf 'queue.swept          %s\n' "$(metric queue.swept)"

# 이건 다르다. 컨슈머가 전 대에 있어 카운터가 흩어지므로 METRICS_HOSTS를 모두 더한다.
# 0이 아니면 접수와 등록 사이가 stale-after보다 길게 벌어졌다는 뜻이고, 그 원인은
# 보통 ingest-per-second를 대수로 안 나눠서가 아니라 너무 작게 나눠서다.
# Kafka를 끈 런에서는 컨슈머 자체가 없어 지표도 없다(n/a가 정상이다).
DROPPED_AFTER=$(metric_sum queue.enqueue.dropped)
printf 'queue.enqueue.dropped %s  <- %s 합산 · Kafka 경유 검증 등식의 뺄 항\n' \
    "$(awk -v a="$DROPPED_BEFORE" -v b="$DROPPED_AFTER" \
        'BEGIN { if (a == "n/a" || b == "n/a") print "n/a"; else printf "%.0f", b - a }')" \
    "$METRICS_HOSTS"
