/*
 * 아이디 기억하기
 *
 * waiting.js·reservation.js와 같은 규약 — 외부 라이브러리·CDN 없이 IIFE 안에 가둔다.
 *
 * 저장은 서버가 아니라 브라우저 localStorage에 한다. 아이디는 비밀이 아니고, 이렇게 두면
 * 로그인 경로(게이트 화이트리스트·요청당 비용)에 아무것도 얹지 않는다.
 */
(function () {
    'use strict';

    const KEY = 'rememberedLoginId';

    const form = document.querySelector('.auth-form');
    const loginId = document.getElementById('loginId');
    const remember = document.getElementById('rememberId');
    if (!form || !loginId || !remember) {
        return;
    }

    const saved = localStorage.getItem(KEY);
    if (saved) {
        remember.checked = true;
        // 서버가 로그인 실패로 이미 아이디를 채웠으면 그 값을 존중한다 — 덮지 않는다.
        if (!loginId.value) {
            loginId.value = saved;
            // 아이디가 채워졌으니 커서를 비밀번호로 옮겨 준다.
            document.getElementById('password')?.focus();
        }
    }

    form.addEventListener('submit', () => {
        if (remember.checked) {
            localStorage.setItem(KEY, loginId.value.trim());
        } else {
            localStorage.removeItem(KEY);
        }
    });
})();
