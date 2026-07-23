package com.urban6.waiting;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
// Testcontainers 2.x의 새 패키지다. org.testcontainers.containers 쪽은 하위 호환용으로 남아 있지만 deprecated다.
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 테스트 전용 Postgres와 Redis.
 *
 * <p>Flyway는 기동 시점에 마이그레이션을 돌리므로, DB가 없으면 컨텍스트 로드부터 실패한다.
 * Redis는 커넥션이 지연 생성이라 컨텍스트 로드만 하는 테스트는 없어도 통과하지만,
 * 대기열을 실제로 부르는 테스트는 당연히 필요하다.
 *
 * <p>{@code @ServiceConnection}이 컨테이너가 잡은 포트를 datasource 설정에 자동으로 이어 준다.
 * application.yml의 5432를 테스트에서 덮어쓸 필요가 없고, 개발용 컨테이너가 떠 있어도
 * 테스트가 그쪽 데이터를 건드리지 않는다.
 *
 * <p>이미지 태그는 docker-compose.yml과 같은 값으로 고정한다. 다르면 로컬에서는 통과하고
 * 테스트에서만 깨지는(혹은 그 반대인) 상황이 생긴다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // 2.x의 PostgreSQLContainer는 더 이상 제네릭이 아니다. <>를 붙이면 컴파일되지 않는다.
        return new PostgreSQLContainer("postgres:18.4-alpine");
    }

    /**
     * Testcontainers에 Redis 전용 모듈은 없다. GenericContainer에 포트만 열어 주고
     * 스프링의 RedisContainerConnectionDetailsFactory에 연결한다.
     *
     * <p>{@code name = "redis"}를 생략하면 안 된다. 이름이 없으면 스프링은 이미지 이름에서
     * 서비스를 추론하는데, 여기 이미지는 {@code redis:8.8.0-alpine}이라 추론에 실패하고
     * "No ConnectionDetails found"로 <b>컨텍스트 로드부터</b> 깨진다.
     */
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>("redis:8.8.0-alpine").withExposedPorts(6379);
    }
}
