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

    /** 창의 마감이 곧 이 창에 딸린 Redis 키들의 수명이라 TTL 정책을 창이 들고 있다. */
    public record Window(String date, Instant openAt, Instant closeAt) {

        /** 마감 직전 진입자가 마감 직후에 조회해도 자기 항목을 찾을 수 있게 남겨 두는 시간. */
        private static final Duration WAITING_GRACE = Duration.ofMinutes(10);

        /** 만료 체인(admission-grace + session-ttl + reservation-ttl)보다 길어야 한다. QueueProperties가 검증한다. */
        static final Duration ACTIVE_GRACE = Duration.ofHours(1);

        /** 대기열보다 먼저 사라지면 카운터가 1부터 다시 시작해 남은 항목과 순번이 겹친다. */
        private static final Duration SEQ_GRACE = Duration.ofHours(12);

        /** 개시는 포함, 마감은 제외. */
        public boolean isOpen(Instant now) {
            return !now.isBefore(openAt) && now.isBefore(closeAt);
        }

        public Instant waitingDeadline() {
            return closeAt.plus(WAITING_GRACE);
        }

        public Instant activeDeadline() {
            return closeAt.plus(ACTIVE_GRACE);
        }

        public Instant seqDeadline() {
            return closeAt.plus(SEQ_GRACE);
        }
    }
}
