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
    private final RedisScript<Long> leaveScript;
    private final RedisScript<Long> sweepScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> statusScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> promoteScript;

    /**
     * 순번을 발급하고 대기열에 등록한다.
     * 두 작업은 Lua Script로 묶어서 실행한다.
     */
    public long enqueue(String windowId, String uuid, long firstPollDeadlineMillis,
                        Instant waitingDeadline, Instant seqDeadline) {
        Long seq = execute(enqueueScript, "대기열 진입", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.seq(windowId), QueueKeys.pollDeadline(windowId)),
                uuid,
                millis(waitingDeadline),
                millis(seqDeadline),
                String.valueOf(firstPollDeadlineMillis));

        if (seq == null) {
            throw new IllegalStateException("스크립트가 seq를 반환하지 않았습니다: " + windowId);
        }
        return seq;
    }

    /**
     * 대기 순번과 입장 여부를 함께 읽는다.
     * 두 값 사이에 큐가 변하면 앞뒤 합이 어긋나므로 Lua Script로 묶어서 실행한다.
     *
     * <p>대기 중이면 다음 폴링 기한을 찍는다 — 이 조회가 곧 하트비트다.
     * 그래서 이름과 달리 순수 읽기가 아니다. 그 대가를 치른 이유는 status.lua 주석에 있다.
     *
     * <p>주기 설정을 함께 넘긴다. 순번이 스크립트 안에서 나오므로 주기를 여기서 미리 계산할 수
     * 없고, 왕복을 두 번으로 쪼갤 수는 없다. promote가 같은 이유로 QueueProperties를 받는다.
     */
    @SuppressWarnings("unchecked")
    public Snapshot status(String windowId, String uuid, long nowMillis, QueueProperties properties) {
        List<Long> result = execute(statusScript, "순번 조회", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.active(windowId), QueueKeys.pollDeadline(windowId)),
                uuid,
                String.valueOf(nowMillis),
                String.valueOf(properties.millisPerRank()),
                String.valueOf(properties.minPollInterval().toMillis()),
                String.valueOf(properties.maxPollInterval().toMillis()),
                String.valueOf(properties.pollGrace().toMillis()));

        if (result == null || result.size() < 5) {
            throw new IllegalStateException(
                    "스크립트가 state/rank/total/expireAt/pollAfter를 반환하지 않았습니다: " + windowId);
        }
        return new Snapshot(result.get(0), result.get(1), result.get(2), result.get(3), result.get(4));
    }

    /**
     * 만료된 활성 사용자를 회수하고, 정원에 빈 만큼 대기열 앞에서 승격시킨다.
     * 정원을 세는 것과 꺼내는 것이 갈라지면 그 사이에 정원을 넘길 수 있어 한 스크립트다.
     */
    @SuppressWarnings("unchecked")
    public Promotion promote(String windowId, long nowMillis, QueueProperties properties, Instant activeDeadline) {
        List<Long> result = execute(promoteScript, "승격", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.active(windowId), QueueKeys.pollDeadline(windowId)),
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
     * 대기열에서 뺀다. 사용자가 팝업을 닫거나 페이지를 떠난 경우다.
     *
     * <p>waiting과 폴링 기한 두 키에 걸치므로 Lua로 묶는다. active는 건드리지 않는다 —
     * 이유는 leave.lua 주석에 있다.
     *
     * @return 실제로 뺐으면 true. 이미 없었으면 false지만 호출자는 둘 다 성공으로 다룬다.
     */
    public boolean leave(String windowId, String uuid) {
        Long removed = execute(leaveScript, "대기열 이탈", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.pollDeadline(windowId)),
                uuid);

        return removed != null && removed == 1L;
    }

    /**
     * 다음 폴링 기한이 지난 대기자를 회수한다.
     *
     * <p>이탈 요청이 도달하지 못한 경우를 덮는다. 브라우저 이벤트가 아예 오지 않는 경로
     * (크래시·기기 꺼짐·네트워크 단절)는 이것 말고 잡을 수단이 없다.
     *
     * <p>판정 기준을 여기서 빼지 않는다. 기한은 사람마다 다르고, 조회 때 그 사람에게 알려 준
     * 주기에 담겨 score에 이미 구워져 있다.
     *
     * @return 이번에 회수한 인원. maxSweep이 상한이다.
     */
    public long sweep(String windowId, long nowMillis, int maxSweep) {
        Long swept = execute(sweepScript, "이탈 회수", windowId,
                List.of(QueueKeys.waiting(windowId), QueueKeys.pollDeadline(windowId)),
                String.valueOf(nowMillis),
                String.valueOf(maxSweep));

        return swept == null ? 0 : swept;
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
     * pollAfterMillis는 다음 조회까지 기다릴 시간이며, 대기 중이 아니면 0이다.
     */
    public record Snapshot(long state, long rank, long total, long expireAt, long pollAfterMillis) {

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
