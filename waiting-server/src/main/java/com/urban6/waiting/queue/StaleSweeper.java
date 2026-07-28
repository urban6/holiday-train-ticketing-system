package com.urban6.waiting.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 폴링이 끊긴 대기자를 대기열에서 회수한다.
 *
 * <p>사용자가 팝업을 닫거나 탭을 닫으면 클라이언트가 이탈(leave)을 보내지만, 그 신호가 아예 오지
 * 않는 경로가 있다 — 브라우저 크래시, 기기 꺼짐, 모바일 OS의 백그라운드 탭 종료, 네트워크 단절.
 * 그쪽은 "다음 조회가 올 기한을 넘겼는가"로 판단하는 수밖에 없고, 그게 여기다.
 *
 * <p>이탈을 방치하면 순번만 부풀지 않는다. <b>유령도 순서가 되면 승격되어 아무도 쓰지 않는 활성
 * 슬롯을 admission-grace만큼 잡으므로 실효 정원이 깎인다.</b>
 *
 * <p>{@link AdmissionScheduler}와 합치지 않은 이유는 주기가 다르기 때문이다. 승격은 초당 한 번이지만
 * 회수 판정 기준에 걸린 poll-grace는 수십 초 단위라 그만큼 자주 훑을 이유가 없다. 한쪽 실패가 다른
 * 쪽 주기를 건드리지 않는 것도 나눠 둔 값이다.
 *
 * <p><b>WAS를 다중화하면 이 스케줄러도 승격과 함께 단일화해야 한다.</b> 인스턴스마다 돌면
 * 회수 주기가 인스턴스 수만큼 짧아진다. Lua가 원자적이라 같은 사람을 두 번 지우지는 않지만,
 * 한 주기의 실효 배치가 N배가 되어 maxSweep으로 막으려던 지연 스파이크가 그대로 돌아온다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaleSweeper {

    private final WaitingQueueService waitingQueueService;
    private final QueueMetrics metrics;

    @Scheduled(fixedDelayString = "${queue.sweep-interval}")
    public void sweep() {
        for (int shard = 0; shard < waitingQueueService.shardCount(); shard++) {
            sweepShard(shard);
        }
    }

    /** try가 샤드 안에 있는 이유는 승격과 같다 — 한 인스턴스의 장애가 나머지 회수를 멈추면 안 된다. */
    private void sweepShard(int shard) {
        try {
            long swept = waitingQueueService.sweepStale(shard);
            metrics.recordSweep(shard, swept);

            // 회수한 게 없을 때도 찍으면 주기마다 빈 로그가 쌓인다.
            if (swept > 0) {
                log.info("이탈 회수. shard={}, swept={}", shard, swept);
            }
        } catch (QueueException.Unavailable e) {
            // fixedDelay라 다시 던져도 다음 주기가 그대로 온다. 스택트레이스만 쌓일 뿐이다.
            // Redis가 돌아오면 저절로 재개된다 — AdmissionScheduler와 같은 취급이다.
            log.warn("이탈 회수 실패. 다음 주기에 재시도한다. shard={}: {}", shard, e.getMessage());
        }
    }
}
