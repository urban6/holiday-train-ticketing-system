#!/usr/bin/env bash
#
# enqueue.lua를 100만 번 실행한다. "명절 오픈 직후 100만 명이 동시에 진입 버튼을 누른다"의
# Redis 쪽 절반만 떼어 낸 것 — 앱(HTTP·Spring)은 거치지 않는다.
#
#   ./k6/enqueue-1m.sh              # 기본 100만 건, 127.0.0.1:6380
#   N=200000 ./k6/enqueue-1m.sh     # 건수 조정
#   STREAMS=4 ./k6/enqueue-1m.sh    # --pipe 프로세스를 4개로 (아래 3번)
#
# 묻는 것은 셋이다.
#   1. Redis가 죽지 않고 정확히 동작하는가  → 아래 "검증" 블록이 전부 통과해야 한다
#   2. 얼마나 걸리는가                      → 벽시계 + 서버측 usec_per_call
#   3. 그 벽시계는 누구의 한계인가          → STREAMS를 늘려 총량이 오르면 클라이언트(--pipe),
#      평평하면 Redis 단일 스레드다. 서버측 usec_per_call과 벽시계 호출당 시간이 2배 벌어져
#      있어서, 스트림을 늘려 보기 전에는 130k/s를 "Redis의 한계"라고 부를 수 없다.
#
# STREAMS를 늘려도 키는 나누지 않는다. 실제 앱도 한 창(windowId)의 같은 ZSet에 몰리므로
# 키를 쪼개면 다른 워크로드가 된다. 검증 블록은 총량 기준이라 스트림 수와 무관하게 그대로 통과해야
# 하고, 특히 pexpireat=3은 Lua 원자성 자체의 시험이다 — seq==1을 받는 호출이 정확히 하나여야 한다.
#
# redis-benchmark를 쓰지 않는 이유. 그쪽 치환자는 __rand_int__(12자리 난수) 하나뿐이라
#   - 멤버가 100만 개 중 수백 개 겹쳐 ZCARD == N 을 검증할 수 없다
#   - 멤버가 14자라 앱의 36자 UUID와 sds 크기가 달라 메모리 실측이 어긋난다
#   - ARGV[4](poll score)를 상수로 고정할 수밖에 없어 100만 항목이 전부 동점 score가 된다.
#     그러면 skiplist 삽입 위치가 멤버 사전순 = 무작위가 되는데, 실제 앱은 now+5s+60s라
#     시간에 따라 score가 밀려 거의 항상 꼬리 삽입이다. 다른 워크로드를 재게 된다.
# redis-cli --pipe는 셋 다 없고, 끝날 때 "errors: 0, replies: N"을 스스로 보고한다.
#
# 실 대기열(waiting:holiday:*)은 건드리지 않는다. bench: 네임스페이스만 쓴다.
#
# 측정 기록 (M-series 10코어, Redis 8.6.2 네이티브, maxmemory 2gb, 영속성 off):
#
#   2026-07-28  이 스크립트 (--pipe, 36자 UUID, 단조 score)
#     건수      벽시계    처리량      evalsha    zadd     p99      메모리
#     20만      1.450s   137,931/s   3.52 us   0.57 us  14.0 us   40.7MB (213 B/명)
#     100만     7.658s   130,582/s   3.79 us   0.69 us  15.0 us  189.9MB (199 B/명)
#     → 깊이 5배에 evalsha +7.7%, zadd +21%, 처리량 -5.3%. ZADD가 O(log N)이라
#       log2 17.6 -> 19.9 (+13%)에 캐시 미스가 얹힌 만큼이다. 에러 0, 전 항목 정확.
#
#   같은 날 20만을 redis-benchmark(-c 100 -P 1, 멤버 14자, poll score 상수)로 재면
#   evalsha 4.81 us / zadd 1.12 us가 나온다. 위 3.52 / 0.57과 비교하면 안 된다 —
#   파이프라인 유무와 poll ZSet의 동점 score(= 사전순 무작위 삽입)가 함께 바뀐 값이다.
#   그 차이 자체가 아래 "redis-benchmark를 쓰지 않는 이유"의 크기다.
#
#   2026-07-28  150만 건 + 스트림 스케일링 (STREAMS= 추가하며 함께 잰 것)
#
#   위 표와 같은 축에 두면 안 된다. 기준선 100만을 다시 재니 130,582/s가 아니라 99,404/s였다 —
#   VS Code 렌더러가 CPU 72%를 쓰고 load avg가 4였다. 1인당 메모리는 199 B로 소수점까지 같아
#   워크로드 자체는 동일했다. 아래 일곱 런은 그 세션 안에서 연속으로 돌린 것끼리만 비교된다.
#
#     io-threads  스트림   벽시계     처리량      evalsha   zadd     p99
#     1           1       14.539s   103,171/s   4.74 us  0.83 us  19.1 us
#     1           2       14.134s   106,127/s   4.74 us  0.84 us  19.1 us
#     1           4       13.935s   107,643/s   4.75 us  0.84 us  19.1 us
#     1           8       14.031s   106,906/s   4.85 us  0.87 us  19.1 us
#     4           1       15.165s    98,912/s   4.84 us  0.86 us  20.1 us
#     4           4       13.039s   115,039/s   4.73 us  0.83 us  19.1 us
#     4           8       12.844s   116,786/s   4.75 us  0.84 us  19.1 us
#
#   → 스트림을 8배로 늘려도 3.6%가 오르고 4에서 평평해진다. 벽시계를 쥐고 있던 건 --pipe가
#     아니라 Redis 단일 스레드다. 호출당 예산으로 보면 벽시계 9.69 us 중 스크립트 안이 4.74 us고
#     나머지 4.95 us가 RESP 파싱·디스패치·응답 버퍼, 곧 이벤트 루프다.
#   → io-threads 4는 그 4.95 us만 건드릴 수 있어 +9%(107k -> 117k)에서 멈춘다. 스트림 1개에서는
#     오히려 손해다(연결 하나가 io 스레드 하나에 묶여 홉만 늘어난다). redis.conf는 건드리지 않고
#     커맨드라인 플래그로만 켰다 — 상시로 켤 만한 이득이 아니다.
#   → 정확성은 일곱 런 전부 통과. 8스트림 동시 투입에서도 pexpireat=3, pttl=N-1이 정확했다.
#     seq==1을 받은 호출이 언제나 정확히 하나였다는 뜻이고, 이게 Lua 원자성의 직접 증거다.
#   → 메모리 307.1MB (215 B/명). 100만의 199 B/명보다 늘었는데, ZSet dict가 이 구간에서
#     2^20 -> 2^21로 rehash해 버킷이 두 배가 된 몫이 크다
#     (버킷 105만 개 x 포인터 8B x ZSet 2개 = 16.8MB, 1인당 11 B).
#
#   "150만이 5초 안에(= 300k/s)"는 이 경로로 성립하지 않는다. 단일 Redis로 12.8~14.5초이고,
#   기록이 좋았던 세션의 130k/s로 환산해도 11.5초다. 게다가 --pipe는 깊은 파이프라이닝이라
#   가장 유리한 조건이라서 위 수치는 상한으로 읽어야 한다.
#
#   5초로 줄이려면 Redis를 3대 이상으로 나눠야 한다. 그건 k6/shard-probe.sh가 재서 2.93배가
#   나왔고(100만 건 7.890s -> 2.690s), 지금 앱이 쓰는 방식이다. seq를 창 단위로 쪼개는 것이
#   아니라 토큰 해시로 나누며, 전역 seq를 두지 않는 대신 전역 순번을
#   '샤드 안 순번 x 샤드 수'로 근사한다 — 100만 번째에서 오차 0.33%다.
#   전역 카운터를 두면 그 키 하나가 샤드로 나눈 부하를 도로 모아 새 병목이 되기 때문이다.

