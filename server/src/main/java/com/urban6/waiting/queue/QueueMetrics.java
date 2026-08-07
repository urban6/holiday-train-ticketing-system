package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>모든 지표에 {@code shard} 태그가 붙는다. 샤드 간 편차가 커지면 순번 근사(rank × 샤드 수)의
 * 전제인 균등 분배가 깨지고 있다는 뜻이라, 그 편차를 볼 수 있는 유일한 신호다.
 *
 * <p>{@code queue.promoted}는 대기열 총량 상한을 정하는 데 쓴다. 슬롯 보유 시간을 직접 잴 수
 * 없어(score가 만료 시각이라 시작 시각이 없다) 회전율로 대신한다 —
 * {@code 누적 승격 ÷ 경과 시간 × 영업시간}이 곧 입장 가능 인원이다.
 */
@Component
public class QueueMetrics {

    private final MeterRegistry registry;
    private final Map<Integer, AtomicLong> waitingByShard = new ConcurrentHashMap<>();
    private final Map<Integer, AtomicLong> activeByShard = new ConcurrentHashMap<>();

    public QueueMetrics(MeterRegistry registry, QueueProperties properties) {
        this.registry = registry;
        // 스케줄러의 첫 주기를 기다리지 않고 기동 시점에 모든 샤드의 지표를 등록해 둔다.
        // 그래야 승격이 한 번도 안 돈 상태에서도 registry.get(...)이 0을 찾아낸다.
        for (int shard = 0; shard < properties.shardCount(); shard++) {
            waitingGauge(shard);
            activeGauge(shard);
            registry.counter("queue.promoted", "shard", String.valueOf(shard));
            registry.counter("queue.swept", "shard", String.valueOf(shard));
        }
    }

    /**
     * 판매 시간 밖에서는 승격이 조기 반환이라 게이지가 0으로 읽힌다.
     * 측정은 영업시간 안에서 하므로 그대로 둔다.
     */
    public void recordPromotion(int shard, Promotion promotion) {
        waitingGauge(shard).set(promotion.waiting());
        activeGauge(shard).set(promotion.active());
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
}
