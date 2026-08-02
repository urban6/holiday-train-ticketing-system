/*
 * 대기열 진입과 순번 폴링
 *
 * 외부 라이브러리·CDN을 쓰지 않는다. main.css와 같은 이유로,
 * 오프라인에서도 그대로 뜨고 부하 테스트에 외부 요청이 끼어들지 않게 하기 위함.
 *
 * 토큰은 이 모듈의 변수에만 둔다. sessionStorage에 넣으면 새로고침에도 살아남아
 * "새로고침하면 자리를 잃는다"는 규칙과 어긋난다.
 * 그래서 대기 화면도 별도 페이지가 아니라 랜딩 위에 뜨는 팝업이다.
 * 페이지를 이동하는 순간 메모리에 든 토큰이 사라지기 때문.
 *
 * 팝업은 네이티브 <dialog>다. 포커스 트랩, Esc 처리, 배경 딤을 직접 만들지 않아도 된다.
 * 배경 클릭으로는 안 닫히는 게 <dialog>의 기본 동작인데,
 * 실수로 자리를 잃는 걸 막아 주므로 그대로 둔다.
 */

(function () {
    'use strict';

    // 폴링 주기는 여기에 없다. 순번이 뒤일수록 성기게 묻도록 서버가 정해서 응답에 실어 보낸다
    // (진입은 token과 함께, 이후는 조회 응답의 pollAfterMillis). 승격 속도와 정원은 서버만
    // 알고 있어서, 여기서 계산하면 application.yml을 고칠 때마다 이 파일도 같이 고쳐야 한다.
    //
    // 남는 건 실패했을 때의 물러나는 폭뿐이다. 서버 응답을 못 받은 상황이라 여기서 정할 수밖에 없다.
    const POLL_BACKOFF_MAX_MS = 20000;

    // 정각에 버튼이 열리는 시나리오에서는 수많은 사용자가 같은 순간에 진입에 성공해,
    // 첫 조회부터 위상이 겹친 채로 폴링을 반복하게 된다. 그러면 그 뒤로도 계속
    // 같은 위상을 유지하며 폴링 주기마다 요청이 파도처럼 몰린다.
    // 첫 폴링과 매 주기에 무작위 지터를 더해 위상을 흩어 놓는다. 순번 계산(POST 시점)에는
    // 영향이 없다 — 순번은 Redis ZSet 진입 순서로만 정해지고, 지터는 조회 시점에만 붙는다.
    //
    // 고정 ms가 아니라 비율인 건 주기가 사람마다 다르기 때문이다. 뒤쪽에 30초를 주고 지터만
    // 1.25초로 두면 흩어지는 폭이 주기의 4%밖에 안 돼 위상이 사실상 그대로 유지된다.
    // 서버의 poll-grace가 이 폭(+25%)을 덮고 있으므로 여기를 키우려면 그쪽도 같이 봐야 한다.
    const POLL_JITTER_RATIO = 0.25;

    // Redis가 죽으면 서버는 에러를 주는 게 아니라 그냥 매달린다(기본 타임아웃 60초).
    // 클라이언트가 먼저 끊지 않으면 버튼이 잠긴 채 아무 안내도 못 준다.
    // 폴링 주기와는 무관한 값이다 — fetch abort 타임아웃은 "한 요청이 응답을 기다리는 상한"이라
    // 주기가 30초로 늘어도 그대로다.
    const ENQUEUE_TIMEOUT_MS = 10000;
    const STATUS_TIMEOUT_MS = 5000;
    const CLAIM_TIMEOUT_MS = 5000;

    // 입장 확정 후 이동할 곳.
    const ADMITTED_URL = '/login';

    // {token, windowId}. 메모리에만 산다.
    let ticket = null;
    let timer = null;

    // 서버가 마지막으로 알려 준 주기(ms)와 실제로 예약한 지연. 둘이 다른 건 지터와 백오프 때문이다.
    // 서버는 pollInterval + poll-grace를 이탈 기한으로 잡고 있으므로, 여기서 그보다 오래 쉬면
    // 시킨 대로 기다린 사용자가 줄에서 빠진다.
    let pollInterval = 0;
    let pollDelay = 0;

    // 마지막으로 조회를 보낸 시각. 탭이 돌아왔을 때 서버가 준 주기를 무시하고
    // 즉시 다시 묻는 걸 막는 데만 쓴다.
    let lastPollAt = 0;

    // 입장에 성공해 다음 화면으로 넘어가는 중인지. 그 이동도 pagehide라서, 이 플래그가 없으면
    // 방금 받은 자리를 스스로 반납하는 이탈 요청을 보내게 된다.
    let admitted = false;

    // poll() 실행 중인지. visibilitychange가 이미 도는 poll()과 겹쳐 새 poll()을 또 띄우면
    // 응답이 두 번 오고, 하필 둘 다 ADMITTED면 admit()도 두 번 불릴 수 있다.
    let inFlight = false;

    const dialog = document.getElementById('waiting-dialog');
    const button = document.getElementById('enqueue-btn');
    const closeButton = document.getElementById('waiting-close');
    const enqueueError = document.getElementById('enqueue-error');
    const status = document.getElementById('waiting-status');
    const slots = {
        position: document.getElementById('waiting-position'),
        behind: document.getElementById('waiting-behind'),
    };

    button.addEventListener('click', enqueue);

    // 닫기 버튼은 dialog를 닫기만 한다.
    // 실제 정리는 close 이벤트 한 곳에서 하는데, Esc로 닫는 경로가 따로 있기 때문이다.
    // 버튼에만 정리를 붙이면 Esc로 닫았을 때 폴링이 계속 돌아 유령 요청이 남는다.
    closeButton.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', leaveQueue);

    // 브라우저가 비활성 탭의 타이머를 강하게 늦추므로, 돌아왔다는 신호(visibilitychange)가
    // 다음 예약된 타이머를 기다리는 것보다 빠르다. 입장권 grace(150초)를 놓치는 가장 흔한
    // 원인이 이 지연이라 탭이 보이는 즉시 폴링을 재개한다.
    //
    // 다만 주기가 지나기 전에 돌아왔다면 남은 만큼만 다시 예약한다. 뒤쪽 사람은 주기가 30초까지
    // 늘어나는데, 그걸 무시하고 매번 즉시 묻게 두면 탭 전환 한 번이 주기를 통째로 무효로 만든다.
    // 포커스 이동은 사용자들 사이에 상관돼 있어서 그 요청이 한꺼번에 몰린다.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState !== 'visible' || !ticket || inFlight) {
            return;
        }

        clearTimeout(timer);
        timer = null;

        const remaining = pollInterval - (Date.now() - lastPollAt);
        if (remaining > 0) {
            timer = setTimeout(poll, remaining);
            return;
        }
        poll();
    });

    /*
     * 탭 닫기·브라우저 종료·새로고침·다른 페이지로 이동. dialog의 close 이벤트가 오지 않는
     * 경로라 여기서 따로 잡는다.
     *
     * beforeunload가 아니라 pagehide다. 전자는 모바일에서 발화하지 않는 경우가 많고
     * bfcache를 무효화한다.
     *
     * visibilitychange의 hidden을 여기에 같이 걸면 안 된다. 탭 전환만으로도 hidden이 떠서
     * 잠깐 다른 탭에 다녀온 정상 사용자를 줄에서 빼내게 된다. 이 앱은 같은 이벤트를 위에서
     * 정반대 용도(돌아오면 폴링 즉시 재개)로 이미 쓰고 있어, 둘을 같이 걸면 서로 싸운다.
     *
     * 새로고침에서도 보낸다. 토큰이 메모리에만 살아 "새로고침하면 자리를 잃는다"가 원래 규칙인데,
     * 지금까지는 규칙상 잃었는데 서버에는 그대로 남아 있었다.
     */
    window.addEventListener('pagehide', () => {
        if (ticket && !admitted) {
            sendLeave(ticket);
        }
    });

    function leaveQueue() {
        clearTimeout(timer);
        timer = null;

        // ticket이 남아 있다는 건 서버에 아직 내 자리가 있다고 믿는다는 뜻이다.
        // 만료(404)·판매 종료(403)·입장 실패 경로는 close() 전에 ticket을 비우므로 여기서 걸러진다.
        // 그 셋은 이미 서버에서 사라진 토큰이라 이탈 요청이 순수한 낭비인데,
        // 하필 여러 사용자에게 동시에 터지는 상황이라 그 순간 요청이 한꺼번에 몰린다.
        if (ticket) {
            sendLeave(ticket);
        }

        ticket = null;
        button.disabled = false;
    }

    /*
     * 응답을 보지 않는다. 서버는 이미 없는 토큰에도 204를 주고, 페이지가 사라지는 중이면
     * 받을 화면도 없다.
     *
     * fetch가 아니라 sendBeacon인 이유는 pagehide 때문이다 — 일반 fetch는 페이지가 죽으면서
     * 함께 취소되지만, beacon은 브라우저가 페이지 사후에 대신 보내 준다.
     * 그래서 서버 API도 DELETE가 아니라 POST다. sendBeacon은 POST만 보낸다.
     */
    function sendLeave(t) {
        const url = '/api/v1/waiting-queue/' + encodeURIComponent(t.token)
            + '/leave?windowId=' + encodeURIComponent(t.windowId);
        navigator.sendBeacon(url);
    }

    async function enqueue() {
        button.disabled = true;
        hideEnqueueError();

        let res;
        try {
            res = await fetch('/api/v1/waiting-queue', {
                method: 'POST',
                signal: AbortSignal.timeout(ENQUEUE_TIMEOUT_MS),
            });
        } catch (e) {
            showEnqueueError('서버가 응답하지 않습니다. 잠시 후 다시 시도해 주세요.');
            button.disabled = false;
            return;
        }

        if (!res.ok) {
            const body = await readJson(res);
            showEnqueueError(body?.message || '대기열에 들어가지 못했습니다. 잠시 후 다시 시도해 주세요.');
            button.disabled = false;
            return;
        }

        const body = await res.json();
        ticket = { token: body.token, windowId: body.windowId };

        resetFigures();
        dialog.showModal();

        // 첫 주기도 서버가 알려 준다. 아직 순번을 모르는 시점이라 항상 하한이고,
        // 순번에 맞는 주기는 첫 응답부터 붙는다.
        pollInterval = body.pollAfterMillis;

        // 진입은 몰려도(POST) 첫 조회는 몰리지 않게, 0~주기 구간에서 무작위로 골라
        // 첫 요청부터 위상을 흩어 놓는다.
        pollDelay = Math.random() * pollInterval;
        timer = setTimeout(poll, pollDelay);
    }

    /*
     * setInterval이 아니라 setTimeout 체인이다.
     * 응답이 폴링 주기보다 느려지면 setInterval은 요청을 계속 겹쳐 쌓는데,
     * 대기 인원이 많을수록 느려지는 구조라 그때 부하를 스스로 키우게 된다.
     */
    async function poll() {
        if (!ticket || inFlight) {
            return;
        }
        inFlight = true;
        lastPollAt = Date.now();

        try {
            const url = '/api/v1/waiting-queue/' + encodeURIComponent(ticket.token)
                + '?windowId=' + encodeURIComponent(ticket.windowId);

            let res;
            try {
                res = await fetch(url, { signal: AbortSignal.timeout(STATUS_TIMEOUT_MS) });
            } catch (e) {
                retryLater('연결이 불안정합니다. 다시 확인하는 중…');
                return;
            }

            // fetch가 도는 동안 Esc나 닫기 버튼으로 대기열을 떠났을 수 있다.
            // 그 상태로 아래로 내려가면 이미 떠난 큐를 다시 렌더링하거나 폴링을 되살린다.
            if (!ticket) {
                return;
            }

            if (res.status === 404) {
                // 정리는 close 핸들러가 맡는다. 여기서는 안내만 띄우고 닫는다.
                // 먼저 ticket을 비우는 건 이탈 요청을 보내지 않기 위해서다 —
                // 서버가 방금 "그런 대기 정보가 없다"고 답했으므로 지울 것도 없다.
                ticket = null;
                showEnqueueError('대기 정보가 만료되었습니다. 다시 신청해 주세요.');
                dialog.close();
                return;
            }

            if (res.status === 403) {
                // 판매 종료. 순번이 더 이상 줄지 않으므로 폴링을 멈춘다 —
                // 아래 retryLater로 흘러가면 "다시 확인하는 중…"을 영원히 반복한다.
                // 404와 같은 이유로 ticket을 먼저 비운다. 승격이 오지 않는 창이라
                // 남은 항목은 키 TTL로 통째로 사라진다.
                ticket = null;
                const body = await readJson(res);
                showEnqueueError(body?.message || '판매가 종료되었습니다. 다시 신청해 주세요.');
                dialog.close();
                return;
            }

            if (!res.ok) {
                const body = await readJson(res);
                retryLater(body?.message || '순번을 확인하지 못했습니다. 다시 확인하는 중…');
                return;
            }

            const body = await res.json();

            if (body.state === 'ADMITTED') {
                await admit();
                return;
            }

            // 접수는 됐지만 아직 대기열 등록 전이다(Kafka 경유 진입). 순번이 없으므로 숫자를
            // 그리지 않는다 — 서버가 채워 보내는 0을 그대로 표시하면 "곧 내 차례"로 읽히는데
            // 사실은 그 반대다. 등록되는 순간 다음 응답부터 WAITING으로 넘어간다.
            if (body.state === 'PENDING') {
                renderPending();
                pollInterval = body.pollAfterMillis;
                pollDelay = jitteredDelay(pollInterval, pollInterval * POLL_JITTER_RATIO);
                timer = setTimeout(poll, pollDelay);
                return;
            }

            render(body);

            // 다음 주기는 서버가 정한다. 순번이 줄면 이 값도 함께 짧아진다.
            pollInterval = body.pollAfterMillis;
            pollDelay = jitteredDelay(pollInterval, pollInterval * POLL_JITTER_RATIO);
            timer = setTimeout(poll, pollDelay);
        } finally {
            inFlight = false;
        }
    }

    /*
     * 서버가 나를 활성으로 올렸다. 입장권에는 짧은 유효시간(admission-grace)만 붙어 있어서,
     * 그 안에 확정하지 않으면 자리가 회수된다.
     *
     * 확정 응답의 쿠키가 대기 토큰을 대신한다. 대기 토큰은 이 모듈의 변수에만 살아서
     * 페이지를 옮기는 순간 사라지지만, 쿠키는 따라간다.
     */
    async function admit() {
        if (!ticket) {
            return;
        }

        clearTimeout(timer);
        timer = null;
        status.textContent = '입장했습니다. 이동 중…';

        const url = '/api/v1/waiting-queue/' + encodeURIComponent(ticket.token)
            + '/admission?windowId=' + encodeURIComponent(ticket.windowId);

        let res;
        try {
            res = await fetch(url, {
                method: 'POST',
                signal: AbortSignal.timeout(CLAIM_TIMEOUT_MS),
            });
        } catch (e) {
            // 확정만 실패했을 뿐 아직 활성일 수 있다. 폴링으로 돌아가면 다음 응답에서 다시 시도한다.
            retryLater('입장 처리에 실패했습니다. 다시 시도하는 중…');
            return;
        }

        if (!res.ok) {
            // 대개 grace가 지나 자리가 회수된 경우다.
            // 정리는 다른 경로와 똑같이 close 핸들러 한 곳에 맡긴다.
            // 이탈 요청은 보내지 않는다 — 이미 승격돼 ZPOPMIN으로 빠졌으므로 대기열에 없다.
            ticket = null;
            showEnqueueError('입장 가능 시간이 지났습니다. 다시 신청해 주세요.');
            dialog.close();
            return;
        }

        // 여기서 dialog.close()를 부르면 안 된다. close 핸들러가 leaveQueue를 돌린다.
        // 어차피 페이지를 통째로 떠나므로 팝업을 띄운 채로 넘어간다.
        //
        // 이 이동도 pagehide를 띄운다. 플래그를 세우지 않으면 그 핸들러가 이탈 요청을 보내
        // 방금 받은 자리를 스스로 반납한다.
        admitted = true;
        window.location.href = ADMITTED_URL;
    }

    // 진행 바는 CSS 애니메이션이라 여기서 손대지 않는다.
    function render(body) {
        slots.position.textContent = format(body.position);
        slots.behind.textContent = format(body.behind);
        status.textContent = '';
    }

    function resetFigures() {
        slots.position.textContent = '-';
        slots.behind.textContent = '-';
        status.textContent = '';
    }

    // 순번을 아직 모르는 상태. 진입 직후 화면과 같은 모양이라 숫자 처리는 resetFigures에 맡기고,
    // 안내만 덧붙인다 — "확인 중"이 아니라 "접수됐다"고 알려야 사용자가 다시 신청하지 않는다.
    function renderPending() {
        resetFigures();
        status.textContent = '접수됐습니다. 순번을 배정하는 중…';
    }

    // 조회 실패로 폴링을 멈추면 순번이 굳은 채로 남는다.
    // 간격만 늘려 재시도하고, 성공하면 원래 주기로 돌아간다.
    //
    // 장애(예: Redis 순단)는 보통 다수 클라이언트에 동시에 영향을 준다.
    // 배수 증가만 있으면 모두 같은 시점에 실패해 같은 배수로 물러나므로,
    // 복구된 순간 재시도가 다시 한꺼번에 몰린다. 상한(capped)은 그대로 배수로 늘리되,
    // 실제 대기 시간은 "절반 고정 + 절반 무작위"로 흩어 재시도가 몰리는 걸 막는다.
    function retryLater(message) {
        status.textContent = message;
        // 첫 폴링은 지터로 인해 pollDelay가 아주 작을 수 있다(0~주기).
        // 거기서 바로 실패하면 배수 증가의 기준값이 작아 백오프가 사실상 없는 셈이 되므로,
        // 최소 기준을 서버가 알려 준 주기로 둔다.
        //
        // 상한도 주기를 바닥으로 깐다. POLL_BACKOFF_MAX_MS 하나로 자르면, 서버가 30초를 준
        // 뒤쪽 사람이 실패하는 순간 20초로 **짧아진다** — 조용히 하려던 바로 그 인원이,
        // 하필 서버가 아플 때 요청을 더 보내게 된다.
        const base = Math.max(pollDelay, pollInterval);
        const capped = Math.min(base * 2, Math.max(POLL_BACKOFF_MAX_MS, base));
        pollDelay = capped;
        const delay = capped / 2 + Math.random() * (capped / 2);
        timer = setTimeout(poll, delay);
    }

    function showEnqueueError(message) {
        enqueueError.textContent = message;
        enqueueError.hidden = false;
    }

    function hideEnqueueError() {
        enqueueError.textContent = '';
        enqueueError.hidden = true;
    }

    async function readJson(res) {
        try {
            return await res.json();
        } catch (e) {
            return null;
        }
    }

    function format(n) {
        return Number(n).toLocaleString('ko-KR');
    }

    // base ± spread 균등분포. 폴링 위상을 계속 흔들어 재동기화를 막는다.
    function jitteredDelay(base, spread) {
        return base + (Math.random() * 2 - 1) * spread;
    }
})();