set -euo pipefail

PORT="${PORT:-6380}"
N="${N:-1000000}"
STREAMS="${STREAMS:-1}"
WORK="${WORK:-${TMPDIR:-/tmp}}/enqueue-1m.resp"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LUA="$REPO_ROOT/waiting-server/src/main/resources/redis/enqueue.lua"

Z=bench:z; SEQ=bench:seq; POLL=bench:poll

r() { redis-cli -p "$PORT" "$@"; }
info() { r INFO "$1" | tr -d '\r'; }

command -v redis-cli >/dev/null || { echo "redis-cli 없음: brew install redis"; exit 1; }
r PING >/dev/null 2>&1 || {
  echo "127.0.0.1:$PORT 에 Redis가 없다. 프로젝트 설정 그대로 띄운다:"
  echo "  redis-server $REPO_ROOT/infra/redis/redis.conf --port $PORT --bind 127.0.0.1 --maxmemory 2gb"
  exit 1
}

# 앱이 쓰는 스크립트를 그대로 로드한다. 같은 작업을 시켜야 비교가 성립한다.
# -x가 stdin을 못 받으면 빈 문자열의 SHA1이 조용히 돌아오고, EVALSHA가 아무것도 안 하면서
# 벤치마크는 정상처럼 보인다. 그래서 가드를 둔다.
SHA=$(r -x SCRIPT LOAD < "$LUA" | tr -d '\r')
[ "$SHA" = "da39a3ee5e6b4b0d3255bfef95601890afd80709" ] && { echo "스크립트가 비었다"; exit 1; }

echo "대상    : 127.0.0.1:$PORT  (maxmemory $(r CONFIG GET maxmemory | tail -1 | tr -d '\r'))"
echo "스크립트: $SHA"
echo "건수    : $N  (--pipe 스트림 ${STREAMS}개)"
echo

# UNLINK가 아니라 DEL이다. UNLINK는 백그라운드 스레드가 나중에 해제해서
# 직후에 읽은 used_memory에 이전 실행분이 그대로 남는다 — 메모리 기준선이 음수가 된다.
r DEL "$Z" "$SEQ" "$POLL" >/dev/null
r CONFIG RESETSTAT >/dev/null
MEM0=$(info memory | awk -F: '/^used_memory:/{print $2}')

