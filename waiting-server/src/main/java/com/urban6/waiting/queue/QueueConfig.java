package com.urban6.waiting.queue;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(QueueProperties.class)
public class QueueConfig {

    @Bean
    public RedisScript<Long> enqueueScript() {
        return script("redis/enqueue.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> restampScript() {
        return script("redis/restamp.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> leaveScript() {
        return script("redis/leave.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> sweepScript() {
        return script("redis/sweep.lua", Long.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> statusScript() {
        return script("redis/status.lua", List.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> promoteScript() {
        return script("redis/promote.lua", List.class);
    }

    private static <T> RedisScript<T> script(String location, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(resultType);
        return script;
    }

    /**
     * 샤드별 Redis 연결. 스크립트 빈은 샤드와 무관하므로 그대로 공유한다 —
     * RedisScript는 SHA와 반환 타입만 들고 있고, 어느 인스턴스에서 실행할지는 실행 시점의
     * 템플릿이 정한다.
     */
    @Bean
    public QueueShards queueShards(QueueProperties properties, StringRedisTemplate redis) {
        return new QueueShards(properties, redis);
    }

    /** 존은 도메인이 한국 철도라 튜닝 대상이 아니다. 개시·마감만 설정에서 읽는다. */
    @Bean
    public DailyWindow dailyWindow(QueueProperties properties) {
        return new DailyWindow(ZoneId.of("Asia/Seoul"), properties.open(), properties.close());
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
