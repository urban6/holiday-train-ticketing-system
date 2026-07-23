package com.urban6.waiting.auth;

import com.urban6.waiting.queue.WaitingQueueController;
import org.springframework.http.ResponseCookie;

/**
 * 쿠키를 지우는 헤더를 만든다.
 *
 * <p>브라우저는 "이 쿠키를 지워라"라는 명령을 따로 받지 않는다. 같은 이름·경로로
 * {@code Max-Age=0}인 쿠키를 다시 내려보내는 것이 유일한 방법이다.
 * 그래서 심을 때 쓴 속성(path 등)이 여기서도 같아야 한다 — 다르면 지워지지 않고 하나 더 생긴다.
 */
final class ExpiredCookies {

    private ExpiredCookies() {}

    static String pass() {
        return ResponseCookie.from(WaitingQueueController.PASS_COOKIE, "")
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0)
                .build()
                .toString();
    }
}
