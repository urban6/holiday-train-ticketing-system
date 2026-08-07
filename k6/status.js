// 순번 조회 API에 폴링 부하를 건다.
// 각 VU가 1회 진입해 토큰을 받은 뒤, 그 토큰으로 계속 순번을 조회한다.
//
//   k6 run -e WAITERS=5000 k6/status.js
//
// WAITERS        동시에 대기하는 사람 수 = VU 수 (기본 1000)
// POLL_JITTER    조회 간격에 더할 지터 비율 (기본 0.25 — waiting.js의 POLL_JITTER_RATIO)
//                매 조회마다 주기 ± 그 비율 사이 균등분포로 흔든다.
//                진입 직후 첫 조회 전에도 0~주기 사이 무작위 지연을 한 번 둬서,
//                수많은 VU가 같은 순간 진입해도 이후 조회 위상이 겹치지 않게 한다.
// POLL_FALLBACK  응답에 주기가 없을 때만 쓰는 값, 초 (기본 5)
// DURATION       유지 시간 (기본 1m)
//
// 조회 간격은 여기서 정하지 않는다. 서버가 순번에 따라 다르게 정해 응답(pollAfterMillis)에
// 실어 보내고, waiting.js와 마찬가지로 이 스크립트도 그 값을 그대로 따른다.
// 여기에 상수로 박아 두면 부하 테스트가 실제 클라이언트가 아니라 옛 동작을 재현하게 된다.
//
// 진입 부하와 성격이 다르다. 진입은 1인당 1회지만 조회는 1인당 N회다. 그런데 주기가
// 순번마다 다르므로 "인원 ÷ 주기" 한 줄로는 계산되지 않는다 — 하한에 붙은 앞쪽, 비례 구간,
// 상한에 붙은 뒤쪽을 나눠 더해야 한다. 대기열이 깊을수록 총합은 대부분 마지막 항이다.
//
//   초당 조회 수 = (하한 인원 / min-poll-interval)
//                + Σ(비례 구간 1인당 1/주기)
//                + (상한 인원 / max-poll-interval)
//
// enqueue.js에서 잰 수치와 같은 축에 놓고 비교하면 안 된다.
//
// **깊이를 먼저 쌓지 않으면 이 스크립트는 아무것도 재지 못한다.** 빈 큐에 VU 1000이면
// 전원이 하한 구간이라 옛 동작과 결과가 같다. 상한 구간을 만들려면 enqueue.js로 먼저
// 채운 다음(위 '단조 감소' 항목의 병행 실행과 같은 방법) 돌린다.
//
// 앱은 loadtest 프로파일로 띄울 것.
//
//   java -jar server/build/libs/waiting-0.0.1.jar \
//        --spring.profiles.active=loadtest
//
// '앞 인원은 단조 감소' 검사는 진입이 함께 일어나야 의미가 있다.
// 조회만 돌리면 큐가 줄기만 해서 어떤 구현이든 통과한다. 별도 프로세스로 같이 돌릴 것:
//
//   k6 run k6/status.js &
//   k6 run -e USERS=200000 k6/enqueue.js
//
// 다시 돌리기 전 초기화 (TTL이 내일 자정까지라 그냥 두면 계속 누적된다):
//   (active·poll 키까지 지워야 하므로 접두사를 셋 다 훑는다 — k6/enqueue.js 머리말 참고)
//   for p in 6380 6381 6382; do
//     for k in 'waiting:*' 'active:*' 'poll:*'; do
//       redis-cli -p $p --scan --pattern "$k" | xargs -r redis-cli -p $p UNLINK
//     done
//   done

import http from 'k6/http';
import { check, sleep, fail } from 'k6';

const WAITERS = parseInt(__ENV.WAITERS || '1000', 10);
const POLL_JITTER = parseFloat(__ENV.POLL_JITTER || '0.25');
const POLL_FALLBACK = parseFloat(__ENV.POLL_FALLBACK || '5');
const DURATION = __ENV.DURATION || '1m';
// localhost를 쓰면 macOS가 ::1을 먼저 시도해 요청마다 오버헤드가 붙는다.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export const options = {
  scenarios: {
    polling: {
      executor: 'constant-vus',
      vus: WAITERS,
      duration: DURATION,
    },
  },
  // 진입 요청은 준비 과정이라 조회 지연에 섞이면 안 된다.
  thresholds: {
    'http_req_duration{phase:status}': ['p(95)<500'],
  },
};

// VU별로 한 번만 진입하고, 그 뒤 반복부터는 조회만 한다.
// setup()에서 미리 만들면 모든 VU가 같은 토큰을 공유해
// 같은 ZSet 항목만 두드리게 되므로 여기서 VU마다 발급받는다.
let ticket = null;

// 직전 응답의 '내 앞' 인원. k6는 VU마다 별개의 JS 런타임을 주므로 ticket과 같은 방식으로 쓴다.
let prevAhead = Infinity;

