package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.ReservationDeadlineTest.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 승격·회수 스케줄러를 모든 WAS에서 돌려도 되는 근거. 여러 대가 같은 샤드를 동시에 불러도
 * promote.lua는 정원을 넘기지 않고, sweep.lua는 같은 사람을 두 번 회수하지 않는다.
 *
 * <p>기동 직후 배경 스케줄러가 한 번 돌며 테스트와 겹칠 수 있다. 그 몫은 지표 카운터에만 쌓이므로
 * 테스트가 직접 센 합계에 카운터 증분을 더해 비교한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "queue.shard-count=1",
        "queue.capacity=50",
        "queue.max-batch=50",
        // 배경 스케줄러가 끼어들면 테스트가 센 합계와 섞인다. QueueLeaveTest와 같은 이유다.
        "queue.promote-interval=1h",
        "queue.sweep-interval=59s"
})
class ConcurrentSchedulerTest {

    private static final int SCHEDULERS = 8;

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @Autowired WaitingQueueService waitingQueueService;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired MutableClock clock;
    @Autowired StringRedisTemplate redis;
    @Autowired MeterRegistry registry;

    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(date(), 0), QueueKeys.seq(date(), 0),
                QueueKeys.active(date(), 0), QueueKeys.pollDeadline(date(), 0)));
    }

    @Test
    @DisplayName("여러 스케줄러가 동시에 승격해도 정원을 넘지 않는다")
    void concurrentPromotionNeverExceedsCapacity() throws Exception {
        int capacity = properties.capacity();
        double background = counter("queue.promoted");
        for (int i = 0; i < capacity * 3; i++) {
            waitingQueueService.enqueue();
        }

        long promoted = runConcurrently(() -> waitingQueueService.promote(0).promoted());

        assertThat(promoted + counter("queue.promoted") - background).isEqualTo(capacity);
        assertThat(redis.opsForZSet().zCard(QueueKeys.active(date(), 0))).isEqualTo(capacity);
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(date(), 0))).isEqualTo(capacity * 2L);
    }

    @Test
    @DisplayName("여러 스케줄러가 동시에 회수해도 같은 대기자를 두 번 세지 않는다")
    void concurrentSweepCountsEachWaiterOnce() throws Exception {
        int stale = 30;
        double background = counter("queue.swept") + counter("queue.promoted");
        for (int i = 0; i < stale; i++) {
            waitingQueueService.enqueue();
        }
        clock.advance(properties.minPollInterval().plus(properties.pollGrace()).plusSeconds(1));

        long swept = runConcurrently(() -> waitingQueueService.sweepStale(0));

        // 배경 승격이 가져간 사람도 대기열에서 빠지므로 함께 센다.
        assertThat(swept + counter("queue.swept") + counter("queue.promoted") - background).isEqualTo(stale);
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(date(), 0))).isZero();
    }

    /** SCHEDULERS개가 한꺼번에 출발해 각자 한 번씩 부르고, 결과를 더한다. */
    private long runConcurrently(Callable<Long> cycle) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < SCHEDULERS; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return cycle.call();
                }));
            }
            start.countDown();

            long total = 0;
            for (Future<Long> result : results) {
                total += result.get();
            }
            return total;
        }
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private String date() {
        return dailyWindow.at(clock.instant()).date();
    }
}
