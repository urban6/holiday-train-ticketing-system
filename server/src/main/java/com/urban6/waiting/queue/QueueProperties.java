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
        require(capacity > 0, "queue.capacity는 1 이상이어야 합니다: " + capacity);
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
     */
    public double millisPerRank() {
        return (double) promoteInterval.toMillis() / maxBatch / pollUpdates;
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
