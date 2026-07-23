/*
 * 시연용 대기열 시드 패널
 *
 * ?demo로 들어왔을 때만 로드된다. waiting.js와 같은 규칙을 따른다 —
 * 외부 라이브러리를 쓰지 않고, fetch에는 항상 타임아웃을 건다.
 *
 * 폴링은 하지 않는다. 순번이 줄어드는 모습은 대기 팝업이 보여주고,
 * 그 팝업은 모달이라 열려 있는 동안 이 패널은 어차피 보이지 않는다.
 */

(function () {
    'use strict';

    const API = '/api/v1/demo/queue';
    const TIMEOUT_MS = 30000;   // 10만 명 시드는 청크를 여러 번 도므로 넉넉히 잡는다.

    const countInput = document.getElementById('demo-count');
    const fillButton = document.getElementById('demo-fill');
    const clearButton = document.getElementById('demo-clear');
    const message = document.getElementById('demo-message');
    const slots = {
        waiting: document.getElementById('demo-waiting'),
        active: document.getElementById('demo-active'),
    };

    fillButton.addEventListener('click', fill);
    clearButton.addEventListener('click', clear);

    // 패널이 열린 시점의 상태부터 보여준다. 실패해도 조작 자체는 가능해야 하므로 조용히 넘어간다.
    call('GET').catch(() => {});

    async function fill() {
        const count = Number(countInput.value);
        if (!Number.isInteger(count) || count < 1) {
            show('인원은 1 이상의 정수여야 합니다.');
            return;
        }

        await run(() => call('POST', '?count=' + encodeURIComponent(count)),
            count.toLocaleString('ko-KR') + '명을 넣었습니다.');
    }

    async function clear() {
        await run(() => call('DELETE'), '대기열을 비웠습니다.');
    }

    /*
     * 두 버튼을 함께 잠근다. 시드가 도는 중에 비우기가 끼어들면 어느 쪽이 이겼는지에 따라
     * 결과가 달라져서, 화면에 보이는 숫자와 Redis가 어긋난 것처럼 보인다.
     */
    async function run(action, successMessage) {
        setBusy(true);
        show('처리 중…');

        try {
            await action();
            show(successMessage);
        } catch (e) {
            show(e.message);
        } finally {
            setBusy(false);
        }
    }

    async function call(method, query) {
        let res;
        try {
            res = await fetch(API + (query || ''), {
                method: method,
                signal: AbortSignal.timeout(TIMEOUT_MS),
            });
        } catch (e) {
            throw new Error('서버가 응답하지 않습니다.');
        }

        if (res.status === 404) {
            // queue.demo.enabled가 false인 채로 패널만 열린 경우. 서버를 다시 띄워야 한다.
            throw new Error('시연용 API가 꺼져 있습니다(queue.demo.enabled).');
        }

        if (!res.ok) {
            let body = null;
            try {
                body = await res.json();
            } catch (e) {
                body = null;
            }
            throw new Error((body && body.message) || '요청이 실패했습니다.');
        }

        render(await res.json());
    }

    function render(counts) {
        slots.waiting.textContent = format(counts.waiting);
        slots.active.textContent = format(counts.active);
    }

    function setBusy(busy) {
        fillButton.disabled = busy;
        clearButton.disabled = busy;
    }

    function show(text) {
        message.textContent = text;
    }

    function format(n) {
        return Number(n).toLocaleString('ko-KR');
    }
})();
