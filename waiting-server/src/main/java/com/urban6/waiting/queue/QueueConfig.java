package com.urban6.waiting.queue;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
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
    public RedisScript<Long> claimScript() {
        return script("redis/claim.lua", Long.class);
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

    @Bean
    public DailyWindow dailyWindow() {
        return new DailyWindow(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
