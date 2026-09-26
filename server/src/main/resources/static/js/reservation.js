/*
 * 예약 가능 시간 카운트다운. 안내용이고 실제 판정은 서버(AdmissionGuard)가 한다.
 */

(function () {
    'use strict';

    const WARN_THRESHOLD_MS = 30_000;

    const TICK_MS = 250;

    const EXPIRED_URL = '/?reason=reservation-expired';

    const timer = document.getElementById('reservation-timer');
    const clock = document.getElementById('reservation-clock');

    const remaining = Number(timer?.dataset.remaining);
    if (!timer || !clock || !Number.isFinite(remaining)) {
        return;
    }

    // 틱마다 빼 나가지 않고 끝나는 시각과의 차이를 다시 잰다. 드리프트와 비활성 탭 지연이 누적되지 않는다.
    const endsAt = Date.now() + remaining;

    let ticking = null;
    let done = false;

    render();
    ticking = setInterval(render, TICK_MS);

    // 비활성 탭에서 돌아오면 바로 다시 그린다.
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') {
            render();
        }
    });

    function render() {
        if (done) {
            return;
        }

        const left = endsAt - Date.now();
        if (left <= 0) {
            expire();
            return;
        }

        clock.textContent = format(left);
        timer.classList.toggle('deadline--warn', left <= WARN_THRESHOLD_MS);
    }

    function expire() {
        done = true;
        clearInterval(ticking);

        clock.textContent = format(0);
        timer.classList.add('deadline--warn');

        // 이동하기까지의 짧은 사이에 마지막 클릭이 들어가는 걸 막는다.
        document.querySelectorAll('main button, main input, main select, main textarea')
            .forEach((el) => { el.disabled = true; });

        // 뒤로가기로 만료된 화면에 돌아오지 않게 replace를 쓴다.
        window.location.replace(EXPIRED_URL);
    }

    // 올림이다. 남은 0.4초를 "0:00"으로 보이지 않게 한다.
    function format(millis) {
        const total = Math.ceil(millis / 1000);
        const minutes = Math.floor(total / 60);
        const seconds = total % 60;
        return minutes + ':' + String(seconds).padStart(2, '0');
    }
})();

// 조회 후 예약 / 예약내역 탭 전환
(function () {
    'use strict';

    const tabs = document.querySelectorAll('.rsv-tab');
    if (tabs.length === 0) {
        return;
    }

    tabs.forEach((tab) => {
        tab.addEventListener('click', () => {
            tabs.forEach((t) => {
                t.classList.remove('rsv-tab--active');
                t.setAttribute('aria-selected', 'false');
            });
            document.querySelectorAll('.rsv-panel').forEach((p) => { p.hidden = true; });

            tab.classList.add('rsv-tab--active');
            tab.setAttribute('aria-selected', 'true');
            const panel = document.getElementById(tab.getAttribute('aria-controls'));
            if (panel) {
                panel.hidden = false;
            }
        });
    });
})();

// 출발역 ↔ 도착역 스왑
(function () {
    'use strict';

    const swap = document.getElementById('rsv-swap');
    const origin = document.getElementById('origin');
    const destination = document.getElementById('destination');
    if (!swap || !origin || !destination) {
        return;
    }

    swap.addEventListener('click', () => {
        const tmp = origin.value;
        origin.value = destination.value;
        destination.value = tmp;
    });
})();
