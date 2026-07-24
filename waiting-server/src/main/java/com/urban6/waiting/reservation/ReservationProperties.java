package com.urban6.waiting.reservation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 예약 설정. {@link com.urban6.waiting.queue.QueueProperties}와 같은 규약으로,
 * 기본값을 코드에 두지 않고 application.yml을 단일 출처로 삼는다. 빠뜨리거나 잘못 넣으면
 * 기동 시점에 터진다 — max-per-member가 0으로 조용히 바인딩되면 아무도 예약하지 못하는데,
 * 그건 로그만 봐서는 알기 어렵다.
 *
 * <p>1인당 예약 한도는 입장 제어(queue)가 아니라 예약 도메인의 규칙이라 queue 블록이 아닌
 * 여기 둔다.
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
