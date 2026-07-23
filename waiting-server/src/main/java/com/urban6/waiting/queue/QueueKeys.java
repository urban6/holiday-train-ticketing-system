package com.urban6.waiting.queue;

import java.util.regex.Pattern;

public final class QueueKeys {

    private QueueKeys() {}

    public static final String EVENT_ID = "holiday";

    /** DailyWindow가 만드는 BASIC_ISO_DATE 형식(yyyyMMdd). */
    private static final Pattern WINDOW_ID = Pattern.compile("\\d{8}");

    /**
     * 예외를 던지지 않는 검사.
     *
     * <p>페이지 요청(pass 쿠키 검증)은 형식이 깨졌을 때 400 JSON이 아니라 리다이렉트로
     * 돌려보내야 한다. 쿠키는 사용자가 직접 넣은 입력이 아니라 우리가 심은 값이므로,
     * 형식이 깨졌다는 건 "잘못 요청했다"가 아니라 "입장 자격이 없다"에 가깝다.
     */
    public static boolean isValidWindowId(String windowId) {
        return windowId != null && WINDOW_ID.matcher(windowId).matches();
    }

    /**
     * 클라이언트가 보낸 windowId는 그대로 Redis 키가 되므로,
     * 키에 쓰기 전에 형식을 검사한다.
     */
    public static String requireValidWindowId(String windowId) {
        if (!isValidWindowId(windowId)) {
            throw new QueueException.InvalidRequest("windowId 형식이 올바르지 않습니다.");
        }
        return windowId;
    }

    public static String waiting(String windowId) {
        return "waiting:%s:%s".formatted(EVENT_ID, windowId);
    }

    public static String seq(String windowId) {
        return "waiting:%s:%s:seq".formatted(EVENT_ID, windowId);
    }

    /** 입장한 사용자. member=uuid, score=만료 epoch ms. */
    public static String active(String windowId) {
        return "active:%s:%s".formatted(EVENT_ID, windowId);
    }
}
