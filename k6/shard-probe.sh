#!/usr/bin/env bash
#
# 대기열을 Redis 여러 대로 나눴을 때 무엇이 늘고 무엇이 틀어지는지 잰다.
# enqueue-1m.sh의 짝이다 — 그쪽이 "한 대의 천장"을 쟀고, 이쪽은 "나누면 그 천장이 배로 가는가"다.
#
#   SHARDS=1 ./k6/shard-probe.sh    # 기준선 (6380 한 대)
#   SHARDS=3 ./k6/shard-probe.sh    # 6380·6381·6382 세 대
#   N=300000 SHARDS=3 ./k6/shard-probe.sh
#
# 묻는 것은 둘이다.
#   1. 처리량이 샤드 수에 비례하는가        → 벽시계. 같은 세션 안에서 SHARDS만 바꿔 비교한다
#   2. 근사 순번의 오차가 얼마나 되는가      → 아래 "순번 근사" 블록
#
# 2번이 이 스크립트의 존재 이유다. 나눈 뒤에도 전역 순번을 정확히 알려면 조회 한 번이 모든
# 샤드를 때려야 하는데(내 샤드 ZRANK + 남의 샤드 ZCOUNT), 그러면 샤드마다 받는 호출 수가
# 나누기 전과 같아져 읽기 이득이 0이 된다. 그래서 정확한 순번을 포기하고
#
#     전역 순번 ~= ZRANK(내 샤드) x 샤드 수
#
# 로 근사한다. 균등 해시라 오차는 balls-in-bins의 1.41 x sqrt(전역순번)이 예측값이고,
# 그 예측이 맞는지를 실제 UUID로 확인하는 것이 아래 블록이다. 예측이 맞아야 이 설계가 선다.
#
# 샤드 배분은 앱이 쓸 식을 그대로 쓴다 — Math.floorMod(uuid.hashCode(), SHARDS).
# 여기서 i % SHARDS 라운드로빈으로 나누면 샤드가 완벽히 균등해져서 오차가 실제보다 작게 나온다.
# 측정하려는 것이 바로 그 불균등이므로, 배분식이 앱과 다르면 이 스크립트는 아무것도 재지 못한다.
#
# 샤드마다 seq를 따로 둔다(bench:seq.N). 공유 카운터를 두면 그 키가 새 단일 병목이 되어
# 샤딩으로 없앤 지점을 되살리게 되고, 근사 순번은 애초에 전역 seq를 필요로 하지 않는다.
#
# 실 대기열(waiting:holiday:*)은 건드리지 않는다. bench: 네임스페이스만 쓴다.
#
# 준비 — Docker가 아니라 Homebrew 네이티브다. 포트 포워딩이 처리량의 66%를 먹는다(redis-baseline.sh).
#   redis-server infra/redis/redis.conf --port 638N --bind 127.0.0.1 --maxmemory 2gb --daemonize yes
#
# 측정 기록 (M-series 10코어, Redis 8.6.2 네이티브 3대, maxmemory 2gb, 영속성 off, 100만 건):
#
#   2026-07-28  같은 세션 안에서 SHARDS만 바꿔 연속으로
#     샤드  벽시계   처리량      배수     evalsha/호출   메모리     순번 오차 p99
#     1     7.890s  126,743/s   1.00x    3.87 us       199 B/명   0 (정의상)
#     3     2.690s  371,747/s   2.93x    3.83~3.89 us  220 B/명   3,247 (0.32%)
#
#   → 처리량은 2.93배. 거의 정확히 선형이다.
#   → 그런데 호출당 시간은 3.87 us -> 3.85 us로 사실상 그대로다. 깊이가 100만에서 33만으로
#     줄어 ZADD의 log2가 19.9 -> 18.3이 됐는데도 이득이 없는데, 그 몫을 Redis 프로세스 셋이
#     코어와 캐시를 나눠 쓰는 비용이 도로 가져갔다. **즉 2.93배는 자료구조를 얕게 만들어서 온 것이
#     아니라 이벤트 루프가 셋이 되어서 온 것이다.** 같은 인스턴스 안에서 키만 3개로 쪼개면
#     이 배수는 나오지 않는다 — 단일 스레드가 그대로이기 때문이다.
#   → 메모리는 1인당 199 -> 220 B로 10% 늘었다. ZSet dict·skiplist 오버헤드가 3벌이 되는 몫이다.
#   → 정확성은 두 런 모두 통과. 샤드마다 pexpireat=3이었다(seq==1을 받은 호출이 샤드당 정확히 하나).
#
#   순번 근사 오차 (SHARDS=3, 표본 1,000개)
#     전역 순번    max 오차   1sigma    비율
#     ~10만        455       445      1.0
#     ~50만        1,883     999      1.9
#     ~100만       3,282     1,414    2.3
#   → 전 구간에서 2.4sigma 이내다. sqrt(p(k-1)) 모델이 실제 UUID 해시 분배에서도 그대로 성립한다.
#     100만 번째 사람이 보는 숫자가 최대 3,282 어긋나는데, 이는 0.33%다.
#     화면에 "999,000명"과 "1,002,282명" 중 무엇이 떠도 사람은 구별하지 못한다.
#   → 샤드 크기 자체의 이탈은 1,094명(0.328%)이었다. 오차의 뿌리가 이것이므로,
#     운영에서는 샤드별 queue.waiting이 벌어지는지가 곧 이 오차의 감시 지점이다.

