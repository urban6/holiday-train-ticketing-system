package com.urban6.waiting.demo;

import com.urban6.waiting.demo.QueueSeeder.Counts;
import com.urban6.waiting.queue.DailyWindow;
import com.urban6.waiting.queue.DailyWindow.Window;
import com.urban6.waiting.queue.QueueException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시연용 대기열 조작 API. 랜딩의 {@code /?demo} 패널이 유일한 호출자다.
 *
 * <p>{@link ConditionalOnDemo}가 없으면 설정을 꺼도 이 컨트롤러만 살아남아
 * 대기열을 조작할 수 있게 된다. 스위치가 API 쪽에서 새는 곳이 정확히 여기다.
 */
@Slf4j
@RestController
@ConditionalOnDemo
@RequestMapping("/api/v1/demo/queue")
@RequiredArgsConstructor
public class DemoController {

    private final QueueSeeder seeder;
    private final DemoProperties properties;
    private final DailyWindow dailyWindow;
    private final Clock clock;

    /** 패널이 열릴 때 현재 상태를 그리기 위해 부른다. */
    @GetMapping
    public Counts counts() {
        return seeder.counts(window().windowId());
    }

    /** 대기열 뒤에 봇 {@code count}명을 붙인다. */
    @PostMapping
    public Counts fill(@RequestParam int count) {
        if (count < 1 || count > properties.maxCount()) {
            throw new QueueException.InvalidRequest(
                    "인원은 1 이상 %d 이하여야 합니다: %d".formatted(properties.maxCount(), count));
        }

        Window window = window();
        seeder.seed(window.windowId(), count, window.waitingDeadline(), window.seqDeadline());

        log.info("시연용 대기열 시드. window={}, count={}", window.windowId(), count);
        return seeder.counts(window.windowId());
    }

    /** 창을 통째로 비운다. 진행 중이던 실사용자도 함께 끊긴다. */
    @DeleteMapping
    public Counts clear() {
        String windowId = window().windowId();
        seeder.clear(windowId);

        log.info("시연용 대기열 초기화. window={}", windowId);
        return seeder.counts(windowId);
    }

    private Window window() {
        return dailyWindow.at(clock.instant());
    }
}
