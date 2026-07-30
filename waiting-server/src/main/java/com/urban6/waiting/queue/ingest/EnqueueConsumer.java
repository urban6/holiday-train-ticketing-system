package com.urban6.waiting.queue.ingest;

import com.urban6.waiting.queue.DailyWindow;
import com.urban6.waiting.queue.QueueMetrics;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka에 쌓인 진입을 정해진 속도로 Redis에 넣는다.
 *
 * <p>넣는 방법은 직접 진입과 똑같다 — {@link DirectEnqueueSink}를 그대로 부른다. 여기가 하는 일은
 * 셋뿐이다: 이미 늦은 메시지를 버리고, 창을 확인하고, {@link IngestRateLimiter}로 속도를 맞춘다.
 *
 * <p><b>WAS를 다중화하면 컨슈머 대수를 함께 봐야 한다.</b> 상한은 인스턴스 안에서만 지켜져서
 * N대면 실효 유입률도 N배다 — 근거는 application.yml의 {@code queue.kafka} 주석.
 *
 * <p>예외를 잡지 않는다. Redis 순단이면 컨테이너가 재시도하고 그 파티션은 멈춘 채 밀린다 —
 * 진입이 느려지는 것이지 유실되는 것이 아니라 의도한 방향이다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "queue.enqueue-via-kafka", havingValue = "true")
@RequiredArgsConstructor
class EnqueueConsumer {

    private final DirectEnqueueSink sink;
    private final DailyWindow dailyWindow;
    private final IngestRateLimiter rateLimiter;
    private final EnqueueKafkaProperties kafka;
    private final QueueMetrics metrics;
    private final Clock clock;

    @KafkaListener(topics = "${queue.kafka.topic}", concurrency = "${queue.kafka.consumers}")
    void consume(ConsumerRecord<String, String> record) {
        String token = record.key();
        String windowId = record.value();
        Instant now = clock.instant();

        // 이미 화면을 닫은 사람을 넣으면 폴링하지 않는 유령이 쌓이고, 스위퍼가 poll-grace를
        // 기다리는 동안 내내 뒷사람의 순번이 실제보다 뒤로 밀린다.
        if (!QueueTokens.acceptedWithin(token, now.toEpochMilli(), kafka.staleAfter().toMillis())) {
            metrics.recordEnqueueDropped();
            return;
        }

        // 진입 시점의 창만 진실이다. 지금이 그 창이 아니면 승격이 오지 않는 줄에 세우는 셈이다.
        DailyWindow.Window window = dailyWindow.at(now);
        if (!windowId.equals(window.windowId()) || !window.isOpen(now)) {
            metrics.recordEnqueueDropped();
            return;
        }

        // 버린 메시지는 Redis를 건드리지 않으므로 허가를 쓰지 않는다.
        rateLimiter.acquire();
        sink.register(window, token, now);
    }
}
