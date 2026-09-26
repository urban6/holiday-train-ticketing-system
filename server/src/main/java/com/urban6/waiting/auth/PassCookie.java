package com.urban6.waiting.auth;

import com.urban6.waiting.queue.QueueKeys;
import com.urban6.waiting.queue.WaitingQueueController;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;

/** 입장 확정 시 심은 {@code pass} 쿠키({@code "{date}.{token}"})를 읽는다. */
public final class PassCookie {

    private PassCookie() {}

    /** Redis 키로 쓸 수 있는 형식인지까지만 본다. 살아 있는지는 {@link AdmissionGuard}가 본다. */
    public static Optional<Pass> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }

        return Arrays.stream(cookies)
                .filter(c -> WaitingQueueController.PASS_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .flatMap(value -> parse(value).stream())
                .findFirst();
    }

    private static Optional<Pass> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String[] parts = value.split("\\.", 2);
        if (parts.length != 2 || !QueueKeys.isValidDate(parts[0]) || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Pass(parts[0], parts[1]));
    }

    public record Pass(String date, String token) {}
}