# 절대 시각이라 아무 미래값이면 된다. ARGV[2]·ARGV[3]은 PEXPIREAT 인자로만 쓰인다.
DEADLINE=$(( ($(date +%s) + 86400) * 1000 ))

# 생성은 계측 밖이다. 파이프로 바로 흘리면 UUID 100만 개 만드는 시간이 벽시계에 섞인다.
# 스트림이 여럿이면 라운드로빈으로 나눠 담는다. score는 전역 i를 그대로 쓰므로 스트림 안에서는
# 단조 증가하고 스트림끼리는 맞물린다 — 동시에 부으면 실제 도착과 같은 모양이 된다.
echo "RESP 생성 중..."
python3 -c '
import sys, uuid
sha, n, d, k, base = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
files = [open(f"{base}.{j}", "w") for j in range(k)]
for i in range(n):
    # ARGV = uuid, waitingDeadline, seqDeadline, pollDeadline
    # pollDeadline을 단조 증가시켜야 앱과 같은 꼬리 삽입이 된다 (동점 score면 무작위 삽입).
    files[i % k].write(f"EVALSHA {sha} 3 bench:z bench:seq bench:poll {uuid.uuid4()} {d} {d+3600000} {d+i}\r\n")
for f in files: f.close()
' "$SHA" "$N" "$DEADLINE" "$STREAMS" "$WORK"
echo "  $(du -ch "$WORK".[0-9]* | tail -1 | cut -f1)  $WORK.0 ...  (${STREAMS}개)"
echo

# 스트림을 동시에 띄우고 전부 끝날 때까지를 잰다. wait 실패를 치명으로 두지 않는 이유는,
# 한 스트림이 죽어도 아래 검증 블록의 ZCARD == N이 그 사실을 더 정확히 잡아 주기 때문이다.
echo "투입 중..."
TIMEFORMAT='%3R'
ELAPSED=$( { time {
  for j in $(seq 0 $((STREAMS - 1))); do
    redis-cli -p "$PORT" --pipe < "$WORK.$j" > "$WORK.out.$j" 2>&1 &
  done
  wait || true
} ; } 2>&1 )
grep -hE 'errors|Last reply' "$WORK".out.* || true
echo

# 검증 블록이 PTTL을 3번 더 부르므로, 통계는 그 전에 떠 놔야 pttl=N-1이 그대로 보인다.
MEM1=$(info memory | awk -F: '/^used_memory:/{print $2}')
CMDSTATS=$(info commandstats)
LATENCY=$(info latencystats)

########## 검증 ##########
# errorstats가 핵심 가드다. 인자 개수가 틀려도 벤치 도구는 정상처럼 보이기 때문에,
# "에러가 0건"을 별도로 확인하지 않으면 깨진 측정을 성공으로 읽는다.
echo "########## 검증 ##########"
printf '  %-22s %s\n' "GET $SEQ"        "$(r GET "$SEQ" | tr -d '\r')            (기대 $N)"
printf '  %-22s %s\n' "ZCARD $Z"        "$(r ZCARD "$Z")            (기대 $N)"
printf '  %-22s %s\n' "ZCARD $POLL"     "$(r ZCARD "$POLL")            (기대 $N)"
printf '  %-22s %s\n' "OBJECT ENCODING" "$(r OBJECT ENCODING "$Z" | tr -d '\r')  (기대 skiplist)"
printf '  %-22s %s / %s / %s\n' "PTTL (셋 다 > 0)" \
  "$(r PTTL "$Z")" "$(r PTTL "$SEQ")" "$(r PTTL "$POLL")"
echo "  errorstats           : $(info errorstats | grep -c errorstat_ || true) 종 (기대 0)"
info errorstats | grep errorstat_ || true
info stats | grep -E '^(evicted_keys|rejected_connections):' | sed 's/^/  /'
echo

echo "########## 명령 구성 (호출당 몇 번인가) ##########"
echo "$CMDSTATS" | grep -E 'cmdstat_(evalsha|zadd|incr|pttl|pexpireat):' | sed 's/^/  /'
echo "  기대: zadd=2N  incr=N  pttl=N-1(seq==1 단락)  pexpireat=3"
echo

echo "########## 시간 ##########"
echo "  벽시계        : ${ELAPSED}s   ($(python3 -c "print(f'{$N/$ELAPSED:,.0f}')") ops/s)"
echo "$LATENCY" | grep evalsha | sed 's/^/  /'
echo "  SLOWLOG LEN   : $(r SLOWLOG LEN)"
echo

echo "########## 메모리 ##########"
python3 -c "
d = $MEM1 - $MEM0
print(f'  증가          : {d/1048576:,.1f} MB   ({d/$N:.0f} bytes/명)')"
info memory | grep -E '^(used_memory_human|used_memory_rss_human|maxmemory_human|mem_fragmentation_ratio|mem_allocator):' | sed 's/^/  /'
echo

rm -f "$WORK".*
echo "정리: redis-cli -p $PORT UNLINK $Z $SEQ $POLL"
