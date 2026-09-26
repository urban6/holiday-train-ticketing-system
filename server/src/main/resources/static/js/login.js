// 아이디 기억하기. 서버가 아니라 localStorage에 둬서 로그인 경로에 비용을 얹지 않는다.
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
        // 로그인 실패로 서버가 이미 채운 값은 덮지 않는다.
        if (!loginId.value) {
            loginId.value = saved;
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
