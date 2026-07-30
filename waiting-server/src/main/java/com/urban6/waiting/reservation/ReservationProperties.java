package com.urban6.waiting.reservation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 설정. {@link com.urban6.waiting.queue.QueueProperties}와 같은 규약으로 기본값을 코드에
 * 두지 않고, 빠뜨리면 기동 시점에 터진다 — 0으로 조용히 바인딩되면 아무도 예약하지 못한다.
 */
@ConfigurationProperties("reservation")
public record ReservationProperties(int maxPerMember) {

    public ReservationProperties {
        if (maxPerMember <= 0) {
            throw new IllegalArgumentException(
                    "reservation.max-per-member는 1 이상이어야 합니다: " + maxPerMember);
        }
    }
}
