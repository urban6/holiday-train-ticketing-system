// 이미 줄 서 있는 사람들이 순번을 조회하는 **동안** 개시 스파이크가 들어온다.
// queue.enqueue-via-kafka가 막으려는 것이 정확히 이 상황이고, 이 스크립트가 그걸 잰다.
//
//   k6 run -e WAITERS=5000 -e RATE=100000 -e SPIKE_AT=20 -e DURATION=15s k6/mixed.js
//
// WAITERS   폴링하는 대기자 수 = 조회 시나리오의 VU 수 (기본 5000)
// RATE      스파이크의 초당 도착 수 (기본 100000)
// DURATION  스파이크 유지 시간 (기본 15s)
// MAXVUS    스파이크가 쓸 VU 상한 (기본 30000)
// SPIKE_AT  스파이크 시작 시각, 초 (기본 20)
// POLL_JITTER / POLL_FALLBACK   status.js와 같은 의미, 같은 기본값
//
// ── 왜 enqueue.js·status.js로는 안 되는가 ────────────────────────────────
//
// Kafka는 처리량 장치가 아니다. enqueue.lua와 status.lua가 Redis 단일 스레드를
// 공유하기 때문에, 진입 스파이크가 이미 줄 서 있는 사람의 조회 지연으로 번지는 것을
// 막는 것이 목적이다(application.yml의 queue.enqueue-via-kafka 주석).
//
// 그런데 기존 스크립트는 둘 중 하나만 건다. enqueue.js는 조회가 없어 경합이 없고,
// status.js는 VU마다 1회 진입이라 스파이크가 아니다. 즉 **켜기 전과 켠 뒤에 무엇이
// 달라지는지 잴 수 있는 시나리오가 없었다.** 여기가 그 자리다.
//
// 두 시나리오를 한 런에 두는 이유는 프로세스를 나눠 돌리면(status.js 헤더의 병행 실행)
// 임계값과 요약이 갈려 "조회 p95"를 한 숫자로 비교할 수 없기 때문이다.
//
// ── 읽는 법 ──────────────────────────────────────────────────────────────
//
// 조회 지연을 스파이크 전후로 갈라 태그한다. 한 런에서 두 값이 같이 나온다.
//
//   http_req_duration{phase:status,window:quiet}   스파이크 전 — 이 런의 기준선
//   http_req_duration{phase:status,window:spike}   스파이크 중 — 번짐의 크기
//
// 기준선을 같은 런에서 뽑는 것이 핵심이다. 다른 런의 수치와 비교하면 JIT·페이지 캐시가
// 섞인다(README「측정」의 경고). 보는 값은 두 절대치가 아니라 **둘의 비**이고,
// Kafka를 켜서 그 비가 1에 가까워지면 성공이다.
//
// 진입 쪽 처리량은 여기서 보지 않는다. 스파이크는 open model이라 서버가 느려져도 도착이
// 줄지 않아서, 여기 나오는 진입 지연은 포화 처리량이 아니라 대기 시간이다.
// 그 수치는 enqueue.js가 낸다.
//
// ── 먼저 깊이를 쌓아야 한다 ──────────────────────────────────────────────
//
// 빈 큐에서 시작하면 폴링 VU 전원이 큐 앞머리에 서서 조회 주기가 하한(5초)에 붙고,
// 그중 queue.capacity만큼은 곧바로 입장해 버려 조회 대상이 아니게 된다.
// measure.sh mixed가 PREFILL로 그 깊이를 먼저 만든다(그 뒤에 초기화하지 않는다 —
// WARMUP과 다른 점이 이것이다).
//
// ── 검증 등식 ────────────────────────────────────────────────────────────
//
// burst.js와 같다. Kafka를 켜면 접수(202)와 등록 사이에 컨슈머가 있어 한 항이 붙는다.
//
//   seq == 접수 응답 수 - queue.enqueue.dropped        (accepted 카운터가 앞 항이다)
//   ZCARD waiting + ZCARD active == seq
//   ZCARD active <= queue.capacity
//
// 앱은 loadtest 프로파일로 띄울 것.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep, fail } from 'k6';
import { Counter } from 'k6/metrics';

