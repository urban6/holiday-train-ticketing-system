package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.ReservationDeadlineTest.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
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
 * 대기열 지표가 실제 큐 상태를 따라가는지 확인한다.
 *
 * <p>지표는 스케줄러에서만 갱신되므로 서비스가 아니라 {@link AdmissionScheduler}·{@link StaleSweeper}를
 * 직접 부른다. 그래야 배선까지 함께 검증된다.
 *
 * <p>카운터는 컨텍스트를 공유하는 테스트끼리 계속 쌓이므로 매번 기준값을 먼저 읽고 증분을 본다.
 * 절대값을 기대하면 실행 순서에 따라 결과가 달라진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // QueueLeaveTest와 같은 이유로 스케줄러를 사실상 멈춘다. 배경에서 승격이 끼어들면
        // 테스트가 직접 부른 주기의 증분과 섞여 어느 쪽이 올린 값인지 구분할 수 없다.
        "queue.promote-interval=1h",
        // poll-grace(60s)보다 작아야 기동 검증을 통과한다. 테스트가 그 전에 끝난다.
        "queue.sweep-interval=59s"
})
class QueueMetricsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @Autowired WaitingQueueService waitingQueueService;
    @Autowired AdmissionScheduler admissionScheduler;
    @Autowired StaleSweeper staleSweeper;
    @Autowired MeterRegistry registry;
    @Autowired MutableClock clock;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired StringRedisTemplate redis;

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 남은 항목이 다음 테스트의 순번에 섞이므로 매번 비운다. */
    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(windowId()), QueueKeys.seq(windowId()),
                QueueKeys.active(windowId()), QueueKeys.pollDeadline(windowId())));
    }

    @Test
    @DisplayName("승격 주기가 돌면 누적 승격 인원과 대기·활성 인원이 기록된다")
    void promotionCycleRecordsTurnoverAndCounts() {
        double before = promotedTotal();

        waitingQueueService.enqueue();
        waitingQueueService.enqueue();
        admissionScheduler.promote();

        assertThat(promotedTotal() - before).isEqualTo(2);
        assertThat(activeGauge()).isEqualTo(2);
        assertThat(waitingGauge()).isZero();
    }

    @Test
    @DisplayName("승격 인원은 주기마다 쌓이고, 대기·활성 인원은 마지막 주기 값으로 덮인다")
    void promotedAccumulatesWhileGaugesAreReplaced() {
        // max-batch를 한 명 넘겨 두 주기에 나눠 올라가게 만든다.
        int overflow = properties.maxBatch() + 1;
        for (int i = 0; i < overflow; i++) {
            waitingQueueService.enqueue();
        }

        double before = promotedTotal();

        admissionScheduler.promote();
        assertThat(promotedTotal() - before).isEqualTo(properties.maxBatch());
        assertThat(activeGauge()).isEqualTo(properties.maxBatch());
        assertThat(waitingGauge()).isEqualTo(1);

        admissionScheduler.promote();

        // 카운터는 두 주기의 합이다. 게이지가 같은 식이었다면 활성 인원이 두 배로 읽힌다.
        assertThat(promotedTotal() - before).isEqualTo(overflow);
        assertThat(activeGauge()).isEqualTo(overflow);
        assertThat(waitingGauge()).isZero();
    }

    @Test
    @DisplayName("회수 주기가 돌면 누적 유령 회수 인원이 기록된다")
    void sweepCycleRecordsReclaimedGhosts() {
        waitingQueueService.enqueue();
        double before = sweptTotal();

        // 판정 기준을 넘기기 전에는 아무도 회수되지 않는다.
        staleSweeper.sweep();
        assertThat(sweptTotal() - before).isZero();

        // 진입만 하고 조회하지 않은 사람의 기한은 하한 주기 + 유예다.
        clock.advance(properties.minPollInterval().plus(properties.pollGrace()).plusSeconds(1));
        staleSweeper.sweep();

        assertThat(sweptTotal() - before).isEqualTo(1);
    }

    private double promotedTotal() {
        return registry.get("queue.promoted").counter().count();
    }

    private double sweptTotal() {
        return registry.get("queue.swept").counter().count();
    }

    private double waitingGauge() {
        return registry.get("queue.waiting").gauge().value();
    }

    private double activeGauge() {
        return registry.get("queue.active").gauge().value();
    }

    private String windowId() {
        return dailyWindow.at(clock.instant()).windowId();
    }
}
