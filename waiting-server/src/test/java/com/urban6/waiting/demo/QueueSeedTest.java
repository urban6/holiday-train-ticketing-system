package com.urban6.waiting.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.demo.QueueSeeder.Counts;
import com.urban6.waiting.queue.DailyWindow;
import com.urban6.waiting.queue.QueueKeys;
import com.urban6.waiting.queue.QueueProperties;
import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueService;
import com.urban6.waiting.queue.WaitingQueueService.State;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 시연용 시드가 실제 대기열 위에서 도는지 확인한다.
 *
 * <p>확인하려는 것은 두 가지다. 봇이 진짜 사용자와 같은 줄에 서는가(그래야 순번이 줄어드는
 * 모습이 사실과 같다), 그리고 봇이 활성 정원을 붙잡지 않는가(그러지 않으면 시드한 만큼
 * 진짜 사용자가 못 들어온다). 두 번째가 이 기능의 핵심이다 — README의 유령 항목 문제를
 * 일부러 피해 간 지점이라, 깨져도 화면에서는 "그냥 좀 느리네"로만 보인다.
 *
 * <p>봇 회수는 {@code BotDrainScheduler}가 200ms마다 하지만 여기서는 스케줄러를 기다리지 않고
 * {@link QueueSeeder#drain} 을 직접 부른다. 타이밍에 기대는 테스트는 CI에서 흔들린다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "queue.demo.enabled=true")
class QueueSeedTest {

    @Autowired QueueSeeder seeder;
    @Autowired WaitingQueueService waitingQueueService;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired Clock clock;
    @Autowired StringRedisTemplate redis;

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 남은 항목이 다음 테스트의 순번에 섞이므로 매번 비운다. */
    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(windowId()), QueueKeys.seq(windowId()), QueueKeys.active(windowId())));
    }

    @Test
    @DisplayName("시드한 봇 뒤에 줄을 서면 그 인원만큼 앞사람이 생긴다")
    void seededBotsStandAheadOfARealUser() {
        seed(50);

        Ticket ticket = waitingQueueService.enqueue();

        var status = waitingQueueService.status(ticket.windowId(), ticket.token());
        assertThat(status.state()).isEqualTo(State.WAITING);
        assertThat(status.ahead()).isEqualTo(50);
        assertThat(status.position()).isEqualTo(51);
        assertThat(status.total()).isEqualTo(51);
    }

    @Test
    @DisplayName("봇도 실사용자와 같은 순번 카운터를 쓴다")
    void botsShareTheSequenceCounterWithRealUsers() {
        Ticket first = waitingQueueService.enqueue();
        seed(10);
        Ticket second = waitingQueueService.enqueue();

        // 봇 10명이 그 사이에 들어갔으므로 두 사람의 순번 차이가 11이어야 한다.
        assertThat(second.seq() - first.seq()).isEqualTo(11);
    }

    @Test
    @DisplayName("봇은 승격되지만 회수하면 활성 정원을 붙잡지 않는다")
    void drainedBotsDoNotHoldCapacity() {
        seed(20);

        Promotion promotion = waitingQueueService.promote();
        assertThat(promotion.promoted()).isEqualTo(20);
        assertThat(seeder.counts(windowId())).isEqualTo(new Counts(0, 20));

        assertThat(seeder.drain(windowId())).isEqualTo(20);
        assertThat(seeder.counts(windowId())).isEqualTo(new Counts(0, 0));
    }

    @Test
    @DisplayName("회수는 봇만 골라낸다. 실사용자의 자리는 남는다")
    void drainLeavesRealUsersAlone() {
        seed(5);
        Ticket ticket = waitingQueueService.enqueue();
        waitingQueueService.promote();

        seeder.drain(windowId());

        assertThat(waitingQueueService.activeUntil(ticket.windowId(), ticket.token())).isPresent();
        assertThat(seeder.counts(windowId())).isEqualTo(new Counts(0, 1));
    }

    @Test
    @DisplayName("정원을 넘겨 시드해도 승격은 max-batch를 넘지 않는다")
    void seedingBeyondCapacityStillRespectsMaxBatch() {
        int count = properties.capacity() + properties.maxBatch();
        seed(count);

        long promoted = waitingQueueService.promote().promoted();

        assertThat(promoted).isEqualTo(properties.maxBatch());
        assertThat(seeder.counts(windowId()).waiting()).isEqualTo(count - properties.maxBatch());
    }

    @Test
    @DisplayName("비우면 대기·활성·순번이 모두 사라져 순번이 1부터 다시 시작한다")
    void clearResetsTheWindow() {
        seed(30);
        waitingQueueService.promote();

        seeder.clear(windowId());

        assertThat(seeder.counts(windowId())).isEqualTo(new Counts(0, 0));
        assertThat(waitingQueueService.enqueue().seq()).isEqualTo(1);
    }

    @Test
    @DisplayName("청크 경계를 넘겨 시드해도 인원이 정확히 맞는다")
    void seedingAcrossChunkBoundary() {
        // SEED_CHUNK가 1,000이라 여러 번의 스크립트 호출로 갈라지는 구간이다.
        seed(2_500);

        assertThat(seeder.counts(windowId()).waiting()).isEqualTo(2_500);
    }

    @Test
    @DisplayName("이탈은 요청한 인원만큼 대기열에서 뺀다")
    void attritionRemovesTheRequestedNumber() {
        seed(100);

        assertThat(seeder.attrition(windowId(), 30)).isEqualTo(30);
        assertThat(seeder.counts(windowId()).waiting()).isEqualTo(70);
    }

    /**
     * 이 테스트가 이탈 기능의 안전선이다. 이탈은 큐 전체에서 무작위로 뽑으므로 필터가 깨지면
     * 대기 중인 진짜 사용자가 소리 없이 큐에서 사라진다. 화면에는 "대기 정보를 찾을 수 없습니다"로만
     * 보여서 원인을 짐작하기 어렵다.
     */
    @Test
    @DisplayName("대기 중인 실사용자는 몇 번을 이탈시켜도 큐에 남는다")
    void attritionNeverRemovesARealUser() {
        seed(50);
        Ticket ticket = waitingQueueService.enqueue();
        seed(50);

        // 큐 전체 크기만큼, 여러 번 뽑는다. 무작위라 한 번으로는 안심할 수 없다.
        for (int i = 0; i < 10; i++) {
            seeder.attrition(windowId(), 101);
        }

        assertThat(seeder.counts(windowId()).waiting()).isEqualTo(1);
        assertThat(waitingQueueService.status(ticket.windowId(), ticket.token()).state())
                .isEqualTo(State.WAITING);
    }

    @Test
    @DisplayName("큐가 비었거나 인원이 모자라도 있는 만큼만 빠진다")
    void attritionHandlesAnUndersizedQueue() {
        assertThat(seeder.attrition(windowId(), 500)).isZero();

        seed(10);
        assertThat(seeder.attrition(windowId(), 500)).isEqualTo(10);
        assertThat(seeder.counts(windowId()).waiting()).isZero();
    }

    @Test
    @DisplayName("이탈은 순번 카운터를 되돌리지 않는다")
    void attritionDoesNotRewindTheSequence() {
        seed(100);
        seeder.attrition(windowId(), 100);

        // 카운터가 되돌아가면 이미 나간 봇과 같은 순번이 다시 발급되어 ZSet에서 겹친다.
        assertThat(waitingQueueService.enqueue().seq()).isEqualTo(101);
    }

    private void seed(int count) {
        var window = dailyWindow.at(clock.instant());
        seeder.seed(window.windowId(), count, window.waitingDeadline(), window.seqDeadline());
    }

    private String windowId() {
        return dailyWindow.at(clock.instant()).windowId();
    }
}
