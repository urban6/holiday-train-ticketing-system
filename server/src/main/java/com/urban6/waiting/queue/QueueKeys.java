package com.urban6.waiting.queue;

import java.util.regex.Pattern;

/**
 * Redis 키를 조립하는 유일한 곳. 키 리터럴이 다른 곳에 새어 나가면 안 된다.
 *
 * <pre>
 * waiting:holiday:{windowId}       ZSet   member=uuid, score=seq
 * waiting:holiday:{windowId}:seq   String 창별 단조 증가 순번 카운터
 * active:holiday:{windowId}        ZSet   member=uuid, score=만료 epoch ms
 * poll:holiday:{windowId}          ZSet   member=uuid, score=다음 폴링 기한 epoch ms
 * </pre>
 *
 * <p>{@code holiday}는 {@link #EVENT_ID} 상수 그대로고, 변하는 부분은 {@code windowId}뿐이다.
 *
 * <p>네 키의 접두사가 달라 Redis Cluster에서는 다른 슬롯에 떨어진다. 두 키 이상을 넘기는
 * status·promote·leave·sweep이 CROSSSLOT으로 깨지므로, Cluster로 간다면 해시 태그로 묶어야 한다.
 */
public final class QueueKeys {

    private QueueKeys() {}

    public static final String EVENT_ID = "holiday";

    /** DailyWindow가 만드는 BASIC_ISO_DATE 형식(yyyyMMdd). */
    private static final Pattern WINDOW_ID = Pattern.compile("\\d{8}");

    /**
     * 예외를 던지지 않는 검사. pass 쿠키 검증은 형식이 깨졌을 때 400이 아니라 리다이렉트로
     * 돌려보내야 한다 — 우리가 심은 값이 깨졌다는 건 "입장 자격이 없다"에 가깝다.
     */
    public static boolean isValidWindowId(String windowId) {
        return windowId != null && WINDOW_ID.matcher(windowId).matches();
    }

    /** 클라이언트가 보낸 windowId는 그대로 Redis 키가 되므로 쓰기 전에 검사한다. */
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

    /**
     * 대기자가 다음 조회를 보내야 하는 기한. member=uuid, score=epoch ms.
     *
     * <p>score가 과거가 아니라 미래를 가리킨다. 판정 기준이 사람마다 다른데 "마지막으로 본 시각"을
     * 저장하면 스위퍼의 ZRANGEBYSCORE가 사람별 임계값을 알 방법이 없다(ZSet은 join이 안 된다).
     * 주기를 미리 더해 구워 두면 스위퍼의 임계값이 지금 시각 하나로 끝난다.
     *
     * <p>waiting과 따로 두는 이유는 waiting의 score가 이미 seq라 기한으로 바꿀 수 없기 때문이다.
     */
    public static String pollDeadline(String windowId) {
        return "poll:%s:%s".formatted(EVENT_ID, windowId);
    }
}
