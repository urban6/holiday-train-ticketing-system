// 대기열 진입에 N명/초가 "동시에 도착"하는 상황. 명절 오픈 순간의 모양이다.
//
//   k6 run -e RATE=100000 -e DURATION=1s -e MAXVUS=40000 k6/burst.js
//
// RATE      초당 도착 수 (기본 10000)
// DURATION  유지 시간 (기본 5s)
// MAXVUS    k6가 쓸 VU 상한 (기본 30000)
//
// enqueue.js와 본문은 같고 executor 하나만 다르다. 그리고 그게 이 측정의 전부다.
//
//   enqueue.js  shared-iterations — 응답을 받아야 다음을 보낸다(closed model).
//               in-flight가 VUS를 넘지 않아 서버가 느려지면 도착도 함께 느려진다.
//               재는 것은 "포화 처리량"이다.
//   burst.js    constant-arrival-rate — 서버 사정과 무관하게 도착한다(open model).
//               느려져도 도착은 안 줄어서 앞단에 쌓인다. 재는 것은 "어디서 거부되는가"다.
//
// 포화에서 먼저 무너지는 것은 CPU가 아니라 커널 accept 큐다.
// 실효 백로그는 min(server.tomcat.accept-count, net.core.somaxconn)이고,
// 넘치면 커널이 연결을 그냥 버려서 클라이언트에는 connection reset으로 보인다.
// 서버 쪽에서 세려면 런 전후로:
//   nstat -az TcpExtListenOverflows TcpExtListenDrops TcpExtTCPReqQFullDrop
//
// 세 지표를 갈라 읽어야 한다. 섞으면 원인을 못 짚는다.
//   dropped_iterations > 0   k6가 VU를 못 대서 못 보낸 것이다. MAXVUS를 올리고 다시 잰다.
//                            서버의 한계가 아니라 생성기의 한계다.
//   http_req_failed (reset)  accept 큐가 넘친 것이다. 이게 진짜 절벽이다.
//   http_req_failed (5xx)    앱이 받고 나서 실패한 것이다.
//                            loadtest 프로파일의 spring.data.redis.timeout: 1s를 먼저 의심한다.
//
// 절벽 자체보다 중요한 것은 거부될 때 대기열이 깨끗한가다. 런 뒤에 확인할 등식은
// 보낸 요청 수가 아니라 성공한 접수 응답의 수를 기준으로 한다 —
// 거부가 TCP 단계에서 일어나면 enqueue.lua의 INCR이 아예 돌지 않기 때문이다.
//
//   seq == 접수 응답 수(201)
//   ZCARD waiting + ZCARD active == seq
//   ZCARD active <= queue.capacity
//
// queue.enqueue-via-kafka를 켜면 첫 줄이 달라진다. 접수(202)와 등록 사이에 컨슈머가 있고,
// 접수된 지 stale-after를 넘긴 메시지는 등록하지 않고 버리기 때문이다. 버린 수는
// actuator의 queue.enqueue.dropped에서 읽는다.
//
//   seq == 202 응답 수 - queue.enqueue.dropped
//
// 요청이 거부되는 것 자체는 정원 통제 시스템에서 설계된 동작이지 버그가 아니다.

import http from 'k6/http';
import { check } from 'k6';

const RATE = parseInt(__ENV.RATE || '10000', 10);
const DURATION = __ENV.DURATION || '5s';
const MAXVUS = parseInt(__ENV.MAXVUS || '30000', 10);
const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';

export const options = {
  scenarios: {
    burst: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // 미리 잡아 두지 않으면 램프업 동안 VU를 만드느라 도착률이 흔들린다.
      preAllocatedVUs: Math.min(RATE, MAXVUS),
      maxVUs: MAXVUS,
    },
  },
  // 실패해도 런은 끝까지 간다. 절벽 너머가 어떤 모양인지가 재려는 것이다.
  thresholds: {},
};

export default function () {
  const res = http.post(`${BASE_URL}/api/v1/waiting-queue`, null, {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { '201 CREATED 또는 202 ACCEPTED': (r) => r.status === 201 || r.status === 202 });
}
