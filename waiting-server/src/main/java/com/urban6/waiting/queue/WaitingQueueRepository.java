package com.urban6.waiting.queue;

import java.time.Instant;
import java.util.List;
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

    /**
     * 순번을 발급하고 대기열에 등록한다.
     * 두 작업은 Lua Script로 묶어서 실행한다.
     */
    public long enqueue(String windowId, String uuid, Instant waitingDeadline, Instant seqDeadline) {
        List<String> keys = List.of(QueueKeys.waiting(windowId), QueueKeys.seq(windowId));

        Long seq;
        try {
            seq = redis.execute(enqueueScript, keys,
                    uuid,
                    String.valueOf(waitingDeadline.toEpochMilli()),
                    String.valueOf(seqDeadline.toEpochMilli()));
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.error("대기열 진입 실패 - Redis 통신 오류. window={}", windowId, e);
            throw new QueueException.Unavailable("대기열이 일시적으로 불가합니다.", e);
        } catch (RedisSystemException e) {
            // NOSCRIPT는 Spring이 EVAL 재시도로 처리하므로 여기 오지 않는다.
            log.error("대기열 스크립트 실행 오류. window={}", windowId, e);
            throw new QueueException.Unavailable("대기열 처리 중 오류가 발생했습니다.", e);
        }

        if (seq == null) {
            throw new IllegalStateException("스크립트가 seq를 반환하지 않았습니다: " + windowId);
        }
        return seq;
    }
}
