package com.urban6.waiting.queue.ingest;

import com.urban6.waiting.queue.QueueProperties;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 경유 진입에만 필요한 것들. 플래그가 꺼져 있으면 이 설정은 통째로 뜨지 않는다 —
 * {@link EnqueueKafkaProperties} 블록도 읽히지 않고, 브로커 없이도 그냥 기동한다.
 * 도입 전 상태를 같은 jar에서 그대로 재현하는 것이 이 조건부의 목적이다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "queue.enqueue-via-kafka", havingValue = "true")
@EnableConfigurationProperties(EnqueueKafkaProperties.class)
class EnqueueKafkaConfig {

    /**
     * {@code queue.kafka}와 {@code queue}에 걸친 조건이라 어느 한쪽 레코드에서도 볼 수 없다.
     * 두 블록을 함께 받는 이곳에서 기동 시점에 검증한다.
     *
     * <p>{@code stale-after}는 조회가 PENDING을 돌려주는 창이다. 이 창이 만료보다 길면 이미 자리를
     * 잃은 사람에게 계속 "배정 중"이라고 답해서 다시 신청하라는 안내가 영원히 오지 않는다.
     * 사라지는 경로가 둘이라 비교 대상도 둘이다 — 승격 뒤 미입장, 대기 중 폴링 끊김.
     */
    EnqueueKafkaConfig(EnqueueKafkaProperties kafka, QueueProperties queue) {
        Duration sweepAt = queue.minPollInterval().plus(queue.pollGrace());

        require(kafka.staleAfter().compareTo(queue.admissionGrace()) < 0,
                "queue.kafka.stale-after(%s)가 queue.admission-grace(%s) 이상입니다. 자리를 잃은 사람이 계속 '순번 배정 중'으로 남습니다."
                        .formatted(kafka.staleAfter(), queue.admissionGrace()));

        require(kafka.staleAfter().compareTo(sweepAt) < 0,
                "queue.kafka.stale-after(%s)가 회수 시점(min-poll-interval + poll-grace = %s) 이상입니다. 회수된 대기자가 계속 '순번 배정 중'으로 남습니다."
                        .formatted(kafka.staleAfter(), sweepAt));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 진입 토픽을 여기서 만든다.
     *
     * <p>브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값이 되어 application.yml에 적은 값이
     * 조용히 무시된다. 그러면 컨슈머를 늘려도 유입률이 늘지 않는데 설정만 보면 늘었어야 한다.
     *
     * <p>복제본이 1인 것은 {@code infra/docker-compose.yml}이 단일 브로커이기 때문이다.
     */
    @Bean
    NewTopic enqueueTopic(EnqueueKafkaProperties kafka) {
        return TopicBuilder.name(kafka.topic())
                .partitions(kafka.partitions())
                .replicas(1)
                .build();
    }

    /** 컨슈머가 몇 개든 상한은 하나여야 한다. 컨슈머마다 두면 실효 유입률이 컨슈머 수만큼 곱해진다. */
    @Bean
    IngestRateLimiter ingestRateLimiter(EnqueueKafkaProperties kafka) {
        return new IngestRateLimiter(kafka.ingestPerSecond());
    }
}
