package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
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
 * 정원이 통째로 비어 있어도 한 주기 승격이 max-batch를 넘지 않는지 확인한다.
 *
 * <p>Redis는 단일 스레드라 만 명을 한 번에 올리면 그동안 다른 모든 요청이 멈춘다. 상한이 빠지면
 * 정원은 그대로 지켜지고 화면도 멀쩡해서, 깨져도 부하를 걸어 보기 전까지는 드러나지 않는다.
 *
 * <p>운영값(capacity 1000 · max-batch 500)으로는 상한을 넘겨 보는 데만 501명이 필요하다.
 * 검증하려는 성질은 인원수가 아니라 "한 주기에 max-batch에서 잘린다"이므로 둘 다 작게 덮는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // 샤드 하나로 고정한다. max-batch는 한 인스턴스가 한 번에 잡히는 시간을 막는 값이라
        // 샤드당 상한인데, 샤드가 여럿이면 한 주기 총 승격이 max-batch x 샤드 수가 되어
        // "한 주기에 max-batch에서 잘린다"를 정확한 수로 단언할 수 없다.
        "queue.shard-count=1",
        "queue.capacity=100",
        "queue.max-batch=10",
        // 배경 승격이 끼어들면 테스트가 부른 주기의 결과와 섞인다. QueueLeaveTest와 같은 이유다.
        "queue.promote-interval=1h"
})
class PromoteBatchTest {

    @Autowired WaitingQueueService waitingQueueService;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired Clock clock;
    @Autowired StringRedisTemplate redis;

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 남은 항목이 다음 테스트의 순번에 섞이므로 매번 비운다. */
    @AfterEach
    void clearWindow() {
        redis.delete(List.of(
                QueueKeys.waiting(date(), 0), QueueKeys.seq(date(), 0),
                QueueKeys.active(date(), 0), QueueKeys.pollDeadline(date(), 0)));
    }

    @Test
    @DisplayName("정원이 넉넉해도 한 주기 승격은 max-batch에서 멈추고, 나머지는 다음 주기에 올라간다")
    void promotionIsCappedPerCycleNotByCapacity() {
        int maxBatch = properties.maxBatch();
        for (int i = 0; i < maxBatch * 2 + 5; i++) {
            waitingQueueService.enqueue();
        }

        // 정원(100)이 대기 인원(25)보다 크므로, 상한이 없다면 첫 주기에 전원이 올라간다.
        assertThat(waitingQueueService.promote(0).promoted()).isEqualTo(maxBatch);
        assertThat(waitingQueueService.promote(0).promoted()).isEqualTo(maxBatch);

        // 남은 인원만 올라가고 큐가 빈다. 정확한 수를 기대하지 않는 이유는 기동 직후
        // AdmissionScheduler가 한 번 도는데(fixedDelay의 첫 실행) 그 시점이 시드 도중일 수 있어서다.
        assertThat(waitingQueueService.promote(0).promoted()).isLessThan(maxBatch);
        assertThat(redis.opsForZSet().zCard(QueueKeys.waiting(date(), 0))).isZero();
    }

    private String date() {
        return dailyWindow.at(clock.instant()).date();
    }
}