const WAITERS = parseInt(__ENV.WAITERS || '5000', 10);
const RATE = parseInt(__ENV.RATE || '100000', 10);
const MAXVUS = parseInt(__ENV.MAXVUS || '30000', 10);
const DURATION = __ENV.DURATION || '15s';
const SPIKE_AT = parseInt(__ENV.SPIKE_AT || '20', 10);
const POLL_JITTER = parseFloat(__ENV.POLL_JITTER || '0.25');
const POLL_FALLBACK = parseFloat(__ENV.POLL_FALLBACK || '5');
// localhost를 쓰면 macOS가 ::1을 먼저 시도해 요청마다 오버헤드가 붙는다.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

// 접수된 요청 수. 위 검증 등식의 첫 항이라 요약에서 바로 읽을 수 있어야 한다.
// http_reqs는 조회까지 세므로 쓸 수 없고, checks는 비율이라 개수가 아니다.
//
// 스파이크만이 아니라 폴링 VU가 처음에 하는 진입도 센다. 그쪽을 빼면 등식이 늘 WAITERS만큼
// 어긋나 있어서, 진짜 어긋남과 구별하려면 매번 그 수를 기억해 빼야 한다.
const accepted = new Counter('accepted');

// 스파이크가 끝나고도 폴링을 조금 더 이어 간다. 지연이 스파이크와 함께 곧바로
// 돌아오는지, 아니면 밀린 것이 풀리는 동안 남아 있는지가 갈리는 지점이다.
const POLL_DURATION = `${SPIKE_AT + parseDurationSeconds(DURATION) + 20}s`;

export const options = {
  scenarios: {
    // 먼저 뜬다. 진입해 자리를 잡고 조회 위상이 흩어질 시간을 SPIKE_AT만큼 갖는다.
    polling: {
      executor: 'constant-vus',
      vus: WAITERS,
      duration: POLL_DURATION,
      exec: 'poll',
    },

    // burst.js와 같은 executor다. 서버 사정과 무관하게 도착해야 "개시 순간"이 된다.
    spike: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: `${SPIKE_AT}s`,
      preAllocatedVUs: Math.min(RATE, MAXVUS),
      maxVUs: MAXVUS,
      exec: 'spike',
    },
  },

  // 판정은 스파이크 구간의 조회에만 건다. 전체 구간에 걸면 조용한 구간이 평균을
  // 끌어내려 번짐이 희석되고, 진입에 걸면 open model이라 대기 시간을 재게 된다.
  //
  // quiet 쪽은 판정이 아니라 **출력용**이다. k6는 임계값이 걸린 부분 지표만 요약에
  // 찍으므로, 선언하지 않으면 비교의 분모인 기준선이 아예 안 나온다. 그래서 실질적으로
  // 걸리지 않는 값을 준다 — 여기에 걸릴 정도면 스파이크 이전부터 이미 망가진 것이라
  // 두 값을 비교할 상황이 아니다.
  thresholds: {
    'http_req_duration{phase:status,window:spike}': ['p(95)<500'],
    'http_req_duration{phase:status,window:quiet}': ['p(95)<60000'],
  },
};

// ── 조회 ──────────────────────────────────────────────────────────────────
//
// 상태는 status.js와 같은 이유로 VU별 전역에 둔다. setup()에서 만들면 모든 VU가
// 같은 토큰을 공유해 같은 ZSet 항목만 두드린다.
let ticket = null;
let prevAhead = Infinity;
let pollInterval = POLL_FALLBACK;

