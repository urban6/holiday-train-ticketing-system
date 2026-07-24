package com.urban6.waiting.reservation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@link com.urban6.waiting.queue.QueueConfig}처럼 도메인별로 설정을 분리한다.
 * 예약은 별도 빈이 필요 없어 프로퍼티 활성화만 한다 — @Transactional의 트랜잭션 매니저는
 * spring-boot-starter-jdbc가 자동 구성하므로 여기서 등록할 것이 없다.
 */
@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfig {
}
