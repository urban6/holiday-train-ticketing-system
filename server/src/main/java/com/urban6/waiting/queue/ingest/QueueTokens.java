package com.urban6.waiting.queue.ingest;

import java.util.OptionalLong;
import java.util.UUID;

/**
 * 대기 토큰을 만들고 읽는다.
 *
 * <p>직접 진입은 uuid 하나다. Kafka 경유만 뒤에 접수 시각을 붙인다 — 접수(202)와 등록 사이의
 * 공백 동안 어느 ZSet에도 없어 {@code status.lua}가 "아직 등록 전"과 "만료"를 구별할 수 없다.
 *
 * <p>시각을 토큰이 들고 다니는 이유는 Redis를 한 번도 더 읽지 않기 위해서다. 따로 적어 두면
 * 유입률에 상한을 걸려고 Kafka를 둔 목적과 어긋난다.
 *
 * <p>구분자 {@code _}는 uuid에도 36진수 시각에도 없어 뒤에서 한 번 자르면 애매할 데가 없다.
 * pass 쿠키는 첫 {@code .}으로만 쪼개므로({@link com.urban6.waiting.auth.PassCookie}) 무관하다.
 */
final class QueueTokens {

    private static final char STAMP = '_';

    /** 36진수. epoch ms를 10진수로 적으면 13자인데 36진수면 8자다 — ZSet 멤버 크기가 곧 메모리다. */
    private static final int RADIX = 36;

    private QueueTokens() {
    }

    static String issue() {
        return UUID.randomUUID().toString();
    }

    /** 접수 시각을 붙인 토큰. Kafka 경유 진입만 쓴다. */
    static String stamped(String uuid, long acceptedAtMillis) {
        return uuid + STAMP + Long.toString(acceptedAtMillis, RADIX);
    }

    /**
     * 접수된 지 {@code windowMillis}를 넘지 않았는가. 시각이 없는 토큰은 false다.
     *
     * <p>컨슈머의 폐기 판정과 조회의 "아직 등록 전" 판정이 같은 값을 봐야 한다. 갈리면 컨슈머는
     * 이미 버렸는데 화면은 계속 기다리거나, 화면이 포기한 뒤에 등록된다.
     */
    static boolean acceptedWithin(String token, long nowMillis, long windowMillis) {
        OptionalLong acceptedAt = acceptedAt(token);
        return acceptedAt.isPresent() && nowMillis - acceptedAt.getAsLong() <= windowMillis;
    }

    /**
     * 토큰에 실린 접수 시각. 클라이언트가 돌려보내는 값이라 형식을 믿지 않는다 —
     * 깨진 값은 비어 있는 것과 같이 다루면 어차피 만료로 판정된다.
     */
    private static OptionalLong acceptedAt(String token) {
        int at = token.lastIndexOf(STAMP);
        if (at < 0 || at == token.length() - 1) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(token, at + 1, token.length(), RADIX));
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }
}
