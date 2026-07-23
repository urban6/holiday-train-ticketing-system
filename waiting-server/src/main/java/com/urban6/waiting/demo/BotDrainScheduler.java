package com.urban6.waiting.demo;

import com.urban6.waiting.queue.DailyWindow;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승격된 봇의 활성 슬롯을 즉시 반납한다.
 *
 * <p>봇도 진짜 사용자와 똑같이 {@code promote.lua}를 거쳐 활성으로 올라간다. 그래야 대기열이
 * 실제 승격 속도(max-batch)로 빠지고 순번이 줄어드는 모습이 사실과 같아진다. 문제는 그 다음이다 —
 * 봇은 폴링도 claim도 하지 않으므로 아무도 쓰지 않는 슬롯을 admission-grace(150초)만큼 붙잡는다.
 * README의 <b>유령 항목</b> 문제 그 자체이고, 5,000명을 시드하면 정원이 봇으로 차서
 * 진짜 사용자가 20분 넘게 못 들어온다.
 *
 * <p>그래서 올라오는 즉시 빼 준다. 로그아웃이 슬롯을 반납하는 것과 같은 동작이라
 * "입장하자마자 나가는 사용자"로 읽으면 된다.
 *
 * <p><b>주기가 promote-interval(1초)보다 짧은 이유.</b> 승격 직후 봇이 정원을 채운 상태로
 * 다음 승격이 돌면 {@code room <= 0}이라 그 주기가 통째로 헛돈다. 대기열이 빠지는 속도가
 * 절반으로 떨어져 시연이 답답해진다. 그 사이에 확실히 비우려면 승격보다 촘촘해야 한다.
 */
@Slf4j
@Component
@ConditionalOnDemo
@RequiredArgsConstructor
public class BotDrainScheduler {

    private final QueueSeeder seeder;
    private final DailyWindow dailyWindow;
    private final Clock clock;

    @Scheduled(fixedDelay = 200)
    public void drain() {
        String windowId = dailyWindow.at(clock.instant()).windowId();

        try {
            long removed = seeder.drain(windowId);

            // 시드하지 않은 동안에는 항상 0이다. 그대로 찍으면 초당 다섯 줄씩 빈 로그가 쌓인다.
            if (removed > 0) {
                log.debug("봇 슬롯 회수. window={}, removed={}", windowId, removed);
            }
        } catch (DataAccessException e) {
            // Redis가 돌아오면 다음 주기에 저절로 재개된다. AdmissionScheduler와 같은 태도다.
            log.warn("봇 슬롯 회수 실패. 다음 주기에 재시도한다: {}", e.getMessage());
        }
    }
}
