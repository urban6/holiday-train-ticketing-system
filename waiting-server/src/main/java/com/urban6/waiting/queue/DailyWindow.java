package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

public class DailyWindow {

    private final ZoneId zone;
    private final LocalTime open;
    private final LocalTime close;

    public DailyWindow(ZoneId zone, LocalTime open, LocalTime close) {
        this.zone = Objects.requireNonNull(zone);
        this.open = Objects.requireNonNull(open);
        this.close = Objects.requireNonNull(close);
    }

    public Window at(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        return new Window(
                today.format(DateTimeFormatter.BASIC_ISO_DATE),
                today.atTime(open).atZone(zone).toInstant(),
                today.atTime(close).atZone(zone).toInstant());
    }

    /**
     * 창의 마감이 곧 이 창에 딸린 Redis 키들의 수명이다. 그래서 TTL 정책을 창이 들고 있다.
     * 키를 심는 쪽이 여럿(진입·승격)이라, 각자 유예 시간을 더하게 두면
     * 같은 키에 서로 다른 TTL이 찍히는 순간을 아무도 눈치채지 못한다.
     */
    public record Window(String windowId, Instant openAt, Instant closeAt) {

        /**
         * 대기 키를 마감 뒤에도 남겨 두는 시간.
         * 마감 직전에 진입한 사용자가 마감 직후에 조회해도 자기 항목을 찾을 수 있어야 한다.
         *
         * <p>대기 ZSet은 상한이 없어, 이 시간이 곧 이전 창과 새 창의 대기 키가 함께 메모리에
         * 있는 중복 구간이다. maxmemory가 데이터 크기와 같은 자릿수라 짧게 잡는다.
         */
        private static final Duration WAITING_GRACE = Duration.ofMinutes(10);

        /**
         * 활성 키를 마감 뒤에도 남겨 두는 시간. 대기와 나눈 이유는 제약이 정반대이기 때문이다.
         *
         * <p>활성 ZSet은 queue.capacity가 상한이라 오래 남겨도 메모리 비용이 없다. 대신
         * 만료 체인(admission-grace + session-ttl + reservation-ttl)보다 반드시 길어야 한다 —
         * 멤버 score는 승격·claim·로그인으로 계속 밀리는데, 그 값이 키 TTL을 넘으면 개별 만료가
         * 아니라 키가 통째로 사라져 활성 사용자 전원이 동시에 슬롯을 잃는다.
         * {@link QueueProperties}가 기동 시점에 이 조건을 검증한다.
         */
        static final Duration ACTIVE_GRACE = Duration.ofHours(1);

        /**
         * 순번 카운터는 대기열보다 오래 남긴다.
         * 먼저 사라지면 카운터가 1부터 다시 시작해 남아 있는 항목과 순번이 겹친다.
         */
        private static final Duration SEQ_GRACE = Duration.ofHours(12);

        /** 지금이 판매 시간 안인가. 개시는 포함, 마감은 제외다. */
        public boolean isOpen(Instant now) {
            return !now.isBefore(openAt) && now.isBefore(closeAt);
        }

        /** waiting ZSet의 만료 시각. */
        public Instant waitingDeadline() {
            return closeAt.plus(WAITING_GRACE);
        }

        /** active ZSet의 만료 시각. 만료 체인보다 길다 — {@link QueueProperties}가 검증한다. */
        public Instant activeDeadline() {
            return closeAt.plus(ACTIVE_GRACE);
        }

        /** seq 카운터의 만료 시각. 항상 {@link #waitingDeadline()}보다 늦다. */
        public Instant seqDeadline() {
            return closeAt.plus(SEQ_GRACE);
        }
    }
}
