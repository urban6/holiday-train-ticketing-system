package com.urban6.waiting.demo;

import com.urban6.waiting.queue.QueueKeys;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * 시연용 더미 대기자를 넣고 빼는 Redis 접근.
 *
 * <p>키는 {@link QueueKeys}를 그대로 쓴다 — 봇을 별도 공간에 두면 실사용자와 같은 큐에
 * 서지 않아서 순번이 줄어드는 모습을 재현할 수 없다. 봇은 진짜 사용자와 완전히 같은
 * 자료구조 위에 있고, 이름 앞의 {@code bot:}만 다르다.
 *
 * <p>{@code WaitingQueueRepository}를 건드리지 않고 따로 둔 이유는 이 클래스가
 * 시연 전용이기 때문이다. 기능이 꺼지면 통째로 사라져야 하는 코드가 프로덕션 경로에
 * 섞여 있으면 안 된다.
 */
@Repository
@ConditionalOnDemo
@RequiredArgsConstructor
public class QueueSeeder {

    /**
     * 한 스크립트 호출에 넣을 최대 인원.
     *
     * <p>Redis는 단일 스레드라 100만 개 ZADD를 한 번에 몰면 그동안 다른 모든 요청이 멈춘다.
     * {@code promote.lua}에 max-batch를 둔 것과 같은 이유이고, 같은 자릿수로 맞췄다.
     *
     * <p>청크 사이에는 다른 요청이 끼어들 수 있다. 100만 명 시드가 1,000번의 스크립트 호출로
     * 갈라져 2.8초쯤 걸리는데, 그동안 대기열 조회와 승격이 멈추지 않는 이유가 이것이다.
     */
    private static final int SEED_CHUNK = 1_000;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> demoSeedScript;
    private final RedisScript<Long> demoDrainScript;
    private final RedisScript<Long> demoAttritionScript;

    /** 대기열 뒤에 봇 {@code count}명을 붙인다. 청크로 나눠 넣으므로 중간에 실사용자가 끼어들 수 있다. */
    public void seed(String windowId, int count, Instant waitingDeadline, Instant seqDeadline) {
        List<String> keys = List.of(QueueKeys.waiting(windowId), QueueKeys.seq(windowId));

        for (int remaining = count; remaining > 0; remaining -= SEED_CHUNK) {
            int chunk = Math.min(remaining, SEED_CHUNK);
            redis.execute(demoSeedScript, keys,
                    String.valueOf(chunk),
                    String.valueOf(waitingDeadline.toEpochMilli()),
                    String.valueOf(seqDeadline.toEpochMilli()));
        }
    }

    /** 승격된 봇을 활성에서 회수한다. @return 회수한 인원 */
    public long drain(String windowId) {
        Long removed = redis.execute(demoDrainScript, List.of(QueueKeys.active(windowId)));
        return removed == null ? 0 : removed;
    }

    /**
     * 대기 중인 봇 {@code count}명을 큐에서 무작위로 뺀다. "기다리다 포기한 사람"이다.
     *
     * <p>승격만으로는 순번이 매 주기 정확히 max-batch씩 줄어 기계처럼 보인다.
     * 여기서 빠지는 인원이 더해져 감소 폭이 흔들린다.
     *
     * @return 실제로 제거한 인원. 큐에 봇이 부족하면 요청보다 적다.
     */
    public long attrition(String windowId, int count) {
        Long removed = redis.execute(demoAttritionScript,
                List.of(QueueKeys.waiting(windowId)), String.valueOf(count));
        return removed == null ? 0 : removed;
    }

    /** 화면에 보여줄 현재 인원. */
    public Counts counts(String windowId) {
        return new Counts(
                card(QueueKeys.waiting(windowId)),
                card(QueueKeys.active(windowId)));
    }

    /**
     * 창을 통째로 비운다. 봇만 골라 지우지 않는 이유는 이게 "초기화" 버튼이기 때문이다 —
     * 시드를 여러 번 돌린 뒤 처음부터 다시 보려면 순번 카운터까지 사라져야 한다.
     *
     * <p>active까지 지우므로 그 순간 로그인·예약 화면에 있던 실사용자도 랜딩으로 돌아간다.
     */
    public void clear(String windowId) {
        redis.unlink(List.of(
                QueueKeys.waiting(windowId),
                QueueKeys.seq(windowId),
                QueueKeys.active(windowId)));
    }

    private long card(String key) {
        Long size = redis.opsForZSet().zCard(key);
        return size == null ? 0 : size;
    }

    public record Counts(long waiting, long active) {}
}
