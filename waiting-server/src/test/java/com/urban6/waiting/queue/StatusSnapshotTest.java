package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.WaitingQueueService.State;
import com.urban6.waiting.queue.WaitingQueueService.Status;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 순번과 전체 인원을 한 스냅샷에서 읽는지 확인한다.
 *
 * <p>{@code status.lua}가 ZCARD와 ZRANK를 Lua로 묶은 이유가 이것인데, 지금까지 그 주장을
 * 검증하는 코드가 없었다. {@code k6/status.js}가 {@code 앞 + 뒤 + 1 = 전체}를 검사하고 있었지만
 * 서버가 {@code 내 뒤}를 {@code 전체 - 내 앞 - 1}로 계산하므로(WaitingQueueService)
 * 그 합은 <b>값이 찢어져 있어도 항상 맞는 산술 항등식</b>이었다.
 *
 * <p>그래서 여기서는 비원자 버전을 <b>일부러 재현해</b> 대조한다. 타이밍에 기대면 CI에서 흔들리므로
 * 두 읽기 사이에 승격을 직접 끼워 넣어 결정적으로 만든다 — 실제 부하에서 우연히 벌어지는 일을
 * 손으로 재현하는 셈이다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class StatusSnapshotTest {

    @Autowired WaitingQueueService waitingQueueService;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired Clock clock;
    @Autowired StringRedisTemplate redis;

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 남은 항목이 다음 테스트의 순번에 섞이므로 매번 비운다. */
    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(windowId()), QueueKeys.seq(windowId()), QueueKeys.active(windowId())));
    }

    @Test
    @DisplayName("따로 읽으면 순번이 전체 인원을 넘어선다 — Lua로 묶은 이유")
    void splitReadTearsTheSnapshot() {
        Ticket last = fillQueue();
        String waiting = QueueKeys.waiting(windowId());

        // 비원자 버전: ZRANK를 먼저 읽고 ZCARD를 나중에 읽는다.
        Long rank = redis.opsForZSet().rank(waiting, last.token());
        waitingQueueService.promote();                       // 그 사이에 앞에서 max-batch만큼 빠진다
        Long total = redis.opsForZSet().zCard(waiting);

        assertThat(rank).isNotNull();
        assertThat(total).isNotNull();

        // 같은 순간의 값이라면 rank는 언제나 [0, total-1]에 있어야 하는데, 찢어져서 벗어났다.
        assertThat(rank).isGreaterThanOrEqualTo(total);

        // 그 결과 파생값인 '내 뒤'가 음수가 된다 — 있을 수 없는 값이다.
        long behind = total - rank - 1;
        assertThat(behind).isNegative();

        // 그런데도 '앞 + 뒤 + 1 = 전체'는 그대로 성립한다.
        // behind가 두 값에서 파생되므로 정보가 없기 때문이다. k6 검사가 헛돌던 이유가 이것이다.
        assertThat(rank + behind + 1).isEqualTo(total);
    }

    @Test
    @DisplayName("status.lua는 한 호출이라 승격을 앞뒤로 섞어도 순번이 범위를 벗어나지 않는다")
    void atomicReadKeepsRankWithinTotal() {
        Ticket last = fillQueue();

        // 승격을 사이사이 끼워 넣어도 한 번의 스크립트 호출 안으로는 끼어들 수 없다.
        for (int i = 0; i < 10; i++) {
            Status status = waitingQueueService.status(last.windowId(), last.token());
            if (status.state() != State.WAITING) {
                break;                                       // 내 차례가 와서 입장했다
            }

            assertThat(status.ahead()).isBetween(0L, status.total() - 1);
            assertThat(status.behind()).isNotNegative();
            assertThat(status.position()).isEqualTo(status.ahead() + 1);

            waitingQueueService.promote();
        }
    }

    /**
     * max-batch보다 조금 많게 채우고 맨 뒤 토큰을 돌려준다.
     *
     * <p>한 번의 승격으로 앞에서 max-batch가 빠져도 이 토큰은 큐에 남아야 판정이 성립한다.
     * 인원을 상수로 박지 않는 이유는 application.yml의 튜닝값이 바뀌어도 테스트가 따라가게 하기 위해서다.
     */
    private Ticket fillQueue() {
        Ticket last = null;
        for (int i = 0; i < properties.maxBatch() + 10; i++) {
            last = waitingQueueService.enqueue();
        }
        return last;
    }

    private String windowId() {
        return dailyWindow.at(clock.instant()).windowId();
    }
}
