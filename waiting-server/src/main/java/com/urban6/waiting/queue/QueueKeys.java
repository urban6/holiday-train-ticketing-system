package com.urban6.waiting.queue;

import java.util.regex.Pattern;

/**
 * Redis 키를 조립하는 유일한 곳. 키 리터럴이 다른 곳에 새어 나가면 안 된다.
 *
 * <pre>
 * waiting:holiday:{windowId}:{shard}       ZSet   member=uuid, score=seq
 * waiting:holiday:{windowId}:{shard}:seq   String 샤드별 단조 증가 순번 카운터
 * active:holiday:{windowId}:{shard}        ZSet   member=uuid, score=만료 epoch ms
 * poll:holiday:{windowId}:{shard}          ZSet   member=uuid, score=다음 폴링 기한 epoch ms
 * </pre>
 *
 * <p>{@code holiday}는 플레이스홀더가 아니라 {@link #EVENT_ID} 상수 그대로다.
 * 변하는 부분은 {@code windowId}와 {@code shard}뿐이다.
 *
 * <p><b>한 샤드의 네 키는 반드시 같은 인스턴스에 있어야 한다.</b> promote는 waiting에서 꺼내
 * active에 넣는 것을 한 스크립트로 묶어 정원을 지키는데, 두 키가 다른 인스턴스면 그 원자성이
 * 사라진다. 그러면 승격 도중 waiting에도 active에도 없는 순간이 생겨, 하필 그때 조회한
 * 사용자가 "대기 정보를 찾을 수 없습니다"를 받는다.
 *
 * <p>네 키의 접두사가 서로 달라 Redis Cluster에서는 다른 슬롯에 떨어진다.
 * 두 키 이상을 함께 넘기는 status·promote·leave·sweep 스크립트가 CROSSSLOT으로 깨지므로,
 * 언젠가 Cluster로 간다면 해시 태그로 묶어야 한다. 지금은 Cluster가 아니라 클라이언트가
 * 직접 샤드를 고르는 방식이라({@link #shardOf}) 이 문제를 만나지 않는다.
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

    /**
     * 이 토큰이 속한 샤드. 토큰 하나로 정해지므로 조회·이탈·입장 확정이 언제나 같은 샤드로 돌아온다.
     *
     * <p>상태를 두지 않는 것이 요점이다. 진입할 때 고른 샤드를 어딘가 기억해 두면 그 저장소가
     * 대기열보다 먼저 병목이 되고, 토큰에 샤드 번호를 붙이면 pass 쿠키 형식까지 따라 바뀐다.
     *
     * <p>해시가 고르게 흩어지는 것이 순번 근사의 전제다 — 전역 순번을
     * {@code 샤드 안 순번 x 샤드 수}로 계산하므로, 샤드 깊이가 갈리면 그만큼 순번이 틀린다.
     * UUID 100만 개에서 샤드 크기 이탈이 0.33%였고, 그때 순번 오차가 최대 0.33%였다
     * (k6/shard-probe.sh).
     *
     * <p>{@code hashCode()}는 음수가 될 수 있어 {@code %}가 아니라 {@code floorMod}다.
     * {@code %}로 두면 음수 샤드 번호가 나와 목록 인덱싱에서 터진다.
     */
    public static int shardOf(String token, int shardCount) {
        return Math.floorMod(token.hashCode(), shardCount);
    }

    public static String waiting(String windowId, int shard) {
        return "waiting:%s:%s:%d".formatted(EVENT_ID, windowId, shard);
    }

    public static String seq(String windowId, int shard) {
        return "waiting:%s:%s:%d:seq".formatted(EVENT_ID, windowId, shard);
    }

    /** 입장한 사용자. member=uuid, score=만료 epoch ms. */
    public static String active(String windowId, int shard) {
        return "active:%s:%s:%d".formatted(EVENT_ID, windowId, shard);
    }

    /**
     * 대기자가 다음 조회를 보내야 하는 기한. member=uuid, score=epoch ms.
     *
     * <p>score가 과거가 아니라 미래를 가리킨다. 서버가 사람마다 다른 폴링 주기를 알려 주므로
     * 이탈 판정 기준도 사람마다 달라야 하는데, "마지막으로 본 시각"을 저장하면 스위퍼의
     * ZRANGEBYSCORE가 사람별 임계값을 알 방법이 없다 — ZSet은 join이 안 된다.
     * 알려 준 주기를 여기서 미리 더해 구워 두면 스위퍼의 임계값이 지금 시각 하나로 끝난다.
     *
     * <p>waiting과 따로 두는 이유는 waiting의 score가 이미 seq(순번)이기 때문이다.
     * ZRANK가 순번의 근거라 그 score를 기한으로 바꿀 수 없어서, active처럼
     * "score 하나가 곧 만료"인 키를 하나 더 둔다.
     */
    public static String pollDeadline(String windowId, int shard) {
        return "poll:%s:%s:%d".formatted(EVENT_ID, windowId, shard);
    }
}
