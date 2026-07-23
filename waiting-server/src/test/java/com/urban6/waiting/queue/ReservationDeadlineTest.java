package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
 * 로그인 후 예약 시간(queue.reservation-ttl)이 실제로 슬롯 만료로 강제되는지 확인한다.
 *
 * <p>시계를 앞으로 돌려서 검증한다. 그러지 않으면 만료 하나 확인하는 데 3분을 기다려야 하고,
 * 그런 테스트는 결국 아무도 돌리지 않는다.
 *
 * <p>Redis의 TTL(PEXPIREAT)은 여기서 돌리는 시계와 무관하게 Redis 자신의 시계를 따른다.
 * 그래서 테스트 시계는 실제 현재 시각에서 출발한다 — 과거에서 시작하면 키가 심자마자 사라지고,
 * 먼 미래에서 시작하면 창(windowId)이 실제 날짜와 어긋난다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationDeadlineTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableClockConfiguration {

        /** QueueConfig의 systemUTC 시계를 대신한다. 주입 지점이 타입 기준이라 @Primary면 충분하다. */
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
     * 창 키는 하루 단위라 테스트끼리 그대로 공유된다. 남은 항목이 다음 테스트의 순번과
     * 정원 계산에 섞이므로 매번 비운다.
     */
    @AfterEach
    void clearWindow() {
        String windowId = dailyWindow.at(clock.instant()).windowId();
        redis.delete(java.util.List.of(
                QueueKeys.waiting(windowId), QueueKeys.seq(windowId), QueueKeys.active(windowId)));
    }

    @Test
    @DisplayName("로그인하면 활성 만료가 sessionTtl에서 reservationTtl로 줄어든다")
    void startReservationShortensTheSlot() {
        Ticket ticket = admit();

        long afterClaim = activeUntil(ticket);
        assertThat(afterClaim).isEqualTo(clock.millis() + properties.sessionTtl().toMillis());

        waitingQueueService.startReservation(ticket.windowId(), ticket.token());

        assertThat(activeUntil(ticket)).isEqualTo(clock.millis() + properties.reservationTtl().toMillis());
        assertThat(activeUntil(ticket)).isLessThan(afterClaim);
    }

    @Test
    @DisplayName("예약 시간이 지나면 활성이 아니게 된다")
    void slotExpiresAfterReservationTtl() {
        Ticket ticket = admit();
        waitingQueueService.startReservation(ticket.windowId(), ticket.token());

        clock.advance(properties.reservationTtl().minusSeconds(1));
        assertThat(waitingQueueService.activeUntil(ticket.windowId(), ticket.token())).isPresent();

        clock.advance(Duration.ofSeconds(2));
        assertThat(waitingQueueService.activeUntil(ticket.windowId(), ticket.token())).isEmpty();
    }

    @Test
    @DisplayName("만료된 슬롯은 로그인으로 되살릴 수 없다")
    void startReservationCannotReviveAnExpiredSlot() {
        Ticket ticket = admit();
        waitingQueueService.startReservation(ticket.windowId(), ticket.token());
        clock.advance(properties.reservationTtl().plusSeconds(1));

        assertThatThrownBy(() -> waitingQueueService.startReservation(ticket.windowId(), ticket.token()))
                .isInstanceOf(QueueException.Expired.class);

        assertThat(waitingQueueService.activeUntil(ticket.windowId(), ticket.token())).isEmpty();
    }

    @Test
    @DisplayName("반납하면 만료를 기다리지 않고 즉시 자리가 빈다")
    void releaseFreesTheSlotImmediately() {
        Ticket ticket = admit();
        waitingQueueService.startReservation(ticket.windowId(), ticket.token());

        waitingQueueService.release(ticket.windowId(), ticket.token());

        assertThat(waitingQueueService.activeUntil(ticket.windowId(), ticket.token())).isEmpty();
        assertThat(redis.opsForZSet().size(QueueKeys.active(ticket.windowId()))).isZero();
    }

    /** 진입 → 승격 → 입장 확정. 로그인 직전까지의 상태를 만든다. */
    private Ticket admit() {
        Ticket ticket = waitingQueueService.enqueue();
        waitingQueueService.promote();
        waitingQueueService.claim(ticket.windowId(), ticket.token());
        return ticket;
    }

    private long activeUntil(Ticket ticket) {
        return waitingQueueService.activeUntil(ticket.windowId(), ticket.token()).orElseThrow();
    }

    /**
     * 앞으로만 가는 테스트 시계. {@link Clock#tick} 같은 기본 구현으로는 시간을 옮길 수 없다.
     */
    static class MutableClock extends Clock {

        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            // 이 앱은 창 계산에만 존을 쓰고 그건 DailyWindow가 따로 들고 있다.
            return this;
        }
    }
}
