package com.urban6.waiting.demo;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 시연용 대기열 시드의 스크립트와 설정.
 *
 * <p>이 패키지의 빈은 전부 {@link ConditionalOnDemo}가 붙어 있어 {@code queue.demo.enabled}가
 * false면 하나도 등록되지 않는다. API는 404가 되고 봇 회수 스케줄러도 돌지 않는다.
 *
 * <p>{@code @EnableScheduling}은 {@code QueueConfig}에 이미 있으므로 다시 켜지 않는다.
 */
@Configuration
@ConditionalOnDemo
@EnableConfigurationProperties(DemoProperties.class)
public class DemoConfig {

    /**
     * 이름이 곧 주입 지점이다. {@code RedisScript<Long>} 빈이 다섯이라
     * (enqueue, restamp, seed, drain, attrition) 타입만으로는 고를 수 없고,
     * 스프링이 파라미터 이름으로 갈라 준다. {@code WaitingQueueRepository}가 쓰는 방식과 같다.
     */
    @Bean
    public RedisScript<Long> demoSeedScript() {
        return script("redis/demo-seed.lua");
    }

    @Bean
    public RedisScript<Long> demoDrainScript() {
        return script("redis/demo-drain.lua");
    }

    @Bean
    public RedisScript<Long> demoAttritionScript() {
        return script("redis/demo-attrition.lua");
    }

    /** QueueConfig와 같은 방식이다. 스크립트를 클래스패스에서 읽고 반환 타입을 못 박는다. */
    private static RedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }
}
