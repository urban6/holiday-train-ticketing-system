package com.urban6.waiting.queue;

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

    public record Window(String windowId, Instant closeAt) {}
}
