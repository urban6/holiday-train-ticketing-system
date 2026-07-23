package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class WaitingQueueRepository {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> enqueueScript;
    private final RedisScript<Long> restampScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> statusScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> promoteScript;

    /**
     * 순번을 발급하고 대기열에 등록한다.
     * 두 작업은 Lua Script로 묶어서 실행한다.
     */
    public long enqueue(String windowId, String uuid, Instant waitingDeadline, Instant seqDeadline) {
        Long seq = execute(enqueueScript, "대기열 진입", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.seq(windowId)),
                uuid,
                millis(waitingDeadline),
                millis(seqDeadline));

        if (seq == null) {
            throw new IllegalStateException("스크립트가 seq를 반환하지 않았습니다: " + windowId);
        }
        return seq;
    }

    /**
     * 대기 순번과 입장 여부를 함께 읽는다.
     * 두 값 사이에 큐가 변하면 앞뒤 합이 어긋나므로 Lua Script로 묶어서 실행한다.
     */
    @SuppressWarnings("unchecked")
    public Snapshot status(String windowId, String uuid, long nowMillis) {
        List<Long> result = execute(statusScript, "순번 조회", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.active(windowId)),
                uuid,
                String.valueOf(nowMillis));

        if (result == null || result.size() < 4) {
            throw new IllegalStateException("스크립트가 state/rank/total/expireAt을 반환하지 않았습니다: " + windowId);
        }
        return new Snapshot(result.get(0), result.get(1), result.get(2), result.get(3));
    }

    /**
     * 만료된 활성 사용자를 회수하고, 정원에 빈 만큼 대기열 앞에서 승격시킨다.
     * 정원을 세는 것과 꺼내는 것이 갈라지면 그 사이에 정원을 넘길 수 있어 한 스크립트다.
     */
    @SuppressWarnings("unchecked")
    public Promotion promote(String windowId, long nowMillis, QueueProperties properties, Instant activeDeadline) {
        List<Long> result = execute(promoteScript, "승격", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.active(windowId)),
                String.valueOf(nowMillis),
                String.valueOf(properties.capacity()),
                String.valueOf(properties.maxBatch()),
                String.valueOf(properties.admissionGrace().toMillis()),
                millis(activeDeadline));

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("스크립트가 승격 결과를 반환하지 않았습니다: " + windowId);
        }
        return new Promotion(result.get(0), result.get(1), result.get(2));
    }

    /**
     * 활성 슬롯의 만료시각을 지금부터 ttl 뒤로 다시 찍는다.
     * 입장 확정(sessionTtl)과 로그인(reservationTtl)이 같은 스크립트를 ttl만 바꿔 쓴다.
     *
     * @return 활성이 아니거나 이미 만료됐으면 false
     */
    public boolean restamp(String windowId, String uuid, long nowMillis, Duration ttl, String operation) {
        Long restamped = execute(restampScript, operation, windowId,
                List.of(QueueKeys.active(windowId)),
                uuid,
                String.valueOf(nowMillis),
                String.valueOf(ttl.toMillis()));

        return restamped != null && restamped == 1L;
    }

    /**
     * 활성 슬롯을 즉시 비운다. 로그아웃처럼 사용자가 스스로 나가는 경우다.
     *
     * <p>만료를 기다리지 않고 여기서 지우는 만큼 정원이 그대로 앞당겨 회수된다.
     * 단일 명령이라 Lua로 묶을 이유가 없다 — 원자성이 필요한 "세고 꺼내기"가 없다.
     */
    public void release(String windowId, String uuid) {
        run("입장권 반납", windowId, () -> redis.opsForZSet().remove(QueueKeys.active(windowId), uuid));
    }

    private <T> T execute(RedisScript<T> script, String operation, String windowId,
                          List<String> keys, String... args) {
        return run(operation, windowId, () -> redis.execute(script, keys, (Object[]) args));
    }

    /**
     * Redis 예외를 도메인 예외로 옮기는 지점. 스크립트든 단일 명령이든 같은 방식으로 실패한다.
     */
    private <T> T run(String operation, String windowId, Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.error("{} 실패 - Redis 통신 오류. window={}", operation, windowId, e);
            throw new QueueException.Unavailable("대기열이 일시적으로 불가합니다.", e);
        } catch (RedisSystemException e) {
            // NOSCRIPT는 Spring이 EVAL 재시도로 처리하므로 여기 오지 않는다.
            log.error("{} 스크립트 실행 오류. window={}", operation, windowId, e);
            throw new QueueException.Unavailable("대기열 처리 중 오류가 발생했습니다.", e);
        }
    }

    private static String millis(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    /**
     * status.lua의 한 스냅샷. rank는 0-based이며 대기 중이 아니면 -1이다.
     * total은 대기 인원으로, 입장한 뒤에도 "지금 몇 명이 기다리는지"로 의미가 남는다.
     * expireAt은 활성 만료 epoch ms이며, 활성이 아니면 -1이다.
     */
    public record Snapshot(long state, long rank, long total, long expireAt) {

        private static final long WAITING = 0;
        private static final long ADMITTED = 1;

        public boolean waiting() {
            return state == WAITING;
        }

        public boolean admitted() {
            return state == ADMITTED;
        }

        /** 만료됐거나, 발급된 적 없거나, 창이 다르다. */
        public boolean gone() {
            return !waiting() && !admitted();
        }
    }

    /** promoted는 이번 주기에 올라간 인원, active/waiting은 실행 직후의 각 인원. */
    public record Promotion(long promoted, long active, long waiting) {}
}
