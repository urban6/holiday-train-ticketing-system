package com.urban6.waiting.queue;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.ReservationDeadlineTest.MutableClock;
import com.urban6.waiting.queue.WaitingQueueService.State;
import com.urban6.waiting.queue.WaitingQueueService.Status;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;

/**
 * 서버가 순번에 따라 다른 폴링 주기를 내려 주는지 확인한다.
 *
 * <p>모두에게 같은 주기를 주면 조회 부하가 대기 인원에 정비례한다. 그런데 뒤쪽 사람에게 오는
 * 답은 한동안 "아직 아닙니다"로 같으므로, 그 낭비를 걷어내는 것이 이 계산의 목적이다.
 * 대신 잘못 계산하면 사람이 자기 차례를 자면서 넘기므로, 아래 마지막 테스트가 그 성질을 지킨다.
 *
 * <p>주기는 승격 속도로 잰 값이라 배경 승격이 돌면 순번이 흔들린다. QueueLeaveTest와 같은 방법으로
 * 스케줄러를 사실상 멈춘다. 기대값을 상수로 박지 않고 {@link QueueProperties}에서 거꾸로 구하므로,
 * 승격 주기를 늘려 둬도 검증하는 성질은 그대로다 — 대신 상한에 닿는 순번이 작아져 시드가 싸진다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // 샤드 하나로 고정한다. 주기 계산의 축인 순번이 샤드 안 값이라 샤딩과 무관하게 같은
        // 식이 성립하지만(QueueProperties.millisPerRank 참고), 여기서는 "이 순번에 이 주기"를
        // 정확히 단언하므로 시드한 순번이 그대로 ZRANK로 나와야 한다.
        "queue.shard-count=1",
        "queue.promote-interval=1h",
        "queue.sweep-interval=59s"
})
class PollIntervalTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.now());
        }
    }

    @Autowired WaitingQueueService waitingQueueService;
    @Autowired MutableClock clock;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired StringRedisTemplate redis;

    /**
     * 정원을 가짜 입장자로 채워 배경 승격을 막는다.
     *
     * <p>이 클래스만 스케줄러를 늦춰서는 부족하다. Spring이 테스트 클래스마다 컨텍스트를 캐시해
     * 두는데, 승격 주기를 기본값(1초)으로 둔 다른 테스트의 스케줄러가 JVM이 살아 있는 내내
     * 같은 Redis를 훑는다. 그쪽이 대기열 앞을 걷어가면 여기서 만든 순번이 조회 직전에 당겨져,
     * 통과와 실패가 실행 순서에 따라 갈린다.
     *
     * <p>promote.lua는 빈 자리가 없으면 아무도 올리지 않으므로, 자리를 막는 것이 가장 확실하다.
     */
    @BeforeEach
    void blockPromotion() {
        Set<TypedTuple<String>> holders = new HashSet<>();
        long farFuture = clock.millis() + Duration.ofHours(1).toMillis();
        for (int i = 0; i < properties.capacity(); i++) {
            holders.add(TypedTuple.of("holder:" + i, (double) farFuture));
        }
        redis.opsForZSet().add(QueueKeys.active(windowId(), 0), holders);
    }

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 남은 항목이 다음 테스트의 순번에 섞이므로 매번 비운다. */
    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(windowId(), 0), QueueKeys.seq(windowId(), 0),
                QueueKeys.active(windowId(), 0), QueueKeys.pollDeadline(windowId(), 0)));
    }

    @Test
    @DisplayName("맨 앞 사람은 하한 주기를 받는다")
    void theFrontOfTheQueueGetsTheFloor() {
        // 하한이 없으면 남은 시간이 0에 수렴하면서 맨 앞이 서로를 두들긴다.
        Ticket ticket = waitingQueueService.enqueue();

        assertThat(status(ticket).pollAfterMillis())
                .isEqualTo(properties.minPollInterval().toMillis());
    }

    @Test
    @DisplayName("진입 응답도 첫 주기를 알려 준다 — 클라이언트가 상수를 들고 있지 않게")
    void enqueueAnswersWithTheFirstInterval() {
        Ticket ticket = waitingQueueService.enqueue();

        assertThat(ticket.pollAfterMillis()).isEqualTo(properties.minPollInterval().toMillis());
    }

    @Test
    @DisplayName("중간 순번은 남은 시간에 비례한 주기를 받는다")
    void theMiddleOfTheQueueScalesWithTheRemainingTime() {
        seedBots(rankAtCeiling() / 2);
        Ticket ticket = waitingQueueService.enqueue();

        Status status = status(ticket);

        // 순번과 주기를 같은 스냅샷에서 읽는다. 상수로 박으면 앞사람이 하나만 빠져도 흔들리는데,
        // 검증하려는 건 "그 순번에서 이 주기가 나오는가"이지 특정 숫자가 아니다.
        assertThat(status.pollAfterMillis())
                .isEqualTo((long) (status.ahead() * properties.millisPerRank()));
        assertThat(status.pollAfterMillis()).isStrictlyBetween(
                properties.minPollInterval().toMillis(), properties.maxPollInterval().toMillis());
    }

    @Test
    @DisplayName("깊은 순번은 상한에서 멈춘다")
    void theBackOfTheQueueIsCappedAtTheCeiling() {
        // 상한이 없으면 뒤쪽은 사실상 폴링을 멈추고, 승격이 와도 한참 뒤에야 알아챈다.
        seedBots(rankAtCeiling() * 2);
        Ticket ticket = waitingQueueService.enqueue();

        assertThat(status(ticket).pollAfterMillis())
                .isEqualTo(properties.maxPollInterval().toMillis());
    }

    @Test
    @DisplayName("입장하면 주기가 0이다 — 더 물어볼 것이 없다")
    void admittedNeedsNoFurtherPolling() {
        Ticket ticket = waitingQueueService.enqueue();

        // 이 테스트만 승격이 실제로 일어나야 하므로 막아 둔 자리를 먼저 비운다.
        redis.delete(QueueKeys.active(windowId(), 0));
        waitingQueueService.promote(0);

        Status status = status(ticket);
        assertThat(status.state()).isEqualTo(State.ADMITTED);
        assertThat(status.pollAfterMillis()).isZero();
    }

    @Test
    @DisplayName("이탈 기한은 알려 준 주기에 유예를 더한 시각이다")
    void theDeadlineIsTheAnsweredIntervalPlusGrace() {
        // 스위퍼가 보는 값이 응답으로 나간 값과 같아야 한다. 둘이 어긋나면 화면은 30초를
        // 기다리라 해 놓고 서버는 그 전에 줄에서 빼는 상태가 되는데, 로그에는 아무것도 안 남는다.
        seedBots(rankAtCeiling() * 2);
        Ticket ticket = waitingQueueService.enqueue();

        long pollAfter = status(ticket).pollAfterMillis();

        Double deadline = redis.opsForZSet().score(QueueKeys.pollDeadline(windowId(), 0), ticket.token());
        assertThat(requireNonNull(deadline).longValue())
                .isEqualTo(clock.millis() + pollAfter + properties.pollGrace().toMillis());
    }

    @Test
    @DisplayName("뒤쪽 대기자는 앞사람의 기한이 지나도 살아 있다 — 판정 기준이 사람마다 다르다")
    void theDeepWaiterOutlivesTheFrontDeadline() {
        // 이 기능이 막으려는 회귀 그 자체다. 판정이 고정값으로 남으면, 뒤쪽에 30초 주기를 준 다음
        // 60초 고정값으로 훑는 순간 시킨 대로 기다린 멀쩡한 사용자가 줄에서 빠진다.
        seedBots(rankAtCeiling() * 2);
        Ticket deep = waitingQueueService.enqueue();

        // 상한 주기를 받는지부터 확인한다. 하한을 받았다면 아래가 통과해도 의미가 없다.
        assertThat(status(deep).pollAfterMillis()).isEqualTo(properties.maxPollInterval().toMillis());

        clock.advance(properties.minPollInterval().plus(properties.pollGrace()).plusSeconds(1));
        assertThat(waitingQueueService.sweepStale(0)).isZero();

        clock.advance(properties.maxPollInterval().minus(properties.minPollInterval()));
        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 스윕 한 번에 앞사람은 회수되고 뒷사람은 남는다")
    void oneSweepJudgesEachWaiterByTheirOwnDeadline() {
        Ticket front = waitingQueueService.enqueue();
        seedBots(rankAtCeiling() * 2);
        Ticket deep = waitingQueueService.enqueue();

        // 둘 다 한 번씩 조회해 각자의 주기로 기한을 받는다 — 앞은 하한, 뒤는 상한.
        assertThat(status(front).pollAfterMillis()).isEqualTo(properties.minPollInterval().toMillis());
        assertThat(status(deep).pollAfterMillis()).isEqualTo(properties.maxPollInterval().toMillis());

        clock.advance(properties.minPollInterval().plus(properties.pollGrace()).plusSeconds(1));

        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(1);
        assertThat(status(deep).state()).isEqualTo(State.WAITING);
    }

    @Test
    @DisplayName("알려 준 주기는 남은 시간을 넘지 않는다 — 자기 차례를 자면서 넘기지 않는다")
    void theIntervalNeverOutlastsTheWait() {
        // 승격은 한 주기에 max-batch를 넘지 못하므로(promote.lua) 큐는 이보다 빨리 줄어들 수 없다.
        // 그래서 이 식으로 잰 남은 시간은 실제보다 짧고, 주기가 그 안에 있으면 안전하다.
        // 하한을 받은 사람은 예외다 — 남은 시간이 하한보다 짧아 어차피 곧 승격된다.
        for (int rank : List.of(0, 1, rankAtCeiling() / 2, rankAtCeiling(), rankAtCeiling() * 3)) {
            clearWindow();
            seedBots(rank);
            Ticket ticket = waitingQueueService.enqueue();

            long pollAfter = status(ticket).pollAfterMillis();
            long remaining = rank * properties.promoteInterval().toMillis() / properties.maxBatch();

            assertThat(pollAfter <= remaining || pollAfter == properties.minPollInterval().toMillis())
                    .as("rank %d에서 주기 %dms가 남은 시간 %dms를 넘었다", rank, pollAfter, remaining)
                    .isTrue();
        }
    }

    /** 주기가 상한에 처음 닿는 순번. 공식이 바뀌어도 따라오도록 설정에서 거꾸로 구한다. */
    private int rankAtCeiling() {
        return (int) Math.ceil(properties.maxPollInterval().toMillis() / properties.millisPerRank());
    }

    /** 순번 카운터를 한 번에 예약하고 그 자리를 통째로 채운다. enqueue를 순번마다 부르지 않기 위해서다. */
    private void seedBots(int count) {
        if (count == 0) {
            return;
        }
        long last = requireNonNull(redis.opsForValue().increment(QueueKeys.seq(windowId(), 0), count));

        Set<TypedTuple<String>> bots = new HashSet<>();
        for (long seq = last - count + 1; seq <= last; seq++) {
            bots.add(TypedTuple.of("bot:" + seq, (double) seq));
        }
        redis.opsForZSet().add(QueueKeys.waiting(windowId(), 0), bots);
    }

    private Status status(Ticket ticket) {
        return waitingQueueService.status(ticket.windowId(), ticket.token());
    }

    private String windowId() {
        return dailyWindow.at(clock.instant()).windowId();
    }
}
