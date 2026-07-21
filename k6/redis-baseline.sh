#!/usr/bin/env bash
#
# Redis 단독 한계를 잰다. 애플리케이션을 완전히 빼고 redis-benchmark로 직접 때린다.
#
#   ./k6/redis-baseline.sh
#
# enqueue.js가 "앱을 거쳤을 때"를 잰다면 이 스크립트는 "Redis가 낼 수 있는 최대치"를 잰다.
# 둘을 비교해야 앱의 수치가 무엇을 뜻하는지 알 수 있다.
#
# 두 군데서 측정한다.
#   컨테이너 안 — Docker 네트워크를 우회한 Redis의 순수 성능
#   호스트     — 앱과 동일한 경로 (Docker 포트 포워딩 경유)
# 이 둘의 차이가 곧 Docker 네트워킹 비용이다.
# macOS에서는 Docker가 리눅스 VM 안에서 돌기 때문에 이 비용이 상당히 크다.
#
# 2026-07-22 측정 (M-series 10코어, Redis 8.8.0, 컨테이너 CPU 1코어 제한):
#   컨테이너 안  107,009 rps  (clients=100)
#   호스트        37,908 rps  (clients=100)
#   앱 전체       34,129 rps  (VUS=1000)
# → 앱은 호스트 한계에 거의 붙어 있다. 병목은 Spring Boot가 아니라 Docker 네트워킹이다.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LUA="$REPO_ROOT/waiting-server/src/main/resources/redis/enqueue.lua"
REQUESTS="${REQUESTS:-200000}"
CLIENTS="${CLIENTS:-10 50 100 500}"

command -v redis-benchmark >/dev/null || { echo "호스트에 redis-benchmark 없음: brew install redis"; exit 1; }
docker exec redis redis-cli ping >/dev/null 2>&1 || { echo "redis 컨테이너 응답 없음"; exit 1; }

# 앱이 쓰는 스크립트를 그대로 로드한다. 같은 작업을 시켜야 비교가 성립한다.
#
# docker exec에 -i가 반드시 있어야 한다. 없으면 stdin이 전달되지 않아
# redis-cli -x가 빈 문자열을 받고, 빈 문자열의 SHA1(da39a3ee...)이 조용히 돌아온다.
# 그러면 EVALSHA가 아무것도 안 하면서 벤치마크는 정상처럼 보인다.
SHA=$(docker exec -i redis redis-cli -x SCRIPT LOAD < "$LUA" | tr -d '\r')
[ "$SHA" = "da39a3ee5e6b4b0d3255bfef95601890afd80709" ] && { echo "스크립트가 비었다. docker exec -i 확인"; exit 1; }
echo "스크립트 SHA: $SHA"

# 절대 시각이라 아무 미래값이면 된다. PEXPIREAT 인자로만 쓰인다.
DEADLINE=$(( ($(date +%s) + 86400) * 1000 ))

# 실제 대기열(waiting:holiday:*)을 건드리지 않도록 별도 네임스페이스를 쓴다.
BENCH_Z="bench:z"
BENCH_SEQ="bench:seq"

bench() {  # bench <container|host> <clients>
  docker exec redis redis-cli DEL "$BENCH_Z" "$BENCH_SEQ" >/dev/null

  # 멤버에 __rand_int__를 넣는 게 중요하다. redis-benchmark가 매 요청 다른 값으로 치환한다.
  # 고정 멤버를 쓰면 ZADD가 같은 항목의 score만 갱신해 ZSet이 자라지 않고,
  # 실제 대기열과 다른 워크로드를 재게 된다.
  local ARGS=(-n "$REQUESTS" -c "$2" -P 1 -q
              evalsha "$SHA" 2 "$BENCH_Z" "$BENCH_SEQ" "m:__rand_int__" "$DEADLINE" "$DEADLINE")
  local LINE
  if [ "$1" = "host" ]; then
    LINE=$(redis-benchmark -h 127.0.0.1 -p 6379 "${ARGS[@]}" 2>/dev/null | tr '\r' '\n' | grep 'requests per second' | tail -1)
  else
    LINE=$(docker exec redis redis-benchmark "${ARGS[@]}" 2>/dev/null | tr '\r' '\n' | grep 'requests per second' | tail -1)
  fi

  # rps 숫자는 줄 앞이 아니라 명령어 접두사 뒤에 온다. '^[0-9.]+'로는 못 잡는다.
  printf "  clients=%-4s  %9s rps   %s\n" "$2" \
    "$(echo "$LINE" | grep -oE '[0-9]+\.[0-9]+ requests' | grep -oE '^[0-9.]+' | cut -d. -f1)" \
    "$(echo "$LINE" | grep -oE 'p50=[0-9.]+ msec')"
}

echo
echo "########## 컨테이너 안 (Docker 네트워크 우회) ##########"
for C in $CLIENTS; do bench container "$C"; done

echo
echo "########## 호스트 (앱과 동일 경로) ##########"
for C in $CLIENTS; do bench host "$C"; done

docker exec redis redis-cli DEL "$BENCH_Z" "$BENCH_SEQ" >/dev/null
echo
echo "bench 키 정리 완료. 두 구간의 차이가 Docker 네트워킹 비용이다."
