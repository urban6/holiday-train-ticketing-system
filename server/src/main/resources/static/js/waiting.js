/*
 * 대기열 진입과 순번 폴링
 *
 * 토큰은 이 모듈의 변수에만 둔다. "새로고침하면 자리를 잃는다"가 규칙이라 sessionStorage에
 * 넣지 않고, 같은 이유로 대기 화면은 별도 페이지가 아니라 랜딩 위의 <dialog>다.
 */

(function () {
    'use strict';

    // 폴링 주기는 서버가 응답(pollAfterMillis)으로 정한다. 여기서는 실패 시 백오프만 정한다.
    const POLL_BACKOFF_MAX_MS = 20000;

    // 정각에 동시에 진입한 사용자들의 폴링이 같은 위상으로 몰리지 않게 흩는다.
    // 서버의 poll-grace가 이 폭(+25%)을 덮고 있으므로 키우려면 그쪽도 같이 본다.
    const POLL_JITTER_RATIO = 0.25;

    // 서버가 매달려도 버튼이 잠긴 채 남지 않게 클라이언트가 먼저 끊는다.
    const ENQUEUE_TIMEOUT_MS = 10000;
    const STATUS_TIMEOUT_MS = 5000;
    const CLAIM_TIMEOUT_MS = 5000;

    const ADMITTED_URL = '/login';

    // {token, date}
    let ticket = null;
    let timer = null;

    // 서버가 알려 준 주기와 실제 예약한 지연(지터·백오프 포함).
    // 서버는 pollInterval + poll-grace를 이탈 기한으로 보므로 그보다 오래 쉬면 줄에서 빠진다.
    let pollInterval = 0;
    let pollDelay = 0;

    let lastPollAt = 0;

    // 입장 후 이동도 pagehide를 띄운다. 이 플래그가 없으면 방금 받은 자리에 이탈 요청을 보낸다.
    let admitted = false;

    // visibilitychange가 도는 중인 poll()과 겹치면 admit()이 두 번 불릴 수 있다.
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

    // 정리는 close 이벤트 한 곳에서 한다. Esc로 닫는 경로가 따로 있기 때문이다.
    closeButton.addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', leaveQueue);

    // 비활성 탭은 타이머가 크게 늦춰져 admission-grace를 놓치기 쉬우므로 돌아오면 바로 폴링한다.
    // 단 주기가 남았으면 남은 만큼만 기다린다. 탭 전환마다 즉시 물으면 주기가 무의미해진다.
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
     * 탭 닫기·새로고침·페이지 이동. beforeunload는 모바일에서 자주 발화하지 않고 bfcache를 깨서
     * pagehide를 쓴다. visibilitychange의 hidden은 탭 전환에도 떠서 걸면 안 된다.
     */
    window.addEventListener('pagehide', () => {
        if (ticket && !admitted) {
            sendLeave(ticket);
        }
    });

    function leaveQueue() {
        clearTimeout(timer);
        timer = null;

        // 404·403·입장 실패 경로는 이미 서버에 없는 토큰이라 close() 전에 ticket을 비워 이탈 요청을 건너뛴다.
        if (ticket) {
            sendLeave(ticket);
        }

        ticket = null;
        button.disabled = false;
    }

    // 일반 fetch는 페이지가 사라지면서 취소되므로 sendBeacon을 쓴다. 그래서 서버 API도 POST다.
    function sendLeave(t) {
        const url = '/api/v1/waiting-queue/' + encodeURIComponent(t.token)
            + '/leave?date=' + encodeURIComponent(t.date);
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
        ticket = { token: body.token, date: body.date };

        resetFigures();
        dialog.showModal();

        pollInterval = body.pollAfterMillis;

        // 첫 조회는 0~주기 구간에서 무작위로 골라 위상을 흩는다.
        pollDelay = Math.random() * pollInterval;
        timer = setTimeout(poll, pollDelay);
    }

    // setInterval이 아니라 setTimeout 체인이다. 응답이 느려질 때 요청이 겹쳐 쌓이지 않는다.
    async function poll() {
        if (!ticket || inFlight) {
            return;
        }
        inFlight = true;
        lastPollAt = Date.now();

        try {
            const url = '/api/v1/waiting-queue/' + encodeURIComponent(ticket.token)
                + '?date=' + encodeURIComponent(ticket.date);

            let res;
            try {
                res = await fetch(url, { signal: AbortSignal.timeout(STATUS_TIMEOUT_MS) });
            } catch (e) {
                retryLater('연결이 불안정합니다. 다시 확인하는 중…');
                return;
            }

            // fetch 중에 대기열을 떠났을 수 있다.
            if (!ticket) {
                return;
            }

            if (res.status === 404) {
                ticket = null;
                showEnqueueError('대기 정보가 만료되었습니다. 다시 신청해 주세요.');
                dialog.close();
                return;
            }

            if (res.status === 403) {
                // 판매 종료. retryLater로 흘러가면 재시도를 영원히 반복한다.
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

            render(body);

            pollInterval = body.pollAfterMillis;
            pollDelay = jitteredDelay(pollInterval, pollInterval * POLL_JITTER_RATIO);
            timer = setTimeout(poll, pollDelay);
        } finally {
            inFlight = false;
        }
    }

    // admission-grace 안에 확정해야 한다. 확정 응답의 쿠키가 페이지 이동 뒤 토큰을 대신한다.
    async function admit() {
        if (!ticket) {
            return;
        }

        clearTimeout(timer);
        timer = null;
        status.textContent = '입장했습니다. 이동 중…';

        const url = '/api/v1/waiting-queue/' + encodeURIComponent(ticket.token)
            + '/admission?date=' + encodeURIComponent(ticket.date);

        let res;
        try {
            res = await fetch(url, {
                method: 'POST',
                signal: AbortSignal.timeout(CLAIM_TIMEOUT_MS),
            });
        } catch (e) {
            // 아직 활성일 수 있으니 폴링으로 돌아가 다음 응답에서 다시 시도한다.
            retryLater('입장 처리에 실패했습니다. 다시 시도하는 중…');
            return;
        }

        if (!res.ok) {
            // 대개 grace가 지나 회수된 경우다. 이미 대기열에 없으니 이탈 요청은 보내지 않는다.
            ticket = null;
            showEnqueueError('입장 가능 시간이 지났습니다. 다시 신청해 주세요.');
            dialog.close();
            return;
        }

        // dialog.close()를 부르면 close 핸들러가 이탈 요청을 보낸다. 팝업을 띄운 채 떠난다.
        admitted = true;
        window.location.href = ADMITTED_URL;
    }

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

    // 지수 백오프에 절반 무작위 지터를 섞어 장애 복구 순간 재시도가 몰리지 않게 한다.
    function retryLater(message) {
        status.textContent = message;
        // 기준과 상한 모두 서버가 준 주기보다 짧아지지 않게 한다. 30초를 받은 사람이
        // 실패하는 순간 20초로 줄면 서버가 아플 때 오히려 요청이 늘어난다.
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

    // base ± spread 균등분포
    function jitteredDelay(base, spread) {
        return base + (Math.random() * 2 - 1) * spread;
    }
})();