export function poll() {
  if (ticket === null) {
    const res = http.post(`${BASE_URL}/api/v1/waiting-queue`, null, {
      tags: { phase: 'enqueue', window: 'prefill' },
    });
    // 202는 Kafka를 거치는 경우다. 접수만 된 상태라 아직 순번이 없고,
    // 아래 조회가 한동안 PENDING을 받는다 — 그 구간은 각 check가 state로 걸러 낸다.
    if (res.status !== 201 && res.status !== 202) {
      fail(`진입 실패: ${res.status}`);
    }
    accepted.add(1);
    const body = res.json();
    ticket = { token: body.token, windowId: body.windowId };
    pollInterval = toSeconds(body.pollAfterMillis);

    // constant-vus는 모든 VU를 거의 같은 순간 시작시킨다. 진입 직후 곧바로 첫 조회를
    // 보내면 그 위상이 그대로 굳어 이후 조회마다 요청이 몰린다.
    sleep(Math.random() * pollInterval);
  }

  const url = `${BASE_URL}/api/v1/waiting-queue/${ticket.token}?windowId=${ticket.windowId}`;
  const res = http.get(url, { tags: { phase: 'status', window: currentWindow() } });

  // 검사 셋은 status.js와 같다. 근거도 그쪽 주석에 있다 —
  // 여기서 다시 고르면 두 스크립트가 서로 다른 것을 검증하게 된다.
  check(res, {
    '200 OK': (r) => r.status === 200,

    '0 <= 앞 < 전체': (r) => {
      if (r.status !== 200) return true;
      const b = r.json();
      if (b.state !== 'WAITING') return true;
      return b.ahead >= 0 && b.ahead < b.total && b.behind >= 0;
    },

    // status.js에서는 "진입 부하를 같이 걸어야 의미가 있다"고만 적어 둔 검사다.
    // 이 스크립트에서는 스파이크가 그 조건을 항상 만족시키므로 여기가 제자리다.
    '앞 인원은 단조 감소': (r) => {
      if (r.status !== 200) return true;
      const b = r.json();
      if (b.state !== 'WAITING') return true;
      const ok = b.ahead <= prevAhead;
      prevAhead = b.ahead;
      return ok;
    },
  });

  if (res.status === 200) {
    const after = toSeconds(res.json().pollAfterMillis);
    pollInterval = after > 0 ? after : POLL_FALLBACK;
  }

  sleep(Math.max(0, pollInterval * (1 + (Math.random() * 2 - 1) * POLL_JITTER)));
}

// ── 스파이크 ──────────────────────────────────────────────────────────────
export function spike() {
  const res = http.post(`${BASE_URL}/api/v1/waiting-queue`, null, {
    tags: { phase: 'enqueue', window: 'spike' },
  });

  const ok = res.status === 201 || res.status === 202;
  if (ok) {
    accepted.add(1);
  }
  check(res, { '접수 (201 또는 202)': () => ok });
}

// 조회 지연을 스파이크 전후로 가르는 태그. 요청을 보내는 시점으로 판정하므로,
// 스파이크 직전에 출발해 스파이크 중에 응답을 받은 요청은 quiet에 남는다.
// 경계에서 한 주기치가 기준선 쪽으로 새는 셈이라, 이 태그는 번짐을 과장하지 않고
// 오히려 조금 눌러서 잰다.
function currentWindow() {
  const elapsed = exec.instance.currentTestRunDuration / 1000;
  return elapsed >= SPIKE_AT ? 'spike' : 'quiet';
}

function toSeconds(millis) {
  return typeof millis === 'number' ? millis / 1000 : POLL_FALLBACK;
}

// options는 모듈 로드 시점에 평가되므로 여기서 초 단위를 직접 구해야 한다.
// k6가 '15s' 같은 문자열만 받고 되돌려 주지는 않아서 최소한만 해석한다.
function parseDurationSeconds(text) {
  const m = /^(\d+)(ms|s|m|h)$/.exec(text);
  if (m === null) {
    throw new Error(`DURATION 형식을 읽을 수 없다: ${text} (예: 15s, 2m)`);
  }
  const n = parseInt(m[1], 10);
  return { ms: n / 1000, s: n, m: n * 60, h: n * 3600 }[m[2]];
}
