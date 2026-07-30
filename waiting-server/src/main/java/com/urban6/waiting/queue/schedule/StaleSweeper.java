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
 * 폴링이 끊긴 대기자를 대기열에서 회수한다.
 *
 * <p>이탈 신호(leave)가 아예 오지 않는 경로 — 브라우저 크래시, 기기 꺼짐, 백그라운드 탭 종료,
 * 네트워크 단절 — 를 덮는 유일한 수단이다. 판정은 "다음 조회가 올 기한을 넘겼는가"로 한다.
 *
 * <p>방치하면 순번만 부풀지 않는다. <b>유령도 순서가 되면 승격되어 아무도 쓰지 않는 활성 슬롯을
 * admission-grace만큼 잡으므로 실효 정원이 깎인다.</b>
 *
 * <p>{@link AdmissionScheduler}와 합치지 않은 이유는 주기가 다르기 때문이다 — poll-grace가
 * 수십 초 단위라 승격만큼 자주 훑을 이유가 없다. WAS 다중화 시 단일화가 필요한 것은 같고,
 * {@code queue.scheduler-enabled}를 함께 쓴다.
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
        try {
            long swept = waitingQueueService.sweepStale();
            metrics.recordSweep(swept);

            // 회수한 게 없을 때도 찍으면 주기마다 빈 로그가 쌓인다.
            if (swept > 0) {
                log.info("이탈 회수. swept={}", swept);
            }
        } catch (QueueException.Unavailable e) {
            // AdmissionScheduler와 같은 취급이다.
            log.warn("이탈 회수 실패. 다음 주기에 재시도한다: {}", e.getMessage());
        }
    }
}
