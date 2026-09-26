package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueRepository.Snapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {

    private final WaitingQueueRepository repository;
    private final DailyWindow dailyWindow;
    private final QueueProperties properties;
    private final Clock clock;

    public Ticket enqueue() {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            throw new QueueException.Closed(
                    "판매 시간이 아닙니다. 판매 시간은 %s ~ %s입니다."
                            .formatted(properties.open(), properties.close()));
        }

        String token = UUID.randomUUID().toString();

        // 아직 순번을 모르므로 첫 주기는 항상 하한이다.
        long firstPoll = properties.minPollInterval().toMillis();
        long firstPollDeadline = now.toEpochMilli() + firstPoll + properties.pollGrace().toMillis();

        long seq = repository.enqueue(window.date(), token, firstPollDeadline,
                window.waitingDeadline(), window.seqDeadline());

        return new Ticket(token, window.date(), seq, firstPoll);
    }

    /**
     * 창은 서버 시계가 아니라 클라이언트가 돌려보낸 date로 찾는다. 마감 직전 진입자가
     * 마감 직후에 조회해도 자기 항목을 찾아야 하기 때문이다.
     *
     * <p>입장 판정을 마감 검사보다 먼저 한다. 승격된 사용자는 마감 뒤에도 claim으로 넘어가야 한다.
     */
    public Status status(String date, String token) {
        QueueKeys.requireValidDate(date);

        Snapshot snapshot = repository.status(date, token, clock.millis(), properties);

        if (snapshot.admitted()) {
            return new Status(token, date, State.ADMITTED, 0, 0, 0,
                    snapshot.total() * properties.shardCount(), 0);
        }

        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);
        if (!date.equals(window.date()) || !window.isOpen(now)) {
            throw new QueueException.Closed("판매가 종료되었습니다. 다시 신청해 주세요.");
        }

        if (snapshot.gone()) {
            throw new QueueException.Expired("대기 정보를 찾을 수 없습니다.");
        }

        // 자기 샤드 순번에 샤드 수를 곱해 전역 순번을 근사한다. 균등 해시라 앞사람들이 샤드에
        // 고르게 흩어져 있다는 것이 전제다. 모든 샤드를 물으면 폴링에서 샤딩 이득이 사라진다.
        int shards = properties.shardCount();
        long ahead = snapshot.rank() * shards;
        long total = snapshot.total() * shards;
        long behind = total - ahead - 1;
        return new Status(token, date, State.WAITING, ahead + 1, ahead, behind, total,
                snapshot.pollAfterMillis());
    }

    /** 활성이면 만료 시각(epoch ms). 예약 화면이 남은 시간을 그릴 때 쓴다. */
    public OptionalLong activeUntil(String date, String token) {
        Snapshot snapshot = repository.status(date, token, clock.millis(), properties);
        return snapshot.admitted() ? OptionalLong.of(snapshot.expireAt()) : OptionalLong.empty();
    }

    public void claim(String date, String token) {
        QueueKeys.requireValidDate(date);

        if (!repository.restamp(date, token, clock.millis(), properties.sessionTtl(), "입장 확정")) {
            throw new QueueException.Expired("입장 가능 시간이 지났습니다.");
        }
    }

    /** 로그인 직후 부른다. 남은 sessionTtl을 들고 가지 않고 reservationTtl로 다시 찍어 정원을 빨리 돌린다. */
    public void startReservation(String date, String token) {
        QueueKeys.requireValidDate(date);

        if (!repository.restamp(date, token, clock.millis(), properties.reservationTtl(), "예약 시간 시작")) {
            throw new QueueException.Expired("입장 가능 시간이 지났습니다.");
        }
    }

    public void release(String date, String token) {
        QueueKeys.requireValidDate(date);

        repository.release(date, token);
    }

    /** 이탈 신호는 늦거나 중복으로 오는 게 정상이라 실패를 알리지 않는다. */
    public void leave(String date, String token) {
        QueueKeys.requireValidDate(date);

        repository.leave(date, token);
    }

    public long sweepStale(int shard) {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            return 0;
        }

        return repository.sweep(window.date(), shard, now.toEpochMilli(), properties.maxSweep());
    }

    public int shardCount() {
        return properties.shardCount();
    }

    /** 판매 시간 안의 현재 창만 승격한다. 이전 창 대기자는 키 TTL로 사라진다. */
    public Promotion promote(int shard) {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            return new Promotion(0, 0, 0);
        }

        return repository.promote(window.date(), shard, clock.millis(), properties,
                window.activeDeadline());
    }

    public record Ticket(String token, String date, long seq, long pollAfterMillis) {}

    public enum State {
        WAITING,
        ADMITTED
    }

    /** position·ahead·behind·total은 샤드 순번에서 환산한 근사값이다. */
    public record Status(String token, String date, State state,
                         long position, long ahead, long behind, long total,
                         long pollAfterMillis) {}
}
