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
 * <p>부하를 재는 동안 큐가 어떤 모양인지 서버에서 확인할 방법이 없었다. loadtest 프로파일은
 * 로그를 warn으로 낮춰 승격·회수 로그마저 찍히지 않고, k6는 클라이언트 쪽(응답 시간·상태 코드)만
 * 본다. 정작 확인해야 하는 "정원이 지켜지는가"는 서버 안에 있다.
 *
 * <p><b>요청 경로에는 아무것도 얹지 않는다.</b> 값은 스케줄러 주기(승격 1초 · 회수 5초)에만
 * 갱신되고, 그 값도 promote.lua가 이미 돌려주던 것이라 Redis 호출이 늘지 않는다.
 * 요청마다 로그를 켜면 큐가 아니라 로거를 측정하게 되는 것과 같은 이유다.
 *
 * <p>{@code queue.promoted}는 대기열 총량 상한을 정하는 데 쓰는 값이다. 상한을 유도하려면
 * 슬롯 보유 시간이 필요한데, active ZSet의 score는 만료 시각이라 시작 시각이 없어 직접 잴 수 없다.
 * 대신 회전율을 센다 — {@code 누적 승격 ÷ 경과 시간 × 영업시간}이 곧 입장 가능 인원이다.
 */
@Component
public class QueueMetrics {

    private final AtomicLong waiting = new AtomicLong();
    private final AtomicLong active = new AtomicLong();
    private final Counter promoted;
    private final Counter swept;

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
    }

    /**
     * 판매 시간 밖에서는 승격이 조기 반환이라({@link WaitingQueueService#promote()})
     * 게이지가 0으로 읽힌다. 측정은 영업시간 안에서 하므로 그대로 둔다 —
     * 이걸 구분하자고 서비스가 돌려주는 형태를 바꾸지는 않는다.
     */
    public void recordPromotion(Promotion promotion) {
        waiting.set(promotion.waiting());
        active.set(promotion.active());
        promoted.increment(promotion.promoted());
    }

    public void recordSweep(long count) {
        swept.increment(count);
    }
}
