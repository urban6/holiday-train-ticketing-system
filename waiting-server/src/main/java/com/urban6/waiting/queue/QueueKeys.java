package com.urban6.waiting.queue;

import java.util.regex.Pattern;

/**
 * Redis 키를 조립하는 유일한 곳. 키 리터럴이 다른 곳에 새어 나가면 안 된다.
 *
 * <pre>
 * waiting:holiday:{windowId}       ZSet   member=uuid, score=seq
 * waiting:holiday:{windowId}:seq   String 창별 단조 증가 순번 카운터
 * active:holiday:{windowId}        ZSet   member=uuid, score=만료 epoch ms
 * seen:holiday:{windowId}          ZSet   member=uuid, score=마지막 확인 epoch ms
 * </pre>
 *
 * <p>{@code holiday}는 플레이스홀더가 아니라 {@link #EVENT_ID} 상수 그대로다.
 * 변하는 부분은 {@code windowId} 하나뿐이다.
 *
 * <p>네 키의 접두사가 서로 달라 Redis Cluster에서는 다른 슬롯에 떨어진다.
 * 두 키 이상을 함께 넘기는 status·promote·leave·sweep 스크립트가 CROSSSLOT으로 깨지므로,
 * 언젠가 Cluster로 간다면 해시 태그로 묶어야 한다. 단일 노드에서는 문제되지 않는다.
 */
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

    /**
     * 대기자가 마지막으로 순번을 확인한 시각. member=uuid, score=epoch ms.
     *
     * <p>waiting과 따로 두는 이유는 waiting의 score가 이미 seq(순번)이기 때문이다.
     * ZRANK가 순번의 근거라 score를 만료시각으로 바꿀 수 없어서, active처럼
     * "score 하나가 곧 만료"로 만들지 못한다.
     */
    public static String seen(String windowId) {
        return "seen:%s:%s".formatted(EVENT_ID, windowId);
    }
}
