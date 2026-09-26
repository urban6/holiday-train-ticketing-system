package com.urban6.waiting.queue;

import java.util.regex.Pattern;

/**
 * Redis 키를 조립하는 유일한 곳.
 *
 * <pre>
 * waiting:{date}:{shard}       ZSet   member=uuid, score=seq
 * waiting:{date}:{shard}:seq   String 샤드별 단조 증가 순번 카운터
 * active:{date}:{shard}        ZSet   member=uuid, score=만료 epoch ms
 * poll:{date}:{shard}          ZSet   member=uuid, score=다음 폴링 기한 epoch ms
 * </pre>
 *
 * <p>{@code date}를 키에서 빼면 안 된다. 키의 유예가 자정을 넘어 다음 창과 섞이고,
 * enqueue.lua의 TTL 조건이 거짓이 되어 오늘 대기열이 어제 만료 시각에 사라진다.
 *
 * <p>{@code {shard}}는 해시 태그다. 한 샤드의 네 키가 같은 노드에 있어야 스크립트가
 * CROSSSLOT 없이 원자적으로 돈다.
 */
public final class QueueKeys {

    private QueueKeys() {}

    /**
     * 샤드 번호 → 해시 태그 문자. {@code "0","1","2"}는 3-마스터 클러스터에서 두 개가 같은 마스터에
     * 몰려 {@code CLUSTER KEYSLOT}으로 마스터마다 하나씩 떨어지는 문자를 골랐다.
     * 순서를 바꾸거나 원소를 빼면 안 되고, 늘릴 때도 같은 방법으로 확인한다
     * (docs/redis-cluster-bootstrap.md).
     */
    private static final String[] SHARD_TAGS = {"a", "b", "c"};

    public static int maxShardCount() {
        return SHARD_TAGS.length;
    }

    /** DailyWindow가 만드는 BASIC_ISO_DATE 형식(yyyyMMdd). */
    private static final Pattern DATE = Pattern.compile("\\d{8}");

    /** pass 쿠키 검증은 형식이 깨졌을 때 400이 아니라 리다이렉트해야 해서 예외를 던지지 않는다. */
    public static boolean isValidDate(String date) {
        return date != null && DATE.matcher(date).matches();
    }

    /** 클라이언트가 보낸 date는 그대로 Redis 키가 되므로 쓰기 전에 검사한다. */
    public static String requireValidDate(String date) {
        if (!isValidDate(date)) {
            throw new QueueException.InvalidRequest("date 형식이 올바르지 않습니다.");
        }
        return date;
    }

    /** 토큰만으로 샤드가 정해지므로 어느 샤드에 넣었는지 따로 기억하지 않는다. */
    public static int shardOf(String token, int shardCount) {
        return Math.floorMod(token.hashCode(), shardCount);
    }

    private static String tag(int shard) {
        if (shard < 0 || shard >= SHARD_TAGS.length) {
            throw new IllegalStateException(
                    "샤드 %d에 쓸 해시 태그가 없습니다. SHARD_TAGS를 늘리고 CLUSTER KEYSLOT으로 다시 확인하세요."
                            .formatted(shard));
        }
        return SHARD_TAGS[shard];
    }

    public static String waiting(String date, int shard) {
        return "waiting:%s:{%s}".formatted(date, tag(shard));
    }

    public static String seq(String date, int shard) {
        return "waiting:%s:{%s}:seq".formatted(date, tag(shard));
    }

    public static String active(String date, int shard) {
        return "active:%s:{%s}".formatted(date, tag(shard));
    }

    /**
     * score는 마지막 조회 시각이 아니라 다음 조회 기한이다. 사람마다 주기가 달라서,
     * 기한을 미리 구워 둬야 스위퍼가 현재 시각 하나로 판정할 수 있다.
     */
    public static String pollDeadline(String date, int shard) {
        return "poll:%s:{%s}".formatted(date, tag(shard));
    }
}
