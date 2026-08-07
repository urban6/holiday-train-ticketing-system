package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 입장 제어 설정. 값은 application.yml이 단일 출처다.
 *
 * <p>기본값을 코드에 두지 않는 대신, 빠뜨리거나 잘못 넣으면 기동 시점에 터지게 한다.
 * capacity가 0으로 조용히 바인딩되면 아무도 입장하지 못하는데, 그건 로그만 봐서는 알기 어렵다.
 */
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

        // 태그가 없는 샤드는 키를 만들 수 없다. 여기서 막지 않으면 기동은 멀쩡히 끝나고
        // 그 샤드로 배정된 첫 사용자의 요청에서 QueueKeys.tag()가 던진다.
        require(shardCount <= QueueKeys.maxShardCount(),
                "queue.shard-count(%d)에 쓸 해시 태그가 %d개뿐입니다. QueueKeys.SHARD_TAGS에 문자를 추가하고 CLUSTER KEYSLOT으로 마스터마다 하나씩 떨어지는지 다시 확인하세요."
                        .formatted(shardCount, QueueKeys.maxShardCount()));

        require(capacity > 0, "queue.capacity는 1 이상이어야 합니다: " + capacity);

        // capacity는 전역 값이고 샤드가 나눠 갖는다. 샤드보다 작으면 몫이 0인 샤드가 생겨
        // 그 샤드의 대기자는 영영 승격되지 않는다.
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

        // 유예는 "알려 준 주기가 지나고도 이만큼은 더 기다려 준다"는 값이다. 그런데 브라우저가
        // 비활성 탭 타이머를 조이는 폭은 주기에 비례해 커지므로, 유예가 주기보다 작으면
        // 뒤쪽 사람일수록 정상인데도 회수될 여지가 커진다.
        require(maxPollInterval.compareTo(pollGrace) <= 0,
                "queue.max-poll-interval(%s)이 queue.poll-grace(%s)보다 큽니다. 뒤쪽 대기자가 정상인데도 회수됩니다."
                        .formatted(maxPollInterval, pollGrace));

        // 승격을 알아채는 수단도 폴링뿐이라, 주기가 길면 그만큼이 입장 확정 시간에서 깎인다.
        // 입장이 가까운 사람은 상한이 아니라 하한에 있으므로 실제로 걸릴 일은 드물지만,
        // 상한을 올릴 때 근거 없이 admission-grace를 잠식하지 않게 여기서 막는다.
        require(maxPollInterval.multipliedBy(2).compareTo(admissionGrace) < 0,
                "queue.max-poll-interval(%s)의 두 배가 queue.admission-grace(%s) 이상입니다. 승격을 알아챌 시간이 남지 않습니다."
                        .formatted(maxPollInterval, admissionGrace));

        // 스윕 주기가 유예보다 길면 판정 기준이 설정값이 아니라 주기가 되어 버린다.
        require(sweepInterval.compareTo(pollGrace) < 0,
                "queue.sweep-interval(%s)이 queue.poll-grace(%s) 이상입니다. 회수가 판정 기준보다 늦어집니다."
                        .formatted(sweepInterval, pollGrace));

        require(open != null && close != null, "queue.open / queue.close가 없습니다.");
        require(open.isBefore(close),
                "queue.open이 queue.close보다 앞이어야 합니다: %s ~ %s".formatted(open, close));

        // 멤버 score가 active 키 TTL을 넘으면 키가 통째로 사라져 활성 사용자 전원이 동시에
        // 슬롯을 잃는다. 마감 근처에만 재현되고 로그에 남지 않아 기동 시점에 알게 한다.
        Duration chain = admissionGrace.plus(sessionTtl).plus(reservationTtl);
        require(chain.compareTo(DailyWindow.Window.ACTIVE_GRACE) < 0,
                "queue의 만료 체인(%s)이 활성 키 유예(%s) 이상입니다. active 키가 먼저 사라져 활성 사용자가 동시에 증발합니다."
                        .formatted(chain, DailyWindow.Window.ACTIVE_GRACE));
    }

    /**
     * 대기 순번 1당 폴링 주기를 몇 ms 늘릴지. status.lua가 이 값 하나만 받아 주기를 구한다.
     *
     * <p>{@code 순번 / 승격 속도}가 남은 시간이고, 그것을 pollUpdates로 나눈 것이 주기다 —
     * 입장 전에 순번이 최소 그 횟수만큼 갱신된다는 뜻이다.
     *
     * <p>승격 속도는 maxBatch / promoteInterval로 잡는다. 큐가 이보다 빨리 줄 수 없어 남은 시간을
     * 과소평가하는 쪽이라, 자기 차례를 자면서 넘기지 않는다. 정상상태 회전 속도를 쓰면 반대로
     * 과대평가해서 개시 시각처럼 정원이 통째로 비는 구간에서 사람을 재운다.
     *
     * <p><b>Lua에 나누기를 넘기지 않는 이유는 정수 나눗셈이다.</b> maxBatch가 1000을 넘는 순간
     * 0이 되어 모두가 하한에 붙고 이 기능이 아무 소리 없이 꺼진다.
     *
     * <p><b>샤드 수가 여기 들어오지 않는 것이 맞다.</b> status.lua가 주는 순번은 그 사람의 샤드
     * 안에서의 순번이고, 그 샤드는 maxBatch씩 빠진다 — 둘의 축이 이미 같다. 전역으로 환산하면
     * 순번도 승격 속도도 나란히 샤드 수만큼 커져 몫이 그대로다. 여기에 샤드 수를 곱하거나 나누면
     * 그 상쇄를 깨서 주기가 샤드 수만큼 어긋난다.
     */
    public double millisPerRank() {
        return (double) promoteInterval.toMillis() / maxBatch / pollUpdates;
    }

    /**
     * 이 샤드가 가진 활성 정원. 전역 capacity를 샤드가 나눠 갖는다.
     *
     * <p>나머지를 앞 샤드에 하나씩 얹어 합이 정확히 capacity가 되게 한다. 그냥 정수 나눗셈만
     * 하면 1000명 정원이 샤드 3개에서 999명이 되는데, 정원은 뒷단이 감당할 수 있는 수라
     * 조용히 줄어드는 쪽보다 적어 둔 값과 같은 쪽이 낫다.
     */
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