// 서버가 마지막으로 알려 준 조회 간격(초). 응답을 받기 전까지만 fallback을 쓴다.
let pollInterval = POLL_FALLBACK;

export default function () {
  if (ticket === null) {
    const res = http.post(`${BASE_URL}/api/v1/waiting-queue`, null, {
      tags: { phase: 'enqueue' },
    });
    if (res.status !== 201) {
      fail(`진입 실패: ${res.status}`);
    }
    const body = res.json();
    ticket = { token: body.token, date: body.date };

    // 첫 주기도 진입 응답이 알려 준다. waiting.js와 같다.
    pollInterval = toSeconds(body.pollAfterMillis);

    // constant-vus는 모든 VU를 거의 같은 순간 시작시킨다. 진입 직후 곧바로 첫 조회를
    // 보내면 그 위상이 그대로 굳어 이후 조회마다 요청이 몰린다.
    // waiting.js의 첫 폴링 지연(0~주기)을 그대로 재현해 위상을 흩어 놓는다.
    sleep(Math.random() * pollInterval);
  }

  const url = `${BASE_URL}/api/v1/waiting-queue/${ticket.token}?date=${ticket.date}`;
  const res = http.get(url, { tags: { phase: 'status' } });

  // 아래 두 검사는 상태 코드를 보지 않는다. 200이 아니면 '200 OK'가 이미 실패로 잡으므로
  // 여기서 또 실패시키면 같은 사고가 두 번 세어진다.
  //
  // 입장한 뒤에는 순번 자체가 없어져 검사 대상이 아니다.
  // 가드를 빼면 승격되는 순간부터 전부 실패로 잡힌다.
  check(res, {
    '200 OK': (r) => r.status === 200,

    // 한 스냅샷에서 읽었다면 rank는 언제나 [0, total-1] 안에 있다.
    // ZRANK를 먼저 읽고 ZCARD를 나중에 읽는 식으로 갈라 놓으면, 그 사이 승격으로 total이
    // 줄어 이 범위를 벗어난다(behind가 음수가 된다).
    //
    // 예전에는 여기서 '앞 + 뒤 + 1 = 전체'를 검사했는데 그건 아무것도 검증하지 않았다.
    // 서버가 behind를 total - ahead - 1로 계산하므로(WaitingQueueService) 그 합은
    // 값이 찢어져 있어도 항상 맞는 산술 항등식이다. ahead와 total은 서로 독립된 값이라
    // 이 범위 검사는 항등식이 아니다.
    //
    // 다만 이 검사도 찢어짐의 한쪽 방향만 잡는다. 원자성 자체는 클라이언트에서 확인할 수 없어
    // StatusSnapshotTest가 비원자 버전을 결정적으로 재현해 대조한다.
    '0 <= 앞 < 전체': (r) => {
      if (r.status !== 200) return true;
      const b = r.json();
      if (b.state !== 'WAITING') return true;
      return b.ahead >= 0 && b.ahead < b.total && b.behind >= 0;
    },

    // 순번은 줄기만 해야 한다. 승격은 큐 앞에서만 빠지고(ZPOPMIN) 신규 진입은 뒤에 붙으므로
    // (seq가 INCR로 단조 증가) 내 앞 인원이 늘어날 경로가 없다.
    // 늘어난다면 seq 되감기, 같은 토큰의 재삽입(ZADD가 score를 갱신), date 혼동 중 하나다.
    //
    // 이 검사는 원자성과 무관하다 — rank는 원자적이든 아니든 시간이 지나면 줄어든다.
    // 진입 부하를 같이 걸어야 의미가 있다(위 실행 방법 참고).
    '앞 인원은 단조 감소': (r) => {
      if (r.status !== 200) return true;
      const b = r.json();
      if (b.state !== 'WAITING') return true;
      const ok = b.ahead <= prevAhead;
      prevAhead = b.ahead;
      return ok;
    },
  });

  // 서버가 이번에 알려 준 주기를 다음 조회에 그대로 쓴다.
  // 입장(ADMITTED)하면 0이 오는데, 그때는 더 물어볼 것이 없으므로 fallback으로 돌아가
  // VU가 바쁜 대기에 빠지지 않게 한다 — 이 스크립트는 입장 뒤에도 반복을 계속한다.
  if (res.status === 200) {
    const after = toSeconds(res.json().pollAfterMillis);
    pollInterval = after > 0 ? after : POLL_FALLBACK;
  }

  // 매 조회마다 같은 비율로 흔들어 위상이 다시 맞아들어가는 걸 막는다.
  // POLL_JITTER를 1보다 크게 주면 음수가 나올 수 있어 0으로 바닥을 둔다.
  sleep(Math.max(0, pollInterval * (1 + (Math.random() * 2 - 1) * POLL_JITTER)));
}

function toSeconds(millis) {
  return typeof millis === 'number' ? millis / 1000 : POLL_FALLBACK;
}