set -euo pipefail

BASE_PORT="${BASE_PORT:-6380}"
SHARDS="${SHARDS:-3}"
N="${N:-1000000}"
SAMPLES="${SAMPLES:-1000}"
WORK="${WORK:-${TMPDIR:-/tmp}}/shard-probe"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LUA="$REPO_ROOT/waiting-server/src/main/resources/redis/enqueue.lua"

port() { echo $((BASE_PORT + $1)); }
r() { local s=$1; shift; redis-cli -p "$(port "$s")" "$@"; }
info() { redis-cli -p "$(port "$1")" INFO "$2" | tr -d '\r'; }

command -v redis-cli >/dev/null || { echo "redis-cli 없음: brew install redis"; exit 1; }
for s in $(seq 0 $((SHARDS - 1))); do
  r "$s" PING >/dev/null 2>&1 || {
    echo "127.0.0.1:$(port "$s") 에 Redis가 없다. 프로젝트 설정 그대로 띄운다:"
    echo "  redis-server $REPO_ROOT/infra/redis/redis.conf --port $(port "$s") --bind 127.0.0.1 --maxmemory 2gb --daemonize yes"
    exit 1
  }
done

# 앱이 쓰는 스크립트를 그대로, 모든 샤드에 같은 것을 로드한다.
# -x가 stdin을 못 받으면 빈 문자열의 SHA1이 조용히 돌아오고, EVALSHA가 아무것도 안 하면서
# 벤치마크는 정상처럼 보인다. 그래서 가드를 둔다.
SHA=""
for s in $(seq 0 $((SHARDS - 1))); do
  H=$(redis-cli -p "$(port "$s")" -x SCRIPT LOAD < "$LUA" | tr -d '\r')
  [ "$H" = "da39a3ee5e6b4b0d3255bfef95601890afd80709" ] && { echo "스크립트가 비었다"; exit 1; }
  [ -n "$SHA" ] && [ "$H" != "$SHA" ] && { echo "샤드마다 스크립트가 다르다"; exit 1; }
  SHA="$H"
done

echo "대상    : $(for s in $(seq 0 $((SHARDS - 1))); do printf '127.0.0.1:%s ' "$(port "$s")"; done)"
echo "스크립트: $SHA"
echo "건수    : $N  (샤드 ${SHARDS}개, 샤드당 --pipe 1개)"
echo

# UNLINK가 아니라 DEL이다. UNLINK는 백그라운드 스레드가 나중에 해제해서
# 직후에 읽은 used_memory에 이전 실행분이 그대로 남는다 — 메모리 기준선이 음수가 된다.
declare -a MEM0
for s in $(seq 0 $((SHARDS - 1))); do
  r "$s" DEL "bench:z.$s" "bench:seq.$s" "bench:poll.$s" >/dev/null
  r "$s" CONFIG RESETSTAT >/dev/null
  MEM0[$s]=$(info "$s" memory | awk -F: '/^used_memory:/{print $2}')
