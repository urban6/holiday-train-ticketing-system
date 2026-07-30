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
 * <p>조회 요청이 스스로 승격하게 만들 수도 있었지만, 만 명이 2초마다 폴링하면 약 5,000 rps라
 * 읽기 전용 경로에 쓰기를 섞는 대가가 크다. 그래서 승격은 초당 한 번, 여기서만 일어난다.
 *
 * <p><b>WAS를 다중화하면 {@code queue.scheduler-enabled}로 단일화해야 한다.</b> 인스턴스마다
 * 돌면 한 주기의 실효 배치가 N배가 되어 maxBatch로 막으려던 지연 스파이크가 돌아온다.
 * 근거는 application.yml의 같은 키 주석.
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
        try {
            Promotion result = waitingQueueService.promote();

            // 지표는 올린 게 없어도 남긴다. 대기·활성 인원은 승격이 멈춘 구간에서도 읽혀야 한다.
            metrics.recordPromotion(result);

            // 올린 게 없을 때도 찍으면 초당 한 줄씩 빈 로그가 쌓인다.
            if (result.promoted() > 0) {
                log.info("입장 승격. promoted={}, active={}, waiting={}",
                        result.promoted(), result.active(), result.waiting());
            }
        } catch (QueueException.Unavailable e) {
            // 다시 던져도 fixedDelay는 다음 주기에 그대로 온다. 스택트레이스만 초당 한 번 쌓인다.
            log.warn("입장 승격 실패. 다음 주기에 재시도한다: {}", e.getMessage());
        }
    }
}
