package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 대기열 상태를 actuator로 내보낸다. 요청 경로에는 얹지 않고 스케줄러 주기에만 갱신한다.
 *
 * <p>{@code shard} 태그로 샤드 간 편차를 본다. 편차가 커지면 순번 근사의 전제인 균등 분배가 깨진 것이다.
 */
@Component
public class QueueMetrics {

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<Integer, AtomicLong> waitingByShard = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> activeByShard = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> lastPromoteByShard = new ConcurrentHashMap<>();

    public QueueMetrics(MeterRegistry registry, QueueProperties properties, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        // 승격이 한 번도 돌지 않았어도 지표가 0으로 보이게 기동 시점에 등록한다.
        for (int shard = 0; shard < properties.shardCount(); shard++) {
            waitingGauge(shard);
            activeGauge(shard);
            lastPromoteGauge(shard);
            registry.counter("queue.promoted", "shard", String.valueOf(shard));
            registry.counter("queue.swept", "shard", String.valueOf(shard));
        }
    }

    /** 판매 시간 밖에서는 승격이 조기 반환이라 게이지가 0으로 읽힌다. */
    public void recordPromotion(int shard, Promotion promotion) {
        waitingGauge(shard).set(promotion.waiting());
        activeGauge(shard).set(promotion.active());
        lastPromoteGauge(shard).set(clock.millis());
        registry.counter("queue.promoted", "shard", String.valueOf(shard)).increment(promotion.promoted());
    }

    public void recordSweep(int shard, long count) {
        registry.counter("queue.swept", "shard", String.valueOf(shard)).increment(count);
    }

    private AtomicLong waitingGauge(int shard) {
        return waitingByShard.computeIfAbsent(shard, s -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("queue.waiting", value, AtomicLong::get)
                    .tag("shard", String.valueOf(s))
                    .description("마지막 승격 주기가 본 대기 인원")
                    .register(registry);
            return value;
        });
    }

    private AtomicLong activeGauge(int shard) {
        return activeByShard.computeIfAbsent(shard, s -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("queue.active", value, AtomicLong::get)
                    .tag("shard", String.valueOf(s))
                    .description("마지막 승격 주기가 본 활성 인원. 샤드별 capacity를 넘지 않는 것이 입장 제어의 확인이다")
                    .register(registry);
            return value;
        });
    }

    /**
     * 승격 인원이 0이어도 갱신한다. 정원이 차 있으면 0이 정상이라, 인원으로는 스케줄러가 멈춘 것과
     * 구분할 수 없다. 모든 WAS가 승격하므로 모든 대의 값 중 최댓값이 멈춰 있으면 경보를 건다.
     */
    private AtomicLong lastPromoteGauge(int shard) {
        return lastPromoteByShard.computeIfAbsent(shard, s -> {
            AtomicLong millis = new AtomicLong();
            Gauge.builder("queue.promote.last-run", millis, m -> m.get() / 1000.0)
                    .tag("shard", String.valueOf(s))
                    .baseUnit("seconds")
                    .description("이 WAS에서 승격 주기가 마지막으로 성공한 시각(epoch 초)")
                    .register(registry);
            return millis;
        });
    }
}
