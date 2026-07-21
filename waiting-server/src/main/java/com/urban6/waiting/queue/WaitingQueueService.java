package com.urban6.waiting.queue;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {

    private static final Duration WAITING_GRACE = Duration.ofHours(1);
    private static final Duration SEQ_GRACE = Duration.ofHours(12);

    private final WaitingQueueRepository repository;
    private final DailyWindow dailyWindow;
    private final Clock clock;

    public Ticket enqueue() {
        DailyWindow.Window window = dailyWindow.at(clock.instant());

        String uuid = UUID.randomUUID().toString();

        long seq = repository.enqueue(window.windowId(), uuid,
                window.closeAt().plus(WAITING_GRACE),
                window.closeAt().plus(SEQ_GRACE));

        // log.debug("대기열 진입. window={}, seq={}", window.windowId(), seq);
        return new Ticket(uuid, window.windowId(), seq);
    }

    public record Ticket(String token, String windowId, long seq) {}
}
