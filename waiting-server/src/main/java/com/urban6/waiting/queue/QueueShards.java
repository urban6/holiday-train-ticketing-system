package com.urban6.waiting.queue;

import com.urban6.waiting.queue.QueueProperties.ShardEndpoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 샤드 번호로 Redis 연결을 고르는 곳.
 *
 * <p>대기열을 여러 인스턴스로 나누는 이유는 Redis가 단일 스레드이기 때문이다. 같은 인스턴스
 * 안에서 키만 쪼개면 이벤트 루프가 그대로라 처리량이 늘지 않는다 — 100만 건 투입에서 호출당
 * 시간이 3.87us에서 3.85us로 사실상 변하지 않았고, 깊이가 100만에서 33만으로 얕아진 몫은
 * 프로세스들이 코어를 나눠 쓰는 비용이 도로 가져갔다. 처리량 2.93배는 순전히 이벤트 루프가
 * 셋이 되어서 나온 것이다 (k6/shard-probe.sh).
 *
 * <p>그래서 {@code queue.shard-endpoints}가 비어 있는 구성은 <b>성능이 아니라 동작을 위한
 * 것이다.</b> 인스턴스 하나에 샤드 키만 갈라 두면 라우팅·순번 근사·스케줄러 순회가 모두
 * 정상으로 돌아가므로, clone 직후나 테스트에서 Redis 한 대로 그대로 뜬다.
 * 배수를 원하면 인스턴스를 실제로 나눠야 한다.
 */
@Slf4j
public class QueueShards implements DisposableBean {

    private final List<StringRedisTemplate> templates;

    /** 우리가 만든 것만 담는다. 공유 템플릿의 연결은 스프링이 닫는다. */
    private final List<LettuceConnectionFactory> owned;

    public QueueShards(QueueProperties properties, StringRedisTemplate shared) {
        int count = properties.shardCount();
        List<ShardEndpoint> endpoints = properties.shardEndpoints();

        if (endpoints.isEmpty()) {
            this.templates = Collections.nCopies(count, shared);
            this.owned = List.of();
            log.info("대기열 샤드 {}개를 Redis 한 곳에 키로만 나눠 둔다. 처리량 이득은 없다.", count);
            return;
        }

        // 타임아웃을 새로 설정으로 받지 않고 공유 연결에서 그대로 가져온다. loadtest 프로파일이
        // spring.data.redis.timeout을 1초로 낮춰 두는데, 샤드만 기본값 60초로 남으면
        // 포화 구간에서 샤드마다 다르게 실패해 그 측정이 무엇을 잰 것인지 알 수 없게 된다.
        Duration timeout = timeoutOf(shared);

        List<StringRedisTemplate> built = new ArrayList<>(count);
        List<LettuceConnectionFactory> factories = new ArrayList<>(count);
        for (ShardEndpoint endpoint : endpoints) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(endpoint.host(), endpoint.port()),
                    LettuceClientConfiguration.builder().commandTimeout(timeout).build());
            factory.afterPropertiesSet();
            // 4.x의 LettuceConnectionFactory는 SmartLifecycle이라 afterPropertiesSet만으로는
            // 연결을 열지 않는다. 스프링이 관리하는 빈이 아니므로 여기서 직접 켠다.
            factory.start();
            factories.add(factory);

            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            built.add(template);
        }

        this.templates = List.copyOf(built);
        this.owned = List.copyOf(factories);
        log.info("대기열 샤드 {}개: {} (타임아웃 {})", count, endpoints, timeout);
    }

    public int count() {
        return templates.size();
    }

    public StringRedisTemplate of(int shard) {
        return templates.get(shard);
    }

    /** 이 토큰이 속한 샤드의 연결. 샤드 번호는 토큰 하나로 정해진다. */
    public StringRedisTemplate forToken(String token) {
        return templates.get(QueueKeys.shardOf(token, templates.size()));
    }

    @Override
    public void destroy() {
        for (LettuceConnectionFactory factory : owned) {
            factory.stop();
            factory.destroy();
        }
    }

    private static Duration timeoutOf(StringRedisTemplate shared) {
        if (shared.getConnectionFactory() instanceof LettuceConnectionFactory lettuce) {
            return Duration.ofMillis(lettuce.getTimeout());
        }
        throw new IllegalStateException(
                "공유 Redis 연결이 Lettuce가 아니라 타임아웃을 읽을 수 없습니다: " + shared.getConnectionFactory());
    }
}
