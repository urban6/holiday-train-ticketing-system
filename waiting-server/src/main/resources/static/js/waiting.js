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

    const POLL_INTERVAL_MS = 2000;
    const POLL_BACKOFF_MAX_MS = 8000;

    // Redis가 죽으면 서버는 에러를 주는 게 아니라 그냥 매달린다(기본 타임아웃 60초).
    // 클라이언트가 먼저 끊지 않으면 버튼이 잠긴 채 아무 안내도 못 준다.
    const ENQUEUE_TIMEOUT_MS = 10000;
    const STATUS_TIMEOUT_MS = 5000;
    const CLAIM_TIMEOUT_MS = 5000;

    // 입장 확정 후 이동할 곳.
    const ADMITTED_URL = '/login';

    // {token, windowId}. 메모리에만 산다.
    let ticket = null;
    let timer = null;
    let pollDelay = POLL_INTERVAL_MS;

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
    // 다음 예약된 타이머를 기다리는 것보다 빠르다. 입장권 grace(60초)를 놓치는 가장 흔한
    // 원인이 이 지연이라 탭이 보이는 즉시 폴링을 재개한다.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && ticket && !inFlight) {
            clearTimeout(timer);
            timer = null;
            pollDelay = POLL_INTERVAL_MS;
            poll();
        }
    });

    function leaveQueue() {
        clearTimeout(timer);
        timer = null;
        ticket = null;          // 자리를 잃는다. 서버에서 빼는 수단은 아직 없다.
        button.disabled = false;
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

        pollDelay = POLL_INTERVAL_MS;
        poll();
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
                showEnqueueError('대기 정보가 만료되었습니다. 다시 신청해 주세요.');
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

            render(body);

            pollDelay = POLL_INTERVAL_MS;
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
            showEnqueueError('입장 가능 시간이 지났습니다. 다시 신청해 주세요.');
            dialog.close();
            return;
        }

        // 여기서 dialog.close()를 부르면 안 된다. close 핸들러가 leaveQueue를 돌린다.
        // 어차피 페이지를 통째로 떠나므로 팝업을 띄운 채로 넘어간다.
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

    // 조회 실패로 폴링을 멈추면 순번이 굳은 채로 남는다.
    // 간격만 늘려 재시도하고, 성공하면 원래 주기로 돌아간다.
    function retryLater(message) {
        status.textContent = message;
        pollDelay = Math.min(pollDelay * 2, POLL_BACKOFF_MAX_MS);
        timer = setTimeout(poll, pollDelay);
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
})();
