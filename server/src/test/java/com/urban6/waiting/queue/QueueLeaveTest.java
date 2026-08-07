package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.ReservationDeadlineTest.MutableClock;
import com.urban6.waiting.queue.WaitingQueueService.Status;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import java.time.Duration;
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
 * 대기열 이탈과 유령 회수를 확인한다.
 *
 * <p>유령을 방치하면 순번만 부풀지 않는다. <b>유령도 순서가 되면 승격되어 아무도 쓰지 않는 활성
 * 슬롯을 admission-grace만큼 잡으므로 실효 정원이 깎인다.</b> 그래서 회수 경로가 둘이다 —
 * 클라이언트가 보내는 이탈(leave)과, 그 신호가 오지 않는 경우를 덮는 스위퍼(sweepStale).
 *
 * <p>판정 기준은 사람마다 다르다. 서버가 순번에 따라 다른 폴링 주기를 알려 주고, 이탈 기한이
 * "알려 준 주기 + poll-grace"로 정해지기 때문이다. 그래서 여기 경계값은 모두
 * {@code minPollInterval + pollGrace}(맨 앞) 또는 {@code maxPollInterval + pollGrace}(뒤쪽)이다.
 *
 * <p>시계를 앞으로 돌려 검증한다. 기한이 분 단위라 실시간으로 기다리는 테스트는 결국 아무도
 * 돌리지 않는다. MutableClock은 {@link ReservationDeadlineTest}의 것을 그대로 쓴다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // 샤드 하나로 고정한다. 여기서 단언하는 것은 순번과 회수 인원의 정확한 수인데, 샤드가
        // 여럿이면 둘 다 근사가 된다 — 두 명을 연달아 넣어도 서로 다른 샤드에 떨어져 각자
        // 자기 줄의 1등이 되고, max-sweep 상한도 샤드마다 따로 걸린다.
        // 샤딩된 동작 자체는 QueueShardingTest가 본다.
        "queue.shard-count=1",
        // 스케줄러를 사실상 멈춘다. fixedDelay는 기동 직후 한 번 돌고 그다음이 이 간격 뒤라,
        // 크게 잡으면 큐가 비어 있는 기동 시점에만 한 번 돌고 테스트 중에는 다시 오지 않는다.
        //
        // 그러지 않으면 이 테스트는 운에 기댄다. 기본 설정은 capacity 1000 · promote-interval 1s라
        // 승격이 끼어드는 순간 대기자가 통째로 active로 옮겨 가 waiting이 비고, 스위퍼는 훑을 것이
        // 없어진다. 시계를 앞으로 돌리는 테스트라 배경 스윕도 같은 이유로 결과를 가로챈다.
        "queue.promote-interval=1h",
        // poll-grace(60s)보다 작아야 기동 검증을 통과한다. 테스트가 그 전에 끝난다.
        "queue.sweep-interval=59s"
})
class QueueLeaveTest {

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

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 남은 항목이 다음 테스트의 순번에 섞이므로 매번 비운다. */
    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(date(), 0), QueueKeys.seq(date(), 0),
                QueueKeys.active(date(), 0), QueueKeys.pollDeadline(date(), 0)));
    }

    @Test
    @DisplayName("이탈하면 대기열에서 빠지고 뒷사람의 순번이 그만큼 당겨진다")
    void leaveRemovesTheEntryAndPullsUpTheOnesBehind() {
        Ticket first = waitingQueueService.enqueue();
        Ticket second = waitingQueueService.enqueue();

        assertThat(status(second).ahead()).isEqualTo(1);

        waitingQueueService.leave(first.date(), first.token());

        assertThat(waitingCount()).isEqualTo(1);
        assertThat(status(second).ahead()).isZero();
        // 폴링 기한도 함께 비워야 스위퍼가 매 주기 이미 없는 토큰을 다시 지우려 들지 않는다.
        assertThat(pollDeadlineCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이탈은 멱등이다 — 없는 토큰도, 두 번 불러도 성공한다")
    void leaveIsIdempotent() {
        Ticket ticket = waitingQueueService.enqueue();

        waitingQueueService.leave(ticket.date(), ticket.token());
        waitingQueueService.leave(ticket.date(), ticket.token());
        waitingQueueService.leave(ticket.date(), "존재하지-않는-토큰");

        assertThat(waitingCount()).isZero();
    }

    @Test
    @DisplayName("입장한 뒤 도착한 이탈 요청은 활성 슬롯을 반납하지 않는다")
    void leaveAfterAdmissionDoesNotReleaseTheActiveSlot() {
        // 입장에 성공해 다음 화면으로 넘어가는 것도 pagehide라, 그 순간 beacon이 늦게 도착할 수 있다.
        // leave가 active까지 지웠다면 방금 받은 자리를 스스로 반납하게 된다.
        Ticket ticket = waitingQueueService.enqueue();
        waitingQueueService.promote(0);
        waitingQueueService.claim(ticket.date(), ticket.token());

        waitingQueueService.leave(ticket.date(), ticket.token());

        assertThat(waitingQueueService.activeUntil(ticket.date(), ticket.token())).isPresent();
        assertThat(activeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("승격되면 폴링 기한에서도 빠진다")
    void promotionClearsTheHeartbeat() {
        // 승격된 사람은 더 이상 폴링으로 기한을 갱신하지 않는다. 남겨 두면 기한이 지난 뒤
        // 스위퍼가 매 주기 이미 없는 항목을 waiting에서 지우려 든다.
        waitingQueueService.enqueue();
        waitingQueueService.promote(0);

        assertThat(pollDeadlineCount()).isZero();
    }

    @Test
    @DisplayName("폴링이 끊긴 대기자는 알려 준 주기 + 유예가 지나면 회수된다")
    void sweeperRemovesStaleWaiters() {
        waitingQueueService.enqueue();

        // 경계 직전에는 남아 있어야 한다. 짧게 잡으면 순단으로 백오프 중이던 정상 사용자가 빠진다.
        clock.advance(frontDeadline().minusMillis(1));
        assertThat(waitingQueueService.sweepStale(0)).isZero();
        assertThat(waitingCount()).isEqualTo(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(1);
        assertThat(waitingCount()).isZero();
        assertThat(pollDeadlineCount()).isZero();
    }

    @Test
    @DisplayName("순번을 계속 조회하는 대기자는 회수되지 않는다 — 폴링이 곧 하트비트다")
    void pollingKeepsTheWaiterAlive() {
        Ticket ticket = waitingQueueService.enqueue();

        // 기한을 아슬아슬하게 남겨 놓고 한 번 조회하면, 거기서부터 기한이 다시 밀린다.
        clock.advance(frontDeadline().minusSeconds(1));
        status(ticket);

        clock.advance(frontDeadline().minusSeconds(1));
        assertThat(waitingQueueService.sweepStale(0)).isZero();
        assertThat(waitingCount()).isEqualTo(1);

        // 조회를 멈추면 그 시점부터 다시 센다.
        clock.advance(Duration.ofSeconds(2));
        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(1);
    }

    @Test
    @DisplayName("진입만 하고 한 번도 조회하지 않은 대기자도 회수된다")
    void sweeperRemovesWaitersThatNeverPolled() {
        // 첫 조회는 0~하한 주기 사이로 무작위로 늦춰지므로(waiting.js) 그 사이에 떠나면
        // 조회가 한 번도 없는 항목이 남는다. enqueue.lua가 첫 기한을 미리 찍지 않으면
        // 이 사람은 스위퍼에 영영 걸리지 않는다.
        waitingQueueService.enqueue();
        assertThat(pollDeadlineCount()).isEqualTo(1);

        clock.advance(frontDeadline().plusSeconds(1));

        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(1);
        assertThat(waitingCount()).isZero();
    }

    @Test
    @DisplayName("한 주기에 회수하는 인원에 max-sweep 상한이 걸린다")
    void sweepIsCappedByMaxSweep() {
        // 상한이 없으면 대량 이탈이 몰리는 순간 그 시간이 그대로 다른 요청의 지연이 된다.
        // promote의 max-batch와 같은 이유다.
        int overflow = properties.maxSweep() + 1;
        for (int i = 0; i < overflow; i++) {
            waitingQueueService.enqueue();
        }

        clock.advance(frontDeadline().plusSeconds(1));

        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(properties.maxSweep());
        assertThat(waitingCount()).isEqualTo(1);

        // 남은 인원은 다음 주기에 빠진다.
        assertThat(waitingQueueService.sweepStale(0)).isEqualTo(1);
        assertThat(waitingCount()).isZero();
    }

    /**
     * 여기 대기자는 모두 맨 앞이거나 조회한 적이 없어 주기가 하한이다. 그래서 이탈 기한도 하나다.
     * 순번에 따라 기한이 갈라지는 쪽은 {@link PollIntervalTest}가 본다.
     */
    private Duration frontDeadline() {
        return properties.minPollInterval().plus(properties.pollGrace());
    }

    private Status status(Ticket ticket) {
        return waitingQueueService.status(ticket.date(), ticket.token());
    }

    private long waitingCount() {
        return count(QueueKeys.waiting(date(), 0));
    }

    private long pollDeadlineCount() {
        return count(QueueKeys.pollDeadline(date(), 0));
    }

    private long activeCount() {
        return count(QueueKeys.active(date(), 0));
    }

    private long count(String key) {
        Long size = redis.opsForZSet().size(key);
        return size == null ? 0 : size;
    }

    private String date() {
        return dailyWindow.at(clock.instant()).date();
    }
}
