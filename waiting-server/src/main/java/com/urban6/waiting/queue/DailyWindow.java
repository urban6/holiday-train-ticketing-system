package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;

public class DailyWindow {

    private final ZoneId zone;

    public DailyWindow(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone);
    }

    public Window at(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        Instant closeAt = today.plusDays(1).atStartOfDay(zone).toInstant();
        return new Window(today.format(DateTimeFormatter.BASIC_ISO_DATE), closeAt);
    }

    /**
     * 창의 마감이 곧 이 창에 딸린 Redis 키들의 수명이다. 그래서 TTL 정책을 창이 들고 있다.
     * 키를 심는 쪽이 여럿(진입·승격·시연용 시드)이라, 각자 유예 시간을 더하게 두면
     * 같은 키에 서로 다른 TTL이 찍히는 순간을 아무도 눈치채지 못한다.
     */
    public record Window(String windowId, Instant closeAt) {

        /**
         * 창이 닫힌 뒤에도 대기·활성 키를 남겨 두는 시간.
         * 자정 직전에 진입한 사용자가 자정 직후에 조회해도 자기 항목을 찾을 수 있어야 한다.
         */
        private static final Duration WAITING_GRACE = Duration.ofHours(1);

        /**
         * 순번 카운터는 대기열보다 오래 남긴다.
         * 먼저 사라지면 카운터가 1부터 다시 시작해 남아 있는 항목과 순번이 겹친다.
         */
        private static final Duration SEQ_GRACE = Duration.ofHours(12);

        /** waiting·active ZSet의 만료 시각. */
        public Instant waitingDeadline() {
            return closeAt.plus(WAITING_GRACE);
        }

        /** seq 카운터의 만료 시각. 항상 {@link #waitingDeadline()}보다 늦다. */
        public Instant seqDeadline() {
            return closeAt.plus(SEQ_GRACE);
        }
    }
}
