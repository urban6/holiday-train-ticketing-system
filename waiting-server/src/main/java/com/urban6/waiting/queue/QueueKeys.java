package com.urban6.waiting.queue;

import java.util.regex.Pattern;

public final class QueueKeys {

    private QueueKeys() {}

    public static final String EVENT_ID = "holiday";

    /** DailyWindow가 만드는 BASIC_ISO_DATE 형식(yyyyMMdd). */
    private static final Pattern WINDOW_ID = Pattern.compile("\\d{8}");

    /**
     * 클라이언트가 보낸 windowId는 그대로 Redis 키가 되므로,
     * 키에 쓰기 전에 형식을 검사한다.
     */
    public static String requireValidWindowId(String windowId) {
        if (windowId == null || !WINDOW_ID.matcher(windowId).matches()) {
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
