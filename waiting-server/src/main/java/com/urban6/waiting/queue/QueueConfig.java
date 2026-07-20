package com.urban6.waiting.queue;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class QueueConfig {

    @Bean
    public RedisScript<Long> enqueueScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/enqueue.lua"));
        script.setResultType(Long.class);
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
