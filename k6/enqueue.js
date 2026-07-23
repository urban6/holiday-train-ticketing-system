// 대기열 진입 API에 N명분 요청을 최대한 빠르게 밀어넣는다.
//
//   k6 run -e USERS=100000 k6/enqueue.js
//
// USERS  보낼 요청 수 (기본 10000)
// VUS    동시 실행 수 (기본 200)
//
// 앱은 loadtest 프로파일로 띄울 것.
// 요청마다 log.debug가 찍히면 큐가 아니라 로거를 측정하게 된다.
//
//   java -jar waiting-server/build/libs/waiting-0.0.1.jar \
//        --spring.profiles.active=loadtest
//
// 실행 후 큐에 실제로 쌓였는지 확인:
//   docker exec redis redis-cli ZCARD waiting:holiday:$(TZ=Asia/Seoul date +%Y%m%d)
//
// 다시 돌리기 전 초기화 (TTL이 내일 자정까지라 그냥 두면 계속 누적된다):
//   (active 키까지 지워야 하므로 패턴이 'waiting:'이 아니다)
//   docker exec redis redis-cli --scan --pattern '*:holiday:*' \
//     | xargs -n1 docker exec redis redis-cli UNLINK

import http from 'k6/http';
import { check } from 'k6';

const USERS = parseInt(__ENV.USERS || '10000', 10);
const VUS = parseInt(__ENV.VUS || '200', 10);
// localhost를 쓰면 macOS가 ::1을 먼저 시도해 요청마다 오버헤드가 붙는다.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export const options = {
  scenarios: {
    rush: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: USERS,
      maxDuration: '30m',
    },
  },
};

export default function () {
  const res = http.post(`${BASE_URL}/api/v1/waiting-queue`, null, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { '201 CREATED': (r) => r.status === 201 });
}
