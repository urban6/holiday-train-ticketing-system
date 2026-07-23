// 순번 조회 API에 폴링 부하를 건다.
// 각 VU가 1회 진입해 토큰을 받은 뒤, 그 토큰으로 계속 순번을 조회한다.
//
//   k6 run -e WAITERS=5000 k6/status.js
//
// WAITERS        동시에 대기하는 사람 수 = VU 수 (기본 1000)
// POLL_INTERVAL  조회 간격, 초 (기본 2 — waiting.js의 POLL_INTERVAL_MS와 맞춘 값)
// DURATION       유지 시간 (기본 1m)
//
// 진입 부하와 성격이 다르다. 진입은 1인당 1회지만 조회는 1인당 N회라,
// 초당 조회 수 ≈ WAITERS / POLL_INTERVAL 로 곱해진다.
// enqueue.js에서 잰 수치와 같은 축에 놓고 비교하면 안 된다.
//
// 앱은 loadtest 프로파일로 띄울 것.
//
//   java -jar waiting-server/build/libs/waiting-0.0.1.jar \
//        --spring.profiles.active=loadtest
//
// 다시 돌리기 전 초기화 (TTL이 내일 자정까지라 그냥 두면 계속 누적된다):
//   (active 키까지 지워야 하므로 패턴이 'waiting:'이 아니다)
//   docker exec redis redis-cli --scan --pattern '*:holiday:*' \
//     | xargs -n1 docker exec redis redis-cli UNLINK

import http from 'k6/http';
import { check, sleep, fail } from 'k6';

const WAITERS = parseInt(__ENV.WAITERS || '1000', 10);
const POLL_INTERVAL = parseFloat(__ENV.POLL_INTERVAL || '2');
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

export default function () {
  if (ticket === null) {
    const res = http.post(`${BASE_URL}/api/v1/waiting-queue`, null, {
      tags: { phase: 'enqueue' },
    });
    if (res.status !== 201) {
      fail(`진입 실패: ${res.status}`);
    }
    const body = res.json();
    ticket = { token: body.token, windowId: body.windowId };
  }

  const url = `${BASE_URL}/api/v1/waiting-queue/${ticket.token}?windowId=${ticket.windowId}`;
  const res = http.get(url, { tags: { phase: 'status' } });

  check(res, {
    '200 OK': (r) => r.status === 200,
    // ZRANK와 ZCARD를 Lua로 묶은 게 실제로 일관된 스냅샷인지 확인한다.
    // 따로 호출했다면 그 사이 진입이 끼어들어 이 합이 어긋난다.
    //
    // 입장한 뒤에는 순번 자체가 없어져 검사 대상이 아니다.
    // 가드를 빼면 승격되는 순간부터 이 체크가 전부 실패로 잡힌다.
    '앞 + 뒤 + 1 = 전체': (r) => {
      if (r.status !== 200) return false;
      const b = r.json();
      if (b.state !== 'WAITING') return true;
      return b.ahead + b.behind + 1 === b.total;
    },
  });

  sleep(POLL_INTERVAL);
}
