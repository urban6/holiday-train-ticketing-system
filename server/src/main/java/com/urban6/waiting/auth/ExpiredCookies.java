package com.urban6.waiting.auth;

import com.urban6.waiting.queue.WaitingQueueController;
import org.springframework.http.ResponseCookie;

/** 쿠키를 지우는 헤더. 심을 때와 path 등 속성이 같아야 한다. 다르면 쿠키가 하나 더 생긴다. */
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
