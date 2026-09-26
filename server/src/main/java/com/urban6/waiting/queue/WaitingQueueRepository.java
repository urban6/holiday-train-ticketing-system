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
    private final QueueProperties properties;
    private final RedisScript<Long> enqueueScript;
    private final RedisScript<Long> restampScript;
    private final RedisScript<Long> leaveScript;
    private final RedisScript<Long> sweepScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> statusScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> promoteScript;

    /** @return 샤드 안에서의 순번. 샤드마다 seq 카운터가 따로다. */
    public long enqueue(String date, String uuid, long firstPollDeadlineMillis,
                        Instant waitingDeadline, Instant seqDeadline) {
        int shard = shardOf(uuid);
        Long seq = execute(enqueueScript, "대기열 진입", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.seq(date, shard),
                        QueueKeys.pollDeadline(date, shard)),
                uuid,
                millis(waitingDeadline),
                millis(seqDeadline),
                String.valueOf(firstPollDeadlineMillis));

        if (seq == null) {
            throw new IllegalStateException("스크립트가 seq를 반환하지 않았습니다: " + date);
        }
        return seq;
    }

    /** 대기 중이면 다음 폴링 기한도 찍는다. 이 조회가 하트비트라 순수 읽기가 아니다. */
    @SuppressWarnings("unchecked")
    public Snapshot status(String date, String uuid, long nowMillis, QueueProperties properties) {
        int shard = shardOf(uuid);
        List<Long> result = execute(statusScript, "순번 조회", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.active(date, shard),
                        QueueKeys.pollDeadline(date, shard)),
                uuid,
                String.valueOf(nowMillis),
                String.valueOf(properties.millisPerRank()),
                String.valueOf(properties.minPollInterval().toMillis()),
                String.valueOf(properties.maxPollInterval().toMillis()),
                String.valueOf(properties.pollGrace().toMillis()));

        if (result == null || result.size() < 5) {
            throw new IllegalStateException(
                    "스크립트가 state/rank/total/expireAt/pollAfter를 반환하지 않았습니다: " + date);
        }
        return new Snapshot(result.get(0), result.get(1), result.get(2), result.get(3), result.get(4));
    }

    /**
     * 샤드마다 정원의 몫을 갖고 자기 줄 앞에서 꺼내므로 전역으로 엄격한 선착순은 아니다.
     * maxBatch는 노드 하나가 한 번에 붙잡히는 시간을 막는 값이라 샤드 수로 나누지 않는다.
     */
    @SuppressWarnings("unchecked")
    public Promotion promote(String date, int shard, long nowMillis, QueueProperties properties,
                             Instant activeDeadline) {
        List<Long> result = execute(promoteScript, "승격", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.active(date, shard),
                        QueueKeys.pollDeadline(date, shard)),
                String.valueOf(nowMillis),
                String.valueOf(properties.capacityOf(shard)),
                String.valueOf(properties.maxBatch()),
                String.valueOf(properties.admissionGrace().toMillis()),
                millis(activeDeadline));

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("스크립트가 승격 결과를 반환하지 않았습니다: " + date);
        }
        return new Promotion(result.get(0), result.get(1), result.get(2));
    }

    /** @return 활성이 아니거나 이미 만료됐으면 false */
    public boolean restamp(String date, String uuid, long nowMillis, Duration ttl, String operation) {
        int shard = shardOf(uuid);
        Long restamped = execute(restampScript, operation, date, shard,
                List.of(QueueKeys.active(date, shard)),
                uuid,
                String.valueOf(nowMillis),
                String.valueOf(ttl.toMillis()));

        return restamped != null && restamped == 1L;
    }

    public boolean leave(String date, String uuid) {
        int shard = shardOf(uuid);
        Long removed = execute(leaveScript, "대기열 이탈", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.pollDeadline(date, shard)),
                uuid);

        return removed != null && removed == 1L;
    }

    public long sweep(String date, int shard, long nowMillis, int maxSweep) {
        Long swept = execute(sweepScript, "이탈 회수", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.pollDeadline(date, shard)),
                String.valueOf(nowMillis),
                String.valueOf(maxSweep));

        return swept == null ? 0 : swept;
    }

    public void release(String date, String uuid) {
        int shard = shardOf(uuid);
        run("입장권 반납", date, shard,
                () -> redis.opsForZSet().remove(QueueKeys.active(date, shard), uuid));
    }

    private int shardOf(String uuid) {
        return QueueKeys.shardOf(uuid, properties.shardCount());
    }

    private <T> T execute(RedisScript<T> script, String operation, String date, int shard,
                          List<String> keys, String... args) {
        return run(operation, date, shard, () -> redis.execute(script, keys, (Object[]) args));
    }

    /**
     * 샤드 번호를 로그에 남겨 노드 한 대의 장애와 전체 장애를 구분한다.
     * CLUSTERDOWN 같은 Cluster 예외는 일반 RedisSystemException으로 떨어져 아래에서 잡힌다.
     */
    private <T> T run(String operation, String date, int shard, Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.error("{} 실패 - Redis 통신 오류. window={}, shard={}", operation, date, shard, e);
            throw new QueueException.Unavailable("대기열이 일시적으로 불가합니다.", e);
        } catch (RedisSystemException e) {
            log.error("{} 스크립트 실행 오류. window={}, shard={}", operation, date, shard, e);
            throw new QueueException.Unavailable("대기열 처리 중 오류가 발생했습니다.", e);
        }
    }

    private static String millis(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    /** status.lua의 결과. rank와 total은 그 사람의 샤드 안에서의 값이다. */
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

    public record Promotion(long promoted, long active, long waiting) {}
}
