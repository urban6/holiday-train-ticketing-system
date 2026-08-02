package com.urban6.waiting.reservation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 도메인별 설정 분리. 예약은 별도 빈이 필요 없어 프로퍼티 활성화만 한다 —
 * 트랜잭션 매니저는 spring-boot-starter-jdbc가 자동 구성한다.
 */
@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfig {
}
