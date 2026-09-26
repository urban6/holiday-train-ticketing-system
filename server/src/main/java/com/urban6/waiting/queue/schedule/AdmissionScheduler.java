package com.urban6.waiting.queue.schedule;

import com.urban6.waiting.queue.QueueException;
import com.urban6.waiting.queue.QueueMetrics;
import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 활성 정원에 빈 자리가 생기면 대기열 앞에서 채운다.
 *
 * <p>WAS를 다중화하면 {@code queue.scheduler-enabled}로 한 대에서만 돌린다.
 * 여러 대가 돌면 실효 배치가 대수만큼 커진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "queue.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionScheduler {

    private final WaitingQueueService waitingQueueService;
    private final QueueMetrics metrics;

    @Scheduled(fixedDelayString = "${queue.promote-interval}")
    public void promote() {
        for (int shard = 0; shard < waitingQueueService.shardCount(); shard++) {
            promoteShard(shard);
        }
    }

    // try를 샤드 안에 둬서 노드 하나의 장애가 나머지 샤드의 승격을 멈추지 않게 한다.
    private void promoteShard(int shard) {
        try {
            Promotion result = waitingQueueService.promote(shard);
            metrics.recordPromotion(shard, result);

            if (result.promoted() > 0) {
                log.info("입장 승격. shard={}, promoted={}, active={}, waiting={}",
                        shard, result.promoted(), result.active(), result.waiting());
            }
        } catch (QueueException.Unavailable e) {
            log.warn("입장 승격 실패. 다음 주기에 재시도한다. shard={}: {}", shard, e.getMessage());
        }
    }
}