done

# 절대 시각이라 아무 미래값이면 된다. ARGV[2]·ARGV[3]은 PEXPIREAT 인자로만 쓰인다.
DEADLINE=$(( ($(date +%s) + 86400) * 1000 ))

# 생성은 계측 밖이다. 파이프로 바로 흘리면 UUID 100만 개 만드는 시간이 벽시계에 섞인다.
#
# poll score(ARGV[4])는 전역 i로 단조 증가시킨다. 앱도 now+5s+60s라 시간에 따라 밀려
# 거의 항상 꼬리 삽입인데, 상수로 고정하면 전부 동점 score가 되어 삽입 위치가 멤버 사전순
# = 무작위가 된다. 다른 워크로드를 재게 된다(enqueue-1m.sh의 같은 이유).
echo "RESP 생성 중... (Java hashCode 재현이라 조금 걸린다)"
python3 -c '
import sys, uuid
sha, n, d, k, base, nsample = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5], int(sys.argv[6])

# Java String.hashCode: h = 31*h + c 를 32비트 부호 있는 정수로 접는다.
# UUID는 항상 36자라 지수가 고정이므로, 문자마다 루프를 도는 대신 미리 접은 거듭제곱과
# 곱해 더한다 (같은 다항식이고 mod 2^32에서 분배법칙이 성립한다). 3배쯤 빠르다.
POW = [pow(31, 35 - i, 1 << 32) for i in range(36)]
def shard_of(u):
    h = sum(p * c for p, c in zip(POW, u.encode())) & 0xFFFFFFFF
    if h >= 0x80000000:
        h -= 0x100000000
    return h % k          # 파이썬 %는 제수 부호를 따라가 Math.floorMod와 같다

files = [open(f"{base}.resp.{j}", "w") for j in range(k)]
counts = [0] * k

