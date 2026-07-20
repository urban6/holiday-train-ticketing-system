package com.urban6.waiting.queue;

public final class QueueKeys {

    private QueueKeys() {}

    public static final String EVENT_ID = "holiday";

    public static String waiting(String windowId) {
        return "waiting:%s:%s".formatted(EVENT_ID, windowId);
    }

    public static String seq(String windowId) {
        return "waiting:%s:%s:seq".formatted(EVENT_ID, windowId);
    }
}
