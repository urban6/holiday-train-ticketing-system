package com.urban6.waiting.demo;

import com.urban6.waiting.queue.DailyWindow;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대기 중인 봇을 조금씩 이탈시켜 순번이 줄어드는 폭을 흔든다.
 *
 * <p>승격 인원은 {@code promote.lua}가 {@code min(capacity - active, maxBatch)}로 정하는데,
 * {@link BotDrainScheduler}가 활성을 계속 비워 주므로 매 주기 정확히 {@code maxBatch}가 된다.
 * 그래서 시드만으로는 순번이 초당 정확히 500씩 줄어 기계처럼 보인다.
 *
 * <p>승격 인원 계산은 프로덕션 경로라 건드리지 않는다. 대신 봇이 기다리다 포기하고
 * 나가게 해서 감소 폭에 흔들림을 준다 — 실제 대기열에서도 일어나는 일이고,
 * 다음 페이즈의 <b>대기열 이탈 처리</b>를 미리 흉내 내는 셈이기도 하다.
 *
 * <p><b>{@link BotDrainScheduler}와 합치지 않는 이유.</b> 그쪽은 200ms 주기여야 하고
 * (승격보다 촘촘해야 정원이 비워진다) 목적도 "정원 회수"로 다르다. 주기가 다른 두 일을
 * 한 메서드에 두면 둘 중 하나는 반드시 잘못된 주기로 돈다.
 */
@Slf4j
@Component
@ConditionalOnDemo
@RequiredArgsConstructor
public class BotAttritionScheduler {

    private final QueueSeeder seeder;
    private final DemoProperties properties;
    private final DailyWindow dailyWindow;
    private final Clock clock;

    @Scheduled(fixedDelay = 1000)
    public void leave() {
        // 0이면 이탈이 꺼진 것이다. 스크립트를 부르지 않고 바로 빠진다.
        if (properties.maxAttrition() == 0) {
            return;
        }

        // 무작위 값은 여기서 만든다. Lua의 math.random은 Redis가 실행마다 같은 시드를 주는
        // 경우가 있어, 스크립트 안에서 뽑으면 매 주기 같은 수가 나올 수 있다.
        int count = ThreadLocalRandom.current().nextInt(properties.maxAttrition() + 1);
        if (count == 0) {
            return;
        }

        String windowId = dailyWindow.at(clock.instant()).windowId();

        try {
            long removed = seeder.attrition(windowId, count);

            // 시드하지 않은 동안에는 항상 0이다. 그대로 찍으면 초당 한 줄씩 빈 로그가 쌓인다.
            if (removed > 0) {
                log.debug("봇 이탈. window={}, removed={}", windowId, removed);
            }
        } catch (DataAccessException e) {
            // 이탈은 시연용 장식이라 실패해도 대기열 자체는 멀쩡하다. 다음 주기에 재시도한다.
            log.warn("봇 이탈 실패. 다음 주기에 재시도한다: {}", e.getMessage());
        }
    }
}
