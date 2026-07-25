package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 입장 제어 설정. 값은 application.yml이 단일 출처다.
 *
 * <p>기본값을 코드에 두지 않는 대신, 빠뜨리거나 잘못 넣으면 기동 시점에 터지게 한다.
 * capacity가 0으로 조용히 바인딩되면 아무도 입장하지 못하는데, 그건 로그만 봐서는 알기 어렵다.
 */
@ConfigurationProperties("queue")
public record QueueProperties(
        LocalTime open,
        LocalTime close,
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

        require(open != null && close != null, "queue.open / queue.close가 없습니다.");
        require(open.isBefore(close),
                "queue.open이 queue.close보다 앞이어야 합니다: %s ~ %s".formatted(open, close));

        // active 키의 TTL은 마감 + ACTIVE_GRACE인데, 멤버 score는 승격·claim·로그인으로 계속 밀린다.
        // score가 키 TTL을 넘으면 개별 만료가 아니라 키가 통째로 사라져 활성 사용자 전원이
        // 동시에 슬롯을 잃는다. 자정이나 마감 근처에만 재현되고 로그에 아무것도 남지 않으므로
        // 값을 잘못 넣은 사람이 그때를 기다리지 않고 기동 시점에 알게 한다.
        Duration chain = admissionGrace.plus(sessionTtl).plus(reservationTtl);
        require(chain.compareTo(DailyWindow.Window.ACTIVE_GRACE) < 0,
                "queue의 만료 체인(%s)이 활성 키 유예(%s) 이상입니다. active 키가 먼저 사라져 활성 사용자가 동시에 증발합니다."
                        .formatted(chain, DailyWindow.Window.ACTIVE_GRACE));
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
