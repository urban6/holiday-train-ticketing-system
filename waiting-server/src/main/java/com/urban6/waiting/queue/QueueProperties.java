package com.urban6.waiting.queue;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 입장 제어 설정. 값은 application.yml이 단일 출처다.
 *
 * <p>기본값을 코드에 두지 않는 대신, 빠뜨리거나 잘못 넣으면 기동 시점에 터지게 한다.
 * capacity가 0으로 조용히 바인딩되면 아무도 입장하지 못하는데, 그건 로그만 봐서는 알기 어렵다.
 */
@ConfigurationProperties("queue")
public record QueueProperties(
        int capacity,
        int maxBatch,
        Duration promoteInterval,
        Duration admissionGrace,
        Duration sessionTtl,
        Duration reservationTtl
) {

    public QueueProperties {
        require(capacity > 0, "queue.capacity는 1 이상이어야 합니다: " + capacity);
        require(maxBatch > 0, "queue.max-batch는 1 이상이어야 합니다: " + maxBatch);
        require(positive(promoteInterval), "queue.promote-interval이 없거나 0 이하입니다.");
        require(positive(admissionGrace), "queue.admission-grace가 없거나 0 이하입니다.");
        require(positive(sessionTtl), "queue.session-ttl이 없거나 0 이하입니다.");
        require(positive(reservationTtl), "queue.reservation-ttl이 없거나 0 이하입니다.");
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
