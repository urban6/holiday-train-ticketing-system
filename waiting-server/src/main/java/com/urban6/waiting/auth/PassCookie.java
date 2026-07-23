package com.urban6.waiting.auth;

import com.urban6.waiting.queue.QueueKeys;
import com.urban6.waiting.queue.WaitingQueueController;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Optional;

/**
 * 입장 확정 시 심은 {@code pass} 쿠키를 읽는다.
 *
 * <p>값의 형식은 {@code "{windowId}.{token}"}으로,
 * {@link WaitingQueueController#PASS_COOKIE}가 만드는 것과 같은 규약이다.
 */
public final class PassCookie {

    private PassCookie() {}

    /**
     * 쿠키가 없거나, 형식이 깨졌거나, windowId가 날짜 형식이 아니면 비어 있다.
     *
     * <p>여기서는 "Redis에 살아 있는가"를 보지 않는다. 그건 {@link AdmissionGuard}의 몫이다.
     * 이 클래스는 <b>키로 쓸 수 있는 값인지</b>까지만 책임진다 —
     * windowId가 그대로 Redis 키가 되므로 검사 없이 넘기면 안 된다.
     */
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

        // token은 UUID라 '.'을 담지 않는다. 그래도 limit 2로 자르는 편이,
        // 값이 예상과 달라졌을 때 조용히 엉뚱한 조각을 집는 것보다 낫다.
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2 || !QueueKeys.isValidWindowId(parts[0]) || parts[1].isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Pass(parts[0], parts[1]));
    }

    public record Pass(String windowId, String token) {}
}
