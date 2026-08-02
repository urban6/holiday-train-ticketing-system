#!/usr/bin/env bash
#
# Redis 단독 한계를 잰다. 애플리케이션을 완전히 빼고 redis-benchmark로 직접 때린다.
#
#   ./k6/redis-baseline.sh                                   # 로컬 기본 (127.0.0.1:6379)
#   REDIS_PORT=6380 ./k6/redis-baseline.sh                    # 로컬 네이티브 인스턴스
#   REDIS_HOST=10.0.1.30 ./k6/redis-baseline.sh               # AWS — k6 인스턴스에서
#
# REDIS_HOST  대상 호스트 (기본 127.0.0.1)
# REDIS_PORT  대상 포트   (기본 6379)
# REQUESTS    요청 수     (기본 200000)
# CLIENTS     동시 연결 수 목록 (기본 "10 50 100 500")
#
# enqueue.js가 "앱을 거쳤을 때"를 잰다면 이 스크립트는 "Redis가 낼 수 있는 최대치"를 잰다.
# infra/aws/measure.sh는 앞의 것만 잰다 — WAS가 먼저 포화돼 Redis가 끝까지 밀리지 않기
# 때문이다. 둘을 나눠야 "앱이 Redis의 몇 %를 쓰고 있는가"가 나오고, 그게 없으면 앱 수치가
# 좋은 값인지 나쁜 값인지 말할 수 없다.
#
# **비교 단위는 EVALSHA 호출/초다.** 앱은 요청 1건이 EVALSHA 1회라(enqueue.lua를 한 번 부른다)
# k6의 req/s와 여기 rps가 그대로 나란히 놓인다. 내부 명령까지 센 ops/sec과 섞지 말 것 —
# enqueue는 1건당 5명령이라 5배 차이가 난다.
#
# ── 기록 ──────────────────────────────────────────────────────────────────
#
# 아래 07-22·07-26 수치는 **키 2개짜리 옛 enqueue.lua**를 잰 값이다. 07-28의
# f46b6bb에서 poll ZSet(KEYS[3]·ARGV[4])이 붙었는데 이 스크립트가 따라가지 않아,
# 그 뒤로는 매 요청이 @user_script:20에서 에러로 끝나고 redis-benchmark가 rps를
# 아예 못 뱉는 상태였다. 지금은 키 3개·ARGV 4개로 고쳤다.
# 세대가 다르므로 아래 값과 07-29 값을 앱 수치의 분모로 섞어 쓰면 안 된다.
#
# 2026-07-22 (M-series 10코어, Redis 8.8.0, 컨테이너 CPU 1코어 제한) — 키 2개 시절:
#   컨테이너 안  107,009 rps  (clients=100)
#   호스트        37,908 rps  (Docker 포트 포워딩 경유 — 앱과 같은 경로)
#   앱 전체       34,129 rps  (VUS=1000)
#
# 2026-07-26 재측정 + 네이티브 구간 추가 (같은 세션) — 키 2개 시절:
#   컨테이너 안  107,238 rps
#   호스트        36,576 rps
#   네이티브     105,764 rps  ← Docker 밖: redis-server infra/redis/redis.conf --port 6380 --bind 127.0.0.1
#
# → Docker 포트 포워딩이 처리량의 66%를 먹는다. 다만 그 구간을 2.9배 열어도 앱은 6%만 올랐다.
#   앱은 이 경로에 묶여 있지 않다.
#
# 2026-07-29 (같은 네이티브 인스턴스, 6380) — **현재의 키 3개 enqueue.lua**:
#   clients=10     48,088 rps  p50=0.191 msec
#   clients=50     81,732 rps  p50=0.551 msec
#   clients=100    93,240 rps  p50=0.927 msec
#   clients=500    93,023 rps  p50=4.647 msec
#
# → poll ZADD 한 줄이 붙으면서 105,764 → 93,240 rps, 약 12%가 빠졌다. 폴링 기한을 진입
#   시점에 미리 굽는 대가가 이 값이다(진입해 놓고 한 번도 조회하지 않은 사람을 스위퍼가
#   찾을 수 있게 하는 것이 그 대가로 사는 것). clients=100과 500이 같은 것은 이미 단일
#   스레드가 다 찼다는 뜻이라, 연결을 더 늘려도 지연만 5배가 된다.
#
# 2026-07-29 AWS (c8g.large 전용 인스턴스, Redis 8.0.5, ap-northeast-2a, k6 인스턴스에서 실행):
#   clients=10    108,049 rps  p50=0.079 msec
#   clients=50    144,196 rps  p50=0.319 msec   ← 피크
#   clients=100   142,857 rps  p50=0.639 msec
#   clients=500   132,187 rps  p50=3.247 msec
#
# → 로컬 네이티브 93,240의 1.55배다. clients=50에서 이미 피크고 500에서 오히려 줄면서
#   p50만 10배가 되는데, 단일 스레드가 다 찼다는 뜻이다 — 연결을 더 붙여도 줄만 길어진다.
#
# 2026-07-29 2차 — **깊이를 앱과 맞춰서** 다시 잰 값. 위 20만은 얕다(-n이 곧 ZSet 깊이다).
#   깊이  20만 · 연결 50   148,367 rps  p50=0.311 msec   ← 위 144,196의 재현. 편차 2.9%
#   깊이 150만 · 연결 50   139,912 rps  p50=0.327 msec   ← 앱과 같은 조건. 이게 진짜 분모다
#   깊이 150만 · 연결 100  141,203 rps  p50=0.639 msec
#
# → 깊이가 7.5배가 돼도 5.7%만 빠진다. ZADD·ZRANK가 O(log N)이라 log2(1.5M)/log2(200k)=1.16배를
#   예상했지만 실제로는 그보다 훨씬 작았다. **O(log N)의 상수가 작다.**
#   벤치마크를 짧게 돌리면 ZSet이 얕아 실제보다 후한 분모가 나온다는 것만 기억하면 된다.
#
# 같은 세션에서 잰 앱 수치와의 비교 (진입 API, 150만 건, 분모는 139,912):
#   WAS 1대  17,313 req/s →  12.4%
#   WAS 2대  36,902 req/s →  26.4%
#   WAS 8대 138,548 req/s →  99.0%   ← Redis CPU 100~110%. 여기가 벽이다
#
# 8대에서 앱이 Redis 단독 성능의 99%를 뽑는다. 커넥션 풀·파이프라이닝 부재로 10% 넘게 샐 것으로
# 봤는데 그런 손실은 없었다. usec_per_call도 5.51 → 5.63 → 5.65μs로 거의 안 움직인다.
#
# "컨테이너 안 / 호스트" 두 구간 비교는 Docker 네트워킹 비용을 재려던 것이라 지금은
# 돌리지 않는다. 로컬 측정은 네이티브 인스턴스로 옮겼고, AWS에는 잴 대상이 아예 없다.
# 결론은 위에 남겨 둔다 — 다시 재려면 그때 Docker 구간을 되살린다.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LUA="$REPO_ROOT/server/src/main/resources/redis/enqueue.lua"

REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
REQUESTS="${REQUESTS:-200000}"
CLIENTS="${CLIENTS:-10 50 100 500}"

R=(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT")

command -v redis-benchmark > /dev/null \
    || { echo "redis-benchmark가 없다 (macOS: brew install redis, Ubuntu: apt install redis-tools)" >&2; exit 1; }
"${R[@]}" ping > /dev/null 2>&1 \
    || { echo "$REDIS_HOST:$REDIS_PORT 응답 없음" >&2; exit 1; }

# 앱이 쓰는 스크립트를 그대로 로드한다. 같은 작업을 시켜야 비교가 성립한다.
SHA=$("${R[@]}" -x SCRIPT LOAD < "$LUA" | tr -d '\r')
# 빈 문자열의 SHA1. 리다이렉션이 끊겼는데도 EVALSHA가 아무것도 안 하면서 벤치마크는
# 정상처럼 보이는 경로를 막는다.
[ "$SHA" = "da39a3ee5e6b4b0d3255bfef95601890afd80709" ] \
    && { echo "스크립트가 비었다. $LUA 확인" >&2; exit 1; }
echo "대상: $REDIS_HOST:$REDIS_PORT · 스크립트 SHA: $SHA"

# 절대 시각이라 아무 미래값이면 된다. PEXPIREAT 인자로만 쓰인다.
DEADLINE=$(( ($(date +%s) + 86400) * 1000 ))

# 실제 대기열(*:holiday:*)을 건드리지 않도록 별도 네임스페이스를 쓴다.
# measure.sh의 초기화가 '*:holiday:*'만 훑으므로 서로 지우지 않는다.
BENCH_Z="bench:z"
BENCH_SEQ="bench:seq"
BENCH_POLL="bench:poll"

bench() {  # bench <clients>
    "${R[@]}" DEL "$BENCH_Z" "$BENCH_SEQ" "$BENCH_POLL" > /dev/null

    # 멤버에 __rand_int__를 넣는 게 중요하다. redis-benchmark가 매 요청 다른 값으로 치환한다.
    # 고정 멤버를 쓰면 ZADD가 같은 항목의 score만 갱신해 ZSet이 자라지 않고,
    # 실제 대기열과 다른 워크로드를 재게 된다.
    local LINE
    LINE=$(redis-benchmark -h "$REDIS_HOST" -p "$REDIS_PORT" \
               -n "$REQUESTS" -c "$1" -P 1 -q \
               evalsha "$SHA" 3 "$BENCH_Z" "$BENCH_SEQ" "$BENCH_POLL" \
               "m:__rand_int__" "$DEADLINE" "$DEADLINE" "$DEADLINE" \
               2> /dev/null | tr '\r' '\n' | grep 'requests per second' | tail -1)

    # rps 숫자는 줄 앞이 아니라 명령어 접두사 뒤에 온다. '^[0-9.]+'로는 못 잡는다.
    printf "  clients=%-4s  %9s rps   %s\n" "$1" \
        "$(echo "$LINE" | grep -oE '[0-9]+\.[0-9]+ requests' | grep -oE '^[0-9.]+' | cut -d. -f1)" \
        "$(echo "$LINE" | grep -oE 'p50=[0-9.]+ msec')"
}

echo
for C in $CLIENTS; do bench "$C"; done

"${R[@]}" DEL "$BENCH_Z" "$BENCH_SEQ" "$BENCH_POLL" > /dev/null
echo
echo "bench 키 정리 완료."
echo "앱의 req/s(= EVALSHA 호출/초)를 위 clients=100 값으로 나누면 앱이 쓰는 Redis 지분이다."
