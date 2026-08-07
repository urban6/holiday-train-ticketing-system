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
//   java -jar server/build/libs/waiting-0.0.1.jar \
//        --spring.profiles.active=loadtest
//
// 실행 후 큐에 실제로 쌓였는지 확인 (샤드마다 따로 세야 한다 — 태그는 QueueKeys.SHARD_TAGS):
//   D=$(TZ=Asia/Seoul date +%Y%m%d)
//   for p in 6380 6381 6382; do
//     for t in a b c; do redis-cli -p $p ZCARD "waiting:$D:{$t}" 2>/dev/null; done
//   done
//
// 다시 돌리기 전 초기화 (TTL이 내일 자정까지라 그냥 두면 계속 누적된다):
//   (active·poll 키까지 지워야 하므로 접두사를 셋 다 훑는다)
//   for p in 6380 6381 6382; do
//     for k in 'waiting:*' 'active:*' 'poll:*'; do
//       redis-cli -p $p --scan --pattern "$k" | xargs -r redis-cli -p $p UNLINK
//     done
//   done

import http from 'k6/http';
import { check } from 'k6';

const USERS = parseInt(__ENV.USERS || '10000', 10);
const VUS = parseInt(__ENV.VUS || '200', 10);
// localhost를 쓰면 macOS가 ::1을 먼저 시도해 요청마다 오버헤드가 붙는다.
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export const options = {
  // NO_REUSE=1이면 요청마다 연결을 새로 맺는다.
  //
  // 기본값(재사용)으로 재면 VU 하나가 연결 하나를 수천 번 우려먹어, 연결당 요청이 1,400을 넘는다.
  // 그런데 실제 오픈 순간에는 사람마다 브라우저를 새로 열어 연결당 요청이 1이다.
  // 그 차이가 처리량에 얼마나 드는지가 이 옵션으로 갈린다 — 켜고 끈 두 값의 차이가
  // 곧 연결 수립 비용이고, 그것만 바뀌므로 다른 변수가 섞이지 않는다.
  noConnectionReuse: __ENV.NO_REUSE === '1',
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
