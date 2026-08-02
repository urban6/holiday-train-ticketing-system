package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 대기열 상태를 actuator로 내보낸다.
 *
 * <p>부하 측정 중에 큐 상태를 보는 유일한 경로다. loadtest 프로파일은 로그를 warn으로 낮추고,
 * k6는 클라이언트 쪽만 본다 — "정원이 지켜지는가"는 서버 안에 있다.
 *
 * <p><b>요청 경로에는 아무것도 얹지 않는다.</b> 값은 스케줄러 주기에만 갱신되고, 그 값도
 * promote.lua가 이미 돌려주던 것이라 Redis 호출이 늘지 않는다.
 *
 * <p>{@code queue.promoted}는 대기열 총량 상한을 정하는 데 쓴다. 슬롯 보유 시간을 직접 잴 수
 * 없어(score가 만료 시각이라 시작 시각이 없다) 회전율로 대신한다 —
 * {@code 누적 승격 ÷ 경과 시간 × 영업시간}이 곧 입장 가능 인원이다.
 */
@Component
public class QueueMetrics {

    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong active = new AtomicLong();
    private final Counter promoted;
    private final Counter swept;
    private final Counter enqueueDropped;

    public QueueMetrics(MeterRegistry registry) {
        Gauge.builder("queue.waiting", waiting, AtomicLong::get)
                .description("마지막 승격 주기가 본 대기 인원")
                .register(registry);

        Gauge.builder("queue.active", active, AtomicLong::get)
                .description("마지막 승격 주기가 본 활성 인원. capacity를 넘지 않는 것이 입장 제어의 확인이다")
                .register(registry);

        promoted = Counter.builder("queue.promoted")
                .description("누적 승격 인원. 경과 시간으로 나누면 초당 처리 인원이 된다")
                .register(registry);

        swept = Counter.builder("queue.swept")
                .description("누적 유령 회수 인원. 이탈 판정이 얼마나 걷어내고 있는지 보여준다")
                .register(registry);

        enqueueDropped = Counter.builder("queue.enqueue.dropped")
                .description("Kafka 경유 진입에서 버린 메시지. 접수됐지만 대기열에 없는 사람의 수다 — 조용히 버리면 원인을 찾을 수 없다")
                .register(registry);
    }

    /**
     * 판매 시간 밖에서는 승격이 조기 반환이라 게이지가 0으로 읽힌다.
     * 측정은 영업시간 안에서 하므로 그대로 둔다.
     */
    public void recordPromotion(Promotion promotion) {
        waiting.set(promotion.waiting());
        active.set(promotion.active());
        promoted.increment(promotion.promoted());
    }

    public void recordSweep(long count) {
        swept.increment(count);
    }

    /** 컨슈머가 진입 메시지를 버렸다 — 접수된 지 너무 오래됐거나 창이 지났다. */
    public void recordEnqueueDropped() {
        enqueueDropped.increment();
    }
}
