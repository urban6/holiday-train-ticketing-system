/*
 * 예약 가능 시간 카운트다운
 *
 * waiting.js와 같은 규약이다 — 외부 라이브러리·CDN을 쓰지 않고, IIFE 안에 가둔다.
 *
 * 이 타이머는 안내용이다. 실제 판정은 요청마다 서버(AdmissionGuard)가 Redis의 활성 슬롯을
 * 보고 내린다. 여기서 하는 일은 "언제 끝나는지 보여 주고, 끝나면 굳이 요청을 보내 튕기기 전에
 * 먼저 랜딩으로 옮겨 주는 것"뿐이다. 그래서 이 스크립트를 막아도 예약 시간이 늘어나지 않는다.
 */

(function () {
    'use strict';

    // 남은 시간이 이 아래로 떨어지면 강조한다.
    const WARN_THRESHOLD_MS = 30_000;

    const TICK_MS = 250;

    const EXPIRED_URL = '/?reason=reservation-expired';

    const timer = document.getElementById('reservation-timer');
    const clock = document.getElementById('reservation-clock');
    const expiredNotice = document.getElementById('reservation-expired');

    const remaining = Number(timer?.dataset.remaining);
    if (!timer || !clock || !Number.isFinite(remaining)) {
        return;
    }

    /*
     * 끝나는 시각을 한 번만 잡아 두고, 매 틱마다 "지금"과의 차이를 다시 잰다.
     * 틱마다 남은 값을 빼 나가면 setInterval의 드리프트와 비활성 탭 스로틀링이 그대로 누적된다.
     * 이 방식은 타이머가 얼마나 늦게 깨어나든 표시된 값이 항상 맞다.
     *
     * 기준이 서버가 준 '남은 길이'이므로, 클라이언트 시계가 몇 시로 맞춰져 있든 상관없다.
     * 여기서 Date.now()를 쓰는 건 절대 시각이 아니라 경과 시간을 재기 위해서다.
     */
    const endsAt = Date.now() + remaining;

    let ticking = null;
    let done = false;

    render();
    ticking = setInterval(render, TICK_MS);

    // 비활성 탭에서는 타이머가 크게 늦춰진다. 돌아온 즉시 다시 그려야
    // 이미 끝난 시간이 잠깐 남아 있는 것처럼 보이지 않는다. (waiting.js와 같은 이유)
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

        // 다음 페이즈의 좌석·결제 폼이 붙었을 때를 위한 잠금이다.
        // 이동하기까지의 짧은 사이에 마지막 클릭이 들어가는 걸 막는다.
        document.querySelectorAll('main button, main input, main select, main textarea')
            .forEach((el) => { el.disabled = true; });

        if (expiredNotice) {
            expiredNotice.hidden = false;
        }

        // assign이 아니라 replace다. 뒤로가기로 이미 만료된 이 화면에 돌아오면
        // 서버가 다시 튕겨 낼 뿐이라 사용자에게는 화면이 깜빡이는 것으로만 보인다.
        window.location.replace(EXPIRED_URL);
    }

    // 올림이다. 남은 0.4초를 "0:00"으로 보여 주면 아직 되는 예약이 안 되는 것처럼 보인다.
    function format(millis) {
        const total = Math.ceil(millis / 1000);
        const minutes = Math.floor(total / 60);
        const seconds = total % 60;
        return minutes + ':' + String(seconds).padStart(2, '0');
    }
})();

/*
 * 조회 후 예약 / 예약내역 탭 전환. 같은 화면 안에서 패널만 감췄다 보였다 한다.
 */
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

/*
 * 출발역 ↔ 도착역 스왑. 두 select의 선택값만 맞바꾼다.
 */
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
