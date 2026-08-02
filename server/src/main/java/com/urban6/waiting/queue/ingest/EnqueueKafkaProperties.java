package com.urban6.waiting.queue.ingest;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kafka 경유 진입 설정. {@link com.urban6.waiting.queue.QueueProperties}와 같은 이유로 기본값을 코드에 두지 않고,
 * 빠뜨리거나 앞뒤가 맞지 않으면 기동 시점에 터지게 한다.
 *
 * <p>{@code queue.enqueue-via-kafka}가 false면 이 블록은 아예 읽히지 않는다
 * ({@link EnqueueKafkaConfig}가 조건부다) — 플래그를 끈 상태로 Kafka 없이 그냥 뜨는 것이
 * 전후 비교의 전제다.
 */
@ConfigurationProperties("queue.kafka")
public record EnqueueKafkaProperties(
        String topic,
        int partitions,
        int consumers,
        int ingestPerSecond,
        Duration ackTimeout,
        Duration staleAfter
) {

    public EnqueueKafkaProperties {
        require(topic != null && !topic.isBlank(), "queue.kafka.topic이 없습니다.");
        require(partitions > 0, "queue.kafka.partitions는 1 이상이어야 합니다: " + partitions);
        require(consumers > 0, "queue.kafka.consumers는 1 이상이어야 합니다: " + consumers);
        require(ingestPerSecond > 0,
                "queue.kafka.ingest-per-second는 1 이상이어야 합니다: " + ingestPerSecond);
        require(positive(ackTimeout), "queue.kafka.ack-timeout이 없거나 0 이하입니다.");
        require(positive(staleAfter), "queue.kafka.stale-after가 없거나 0 이하입니다.");

        // 한 파티션은 그룹 안에서 컨슈머 하나만 받는다. 컨슈머를 더 붙여도 남는 쪽은 그냥 놀아서,
        // 유입률이 올라갈 것이라 기대하고 늘렸다가 아무 변화도 못 보게 된다.
        require(consumers <= partitions,
                "queue.kafka.consumers(%d)가 queue.kafka.partitions(%d)보다 많습니다. 남는 컨슈머는 파티션을 배정받지 못해 놉니다."
                        .formatted(consumers, partitions));
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
