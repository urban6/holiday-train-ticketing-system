package com.urban6.waiting.queue.ingest;

import com.urban6.waiting.queue.DailyWindow.Window;
import com.urban6.waiting.queue.QueueException;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 진입을 Kafka에 넘긴다. Redis는 건드리지 않는다.
 *
 * <p>메시지는 key=토큰, value=windowId인 문자열 두 개다. 진입 경로가 곧 측정 대상이라 JSON을
 * 한 겹 얹지 않았고, 접수 시각은 이미 토큰에 실려 있다({@link QueueTokens}).
 *
 * <p>key가 토큰인 이유는 파티션을 고르게 쓰기 위해서다. windowId를 key로 두면 하루치가 한
 * 파티션으로 몰려 컨슈머를 늘려도 유입률이 늘지 않는다.
 *
 * <p>windowId를 담는 이유는 자정을 넘겨 소비되는 메시지 때문이다. 컨슈머가 자기 시계로 창을
 * 구하면 마감 직전에 접수된 사람이 다음 창에 들어간다 — 진실은 접수 시점의 창이다.
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "queue.enqueue-via-kafka", havingValue = "true")
@RequiredArgsConstructor
class KafkaEnqueueSink implements EnqueueSink {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EnqueueKafkaProperties kafka;

    /**
     * produce가 끝날 때까지 기다린다. 기다리지 않으면 브로커가 받지 못한 요청에도 202가 나가
     * 대기열에 영원히 없는 사람이 조용히 생긴다.
     *
     * <p>실패는 Redis가 죽었을 때와 같은 503으로 옮긴다 — 어느 쪽이 막혔든 사용자에게는
     * "지금 줄을 설 수 없다" 하나다.
     */
    @Override
    public Accepted submit(Window window, Instant now) {
        String token = QueueTokens.stamped(QueueTokens.issue(), now.toEpochMilli());

        try {
            kafkaTemplate.send(kafka.topic(), token, window.windowId())
                    .get(kafka.ackTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueueException.Unavailable("대기열이 일시적으로 불가합니다.", e);
        } catch (ExecutionException | TimeoutException e) {
            log.error("대기열 진입 실패 - Kafka 전송 오류. window={}", window.windowId(), e);
            throw new QueueException.Unavailable("대기열이 일시적으로 불가합니다.", e);
        }

        return new Accepted(token, SEQ_PENDING);
    }

    /** 컨슈머의 폐기 판정과 같은 값을 본다 — 근거는 {@link QueueTokens#acceptedWithin}. */
    @Override
    public boolean maybePending(String token, Instant now) {
        return QueueTokens.acceptedWithin(token, now.toEpochMilli(), kafka.staleAfter().toMillis());
    }
}