# 오차를 잴 표본. 전역 순번 전 구간에 고르게 뿌려야 깊이별 오차를 볼 수 있다.
step = max(1, n // nsample)
smeta = [open(f"{base}.cmd.{j}", "w") for j in range(k)]
sidx  = [open(f"{base}.idx.{j}", "w") for j in range(k)]

for i in range(n):
    u = str(uuid.uuid4())
    s = shard_of(u)
    counts[s] += 1
    # ARGV = uuid, waitingDeadline, seqDeadline, pollDeadline
    files[s].write(f"EVALSHA {sha} 3 bench:z.{s} bench:seq.{s} bench:poll.{s} {u} {d} {d+3600000} {d+i}\r\n")
    if i % step == 0:
        smeta[s].write(f"ZRANK bench:z.{s} {u}\n")
        sidx[s].write(f"{i}\n")

for f in files + smeta + sidx: f.close()
sys.stderr.write("  샤드별 건수: " + " ".join(str(c) for c in counts) + "\n")
ideal = n / k
dev = max(abs(c - ideal) for c in counts)
sys.stderr.write(f"  균등 이탈  : {dev:,.0f} ({dev/ideal*100:.3f}%)   기대 ~sqrt(n/k)={ (ideal)**0.5:,.0f}\n")
' "$SHA" "$N" "$DEADLINE" "$SHARDS" "$WORK" "$SAMPLES"
echo "  $(du -ch "$WORK".resp.* | tail -1 | cut -f1)"
echo

# 샤드마다 --pipe 하나를 동시에 띄우고 전부 끝날 때까지를 잰다. 샤드가 늘면 클라이언트도
# 같이 늘어나므로, 벽시계가 안 줄면 그건 클라이언트가 아니라 Redis 쪽 이야기다.
# wait 실패를 치명으로 두지 않는 이유는, 한 스트림이 죽어도 아래 검증 블록의
# 총합 ZCARD == N 이 그 사실을 더 정확히 잡아 주기 때문이다.
echo "투입 중..."
TIMEFORMAT='%3R'
ELAPSED=$( { time {
  for s in $(seq 0 $((SHARDS - 1))); do
    redis-cli -p "$(port "$s")" --pipe < "$WORK.resp.$s" > "$WORK.out.$s" 2>&1 &
  done
  wait || true
} ; } 2>&1 )
grep -hE 'errors|Last reply' "$WORK".out.* || true
echo

# 검증 블록이 PTTL 등을 더 부르므로, 통계는 그 전에 떠 놔야 명령 구성이 그대로 보인다.
declare -a MEM1 CMDSTATS LATENCY
for s in $(seq 0 $((SHARDS - 1))); do
  MEM1[$s]=$(info "$s" memory | awk -F: '/^used_memory:/{print $2}')
  CMDSTATS[$s]=$(info "$s" commandstats)
  LATENCY[$s]=$(info "$s" latencystats)
done

########## 검증 ##########
# errorstats가 핵심 가드다. 인자 개수가 틀려도 벤치 도구는 정상처럼 보이기 때문에,
# "에러가 0건"을 별도로 확인하지 않으면 깨진 측정을 성공으로 읽는다.
echo "########## 검증 ##########"
TOT_SEQ=0; TOT_Z=0; TOT_POLL=0; TOT_ERR=0
for s in $(seq 0 $((SHARDS - 1))); do
  SEQ=$(r "$s" GET "bench:seq.$s" | tr -d '\r')
  Z=$(r "$s" ZCARD "bench:z.$s")
  P=$(r "$s" ZCARD "bench:poll.$s")
  ENC=$(r "$s" OBJECT ENCODING "bench:z.$s" | tr -d '\r')
  ERR=$(info "$s" errorstats | grep -c errorstat_ || true)
  TOT_SEQ=$((TOT_SEQ + SEQ)); TOT_Z=$((TOT_Z + Z)); TOT_POLL=$((TOT_POLL + P)); TOT_ERR=$((TOT_ERR + ERR))
  printf '  샤드%s(%s)  seq=%-9s zcard=%-9s poll=%-9s %s  pttl=%s/%s/%s  errorstats=%s종\n' \
    "$s" "$(port "$s")" "$SEQ" "$Z" "$P" "$ENC" \
    "$(r "$s" PTTL "bench:z.$s")" "$(r "$s" PTTL "bench:seq.$s")" "$(r "$s" PTTL "bench:poll.$s")" "$ERR"
  info "$s" errorstats | grep errorstat_ || true
done
printf '  ── 합계     seq=%s  zcard=%s  poll=%s   (셋 다 기대 %s)   errorstats 합=%s (기대 0)\n' \
  "$TOT_SEQ" "$TOT_Z" "$TOT_POLL" "$N" "$TOT_ERR"
echo

echo "########## 명령 구성 (샤드당) ##########"
for s in $(seq 0 $((SHARDS - 1))); do
  echo "  샤드$s:"
  echo "${CMDSTATS[$s]}" | grep -E 'cmdstat_(evalsha|zadd|incr|pttl|pexpireat):' | sed 's/^/    /'
done
echo "  기대(샤드당): zadd=2n  incr=n  pttl=n-1(seq==1 단락)  pexpireat=3"
echo "  pexpireat=3이 Lua 원자성의 직접 증거다 — 샤드마다 seq==1을 받은 호출이 정확히 하나여야 한다."
echo

echo "########## 시간 ##########"
echo "  벽시계        : ${ELAPSED}s   ($(python3 -c "print(f'{$N/$ELAPSED:,.0f}')") ops/s 총합)"
for s in $(seq 0 $((SHARDS - 1))); do
  printf '    샤드%s  %s\n' "$s" "$(echo "${LATENCY[$s]}" | grep evalsha || echo '-')"
done
echo

echo "########## 메모리 ##########"
python3 -c "
mem0 = [$(IFS=,; echo "${MEM0[*]}")]
mem1 = [$(IFS=,; echo "${MEM1[*]}")]
d = sum(mem1) - sum(mem0)
print(f'  증가(합계)    : {d/1048576:,.1f} MB   ({d/$N:.0f} bytes/명)')
for i,(a,b) in enumerate(zip(mem0, mem1)):
    print(f'    샤드{i}       : {(b-a)/1048576:,.1f} MB')"
echo

########## 순번 근사 ##########
# 여기가 이 스크립트의 본론이다. 통계를 이미 떠 놨으므로 이 블록의 ZRANK는 위 숫자를 오염시키지 않는다.
echo "########## 순번 근사 (ZRANK x $SHARDS 대 참값) ##########"
for s in $(seq 0 $((SHARDS - 1))); do
  redis-cli -p "$(port "$s")" < "$WORK.cmd.$s" > "$WORK.rank.$s"
done
python3 -c '
import sys
k, base = int(sys.argv[1]), sys.argv[2]
rows = []
for s in range(k):
    ranks = [l.strip() for l in open(f"{base}.rank.{s}") if l.strip()]
    idxs  = [int(l) for l in open(f"{base}.idx.{s}")]
    if len(ranks) != len(idxs):
        sys.exit(f"  표본 수가 어긋난다: 샤드{s} rank={len(ranks)} idx={len(idxs)}")
    for rk, i in zip(ranks, idxs):
        rows.append((i, int(rk) * k))
rows.sort()
errs = [abs(approx - i) for i, approx in rows]
deepest = rows[-1][0]

# 이론값. 전역 순번 p에서 내 샤드 순번은 Binomial(p, 1/k)이므로
# Var(순번 x k) = k^2 * p * (1/k)(1-1/k) = p(k-1). 표준편차 sqrt(p(k-1))이고 k=3이면 1.41*sqrt(p).
def sigma(p): return (p * (k - 1)) ** 0.5

errs_sorted = sorted(errs)
def q(p): return errs_sorted[min(len(errs_sorted) - 1, int(len(errs_sorted) * p))]
print(f"  표본          : {len(rows):,}개   (가장 깊은 전역 순번 {deepest:,})")
print(f"  절대 오차     : p50={q(.5):,}  p99={q(.99):,}  max={max(errs):,}")

# 상대 오차는 얕은 구간에서 의미가 없다 — 전역 30번이 39번으로 보여도 어차피 곧 입장이다.
# 화면에 큰 숫자가 뜨는 구간(전역 1만 이상)만 본다.
deep = [(i, e) for (i, _), e in zip(rows, errs) if i >= 10000]
if deep:
    rel = sorted(e / i for i, e in deep)
    print(f"  상대 오차     : p50={rel[len(rel)//2]*100:.3f}%  max={rel[-1]*100:.3f}%   (전역 1만 이상 {len(deep):,}개)")
if deepest > 0:
    print(f"  가장 깊은 곳  : 오차 {errs[-1]:,} / 참값 {deepest:,}   1sigma={sigma(deepest):,.0f}")
print()

# max는 표본 최댓값이라 1sigma보다 크게 나오는 것이 정상이다(2~3sigma). 비율로 봐야 한다.
print("  깊이별 (구간 최대 오차가 1sigma의 몇 배인가 — 4배를 넘으면 균등 분배 가정을 의심한다)")
buckets = 10
lo = 0
step = (deepest + 1) / buckets
for b in range(buckets):
    hi = (b + 1) * step
    sel = [(i, e) for (i, _), e in zip(rows, errs) if lo <= i < hi]
    lo = hi
    if not sel: continue
    mx = max(e for _, e in sel)
    p = max(i for i, _ in sel)
    s = sigma(p)
    ratio = mx / s if s else 0
    flag = "" if ratio <= 4 else "   <-- 가정 의심"
    print(f"    ~{int(hi):>10,}  max오차={mx:>8,}  1sigma={s:>9,.0f}  = {ratio:.1f}sigma{flag}")
' "$SHARDS" "$WORK"
echo

rm -f "$WORK".resp.* "$WORK".out.* "$WORK".cmd.* "$WORK".idx.* "$WORK".rank.*
echo "정리: for p in $(for s in $(seq 0 $((SHARDS - 1))); do printf '%s ' "$(port "$s")"; done); do redis-cli -p \$p UNLINK bench:z.\$((p-$BASE_PORT)) bench:seq.\$((p-$BASE_PORT)) bench:poll.\$((p-$BASE_PORT)); done"
