package com.urban6.waiting.queue.schedule;

import com.urban6.waiting.queue.QueueException;
import com.urban6.waiting.queue.QueueMetrics;
import com.urban6.waiting.queue.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 폴링이 끊긴 대기자를 회수한다. leave가 오지 않는 크래시·기기 꺼짐·네트워크 단절을 덮는다.
 * 방치하면 이탈자가 승격되어 아무도 쓰지 않는 슬롯을 admission-grace 동안 잡는다.
 *
 * <p>다중화 시 한 대에서만 돌리는 것은 {@link AdmissionScheduler}와 같다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class StaleSweeper {

    private final WaitingQueueService waitingQueueService;
    private final QueueMetrics metrics;

    @Scheduled(fixedDelayString = "${queue.sweep-interval}")
    public void sweep() {
        for (int shard = 0; shard < waitingQueueService.shardCount(); shard++) {
            sweepShard(shard);
        }
    }

    private void sweepShard(int shard) {
        try {
            long swept = waitingQueueService.sweepStale(shard);
            metrics.recordSweep(shard, swept);

            if (swept > 0) {
                log.info("이탈 회수. shard={}, swept={}", shard, swept);
            }
        } catch (QueueException.Unavailable e) {
            log.warn("이탈 회수 실패. 다음 주기에 재시도한다. shard={}: {}", shard, e.getMessage());
        }
    }
}
