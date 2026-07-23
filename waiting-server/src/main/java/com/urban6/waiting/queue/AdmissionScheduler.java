package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 활성 정원에 빈 자리가 생기면 대기열 앞에서 채운다.
 *
 * <p>승격을 조회 요청이 스스로 하게 만들 수도 있었다. 그러면 스케줄러도 리더 선출도 필요 없고
 * 폴링하지 않는 유령이 슬롯을 먹지도 않는다. 대신 조회 경로가 읽기 전용이 아니게 되는데,
 * 만 명이 2초마다 폴링하면 약 5,000 rps라 거기에 쓰기를 섞는 대가가 크다.
 * 그래서 승격은 초당 한 번, 여기 한 곳에서만 일어난다.
 *
 * <p><b>WAS를 다중화하면 이 스케줄러를 단일화해야 한다.</b> 인스턴스마다 돌면 승격 주기가
 * 인스턴스 수만큼 짧아진다. Lua가 원자적이라 정원을 넘기지는 않지만, 한 주기의 실효 배치가
 * N배가 되어 maxBatch로 막으려던 지연 스파이크가 그대로 돌아온다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdmissionScheduler {

    private final WaitingQueueService waitingQueueService;

    @Scheduled(fixedDelayString = "${queue.promote-interval}")
    public void promote() {
        try {
            Promotion result = waitingQueueService.promote();

            // 올린 게 없을 때도 찍으면 초당 한 줄씩 빈 로그가 쌓인다.
            if (result.promoted() > 0) {
                log.info("입장 승격. promoted={}, active={}, waiting={}",
                        result.promoted(), result.active(), result.waiting());
            }
        } catch (QueueException.Unavailable e) {
            // 다시 던져도 fixedDelay는 다음 주기에 그대로 돌아온다. 스택트레이스만 초당 한 번 쌓일 뿐이다.
            // Redis가 돌아오면 다음 주기에 저절로 재개된다.
            log.warn("입장 승격 실패. 다음 주기에 재시도한다: {}", e.getMessage());
        }
    }
}
