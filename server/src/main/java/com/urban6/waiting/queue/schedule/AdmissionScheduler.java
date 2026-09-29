package com.urban6.waiting.queue.schedule;

import com.urban6.waiting.queue.QueueException;
import com.urban6.waiting.queue.QueueMetrics;
import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 활성 정원에 빈 자리가 생기면 대기열 앞에서 채운다.
 *
 * <p>모든 WAS에서 돈다. promote.lua가 세기와 꺼내기를 한 스크립트로 해서 여러 대가 같은 샤드를
 * 동시에 불러도 정원을 넘지 않는다. 한 대로 줄이면 그 대가 죽는 순간 입장이 전부 멈춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
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
