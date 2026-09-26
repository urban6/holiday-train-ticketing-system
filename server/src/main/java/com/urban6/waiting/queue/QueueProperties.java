package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 입장 제어 설정. 기본값을 두지 않고, 빠뜨리거나 잘못 넣으면 기동 시점에 실패한다. */
@ConfigurationProperties("queue")
public record QueueProperties(
        LocalTime open,
        LocalTime close,
        int shardCount,
        int capacity,
        int maxBatch,
        Duration promoteInterval,
        Duration admissionGrace,
        Duration sessionTtl,
        Duration reservationTtl,
        Duration minPollInterval,
        Duration maxPollInterval,
        int pollUpdates,
        Duration pollGrace,
        Duration sweepInterval,
        int maxSweep
) {

    public QueueProperties {
        require(shardCount > 0, "queue.shard-count는 1 이상이어야 합니다: " + shardCount);
        require(shardCount <= QueueKeys.maxShardCount(),
                "queue.shard-count(%d)에 쓸 해시 태그가 %d개뿐입니다. QueueKeys.SHARD_TAGS에 문자를 추가하고 CLUSTER KEYSLOT으로 마스터마다 하나씩 떨어지는지 다시 확인하세요."
                        .formatted(shardCount, QueueKeys.maxShardCount()));

        require(capacity > 0, "queue.capacity는 1 이상이어야 합니다: " + capacity);
        require(capacity >= shardCount,
                "queue.capacity(%d)가 queue.shard-count(%d)보다 작습니다. 몫이 0인 샤드가 생깁니다."
                        .formatted(capacity, shardCount));
        require(maxBatch > 0, "queue.max-batch는 1 이상이어야 합니다: " + maxBatch);
        require(maxSweep > 0, "queue.max-sweep은 1 이상이어야 합니다: " + maxSweep);
        require(pollUpdates > 0, "queue.poll-updates는 1 이상이어야 합니다: " + pollUpdates);
        require(positive(promoteInterval), "queue.promote-interval이 없거나 0 이하입니다.");
        require(positive(admissionGrace), "queue.admission-grace가 없거나 0 이하입니다.");
        require(positive(sessionTtl), "queue.session-ttl이 없거나 0 이하입니다.");
        require(positive(reservationTtl), "queue.reservation-ttl이 없거나 0 이하입니다.");
        require(positive(minPollInterval), "queue.min-poll-interval이 없거나 0 이하입니다.");
        require(positive(maxPollInterval), "queue.max-poll-interval이 없거나 0 이하입니다.");
        require(positive(pollGrace), "queue.poll-grace가 없거나 0 이하입니다.");
        require(positive(sweepInterval), "queue.sweep-interval이 없거나 0 이하입니다.");

        require(minPollInterval.compareTo(maxPollInterval) < 0,
                "queue.min-poll-interval(%s)이 queue.max-poll-interval(%s) 이상입니다."
                        .formatted(minPollInterval, maxPollInterval));

        require(maxPollInterval.compareTo(pollGrace) <= 0,
                "queue.max-poll-interval(%s)이 queue.poll-grace(%s)보다 큽니다. 뒤쪽 대기자가 정상인데도 회수됩니다."
                        .formatted(maxPollInterval, pollGrace));

        require(maxPollInterval.multipliedBy(2).compareTo(admissionGrace) < 0,
                "queue.max-poll-interval(%s)의 두 배가 queue.admission-grace(%s) 이상입니다. 승격을 알아챌 시간이 남지 않습니다."
                        .formatted(maxPollInterval, admissionGrace));

        require(sweepInterval.compareTo(pollGrace) < 0,
                "queue.sweep-interval(%s)이 queue.poll-grace(%s) 이상입니다. 회수가 판정 기준보다 늦어집니다."
                        .formatted(sweepInterval, pollGrace));

        require(open != null && close != null, "queue.open / queue.close가 없습니다.");
        require(open.isBefore(close),
                "queue.open이 queue.close보다 앞이어야 합니다: %s ~ %s".formatted(open, close));

        // 멤버 만료가 active 키 TTL보다 길면 마감 근처에서 키가 통째로 사라져 활성 사용자 전원이 슬롯을 잃는다.
        Duration chain = admissionGrace.plus(sessionTtl).plus(reservationTtl);
        require(chain.compareTo(DailyWindow.Window.ACTIVE_GRACE) < 0,
                "queue의 만료 체인(%s)이 활성 키 유예(%s) 이상입니다. active 키가 먼저 사라져 활성 사용자가 동시에 증발합니다."
                        .formatted(chain, DailyWindow.Window.ACTIVE_GRACE));
    }

    /**
     * 대기 순번 1당 폴링 주기를 몇 ms 늘릴지. 승격 속도를 상한(maxBatch / promoteInterval)으로 잡아
     * 남은 시간을 짧게 추정하므로 자기 차례를 자면서 넘기지 않는다.
     *
     * <p>Lua에 나누기를 넘기지 않는 이유는 정수 나눗셈이다. maxBatch가 1000을 넘으면 0이 된다.
     * 순번과 승격 속도가 둘 다 샤드 기준이라 샤드 수는 들어가지 않는다.
     */
    public double millisPerRank() {
        return (double) promoteInterval.toMillis() / maxBatch / pollUpdates;
    }

    /** 나머지를 앞 샤드에 하나씩 얹어 합이 정확히 capacity가 되게 한다. */
    public int capacityOf(int shard) {
        return capacity / shardCount + (shard < capacity % shardCount ? 1 : 0);
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
