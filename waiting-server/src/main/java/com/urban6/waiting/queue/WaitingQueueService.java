package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueRepository.Snapshot;
import com.urban6.waiting.queue.ingest.EnqueueSink;
import java.time.Clock;
import java.time.Instant;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {

    private final WaitingQueueRepository repository;
    private final EnqueueSink enqueueSink;
    private final DailyWindow dailyWindow;
    private final QueueProperties properties;
    private final Clock clock;

    /** 줄에 세운다. 실제로 넣는 방법은 {@link EnqueueSink}가 정한다. */
    public Ticket enqueue() {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        // 개시 전에 받으면 밤새 줄 서기가 되고, 마감 뒤에 받으면 승격이 오지 않는 줄에 세운다.
        if (!window.isOpen(now)) {
            throw new QueueException.Closed(
                    "판매 시간이 아닙니다. 판매 시간은 %s ~ %s입니다."
                            .formatted(properties.open(), properties.close()));
        }

        EnqueueSink.Accepted accepted = enqueueSink.submit(window, now);

        // 첫 주기는 항상 하한이다. 아직 순번을 모르는데 뒤쪽 사람에게 30초를 주면
        // 자기 번호를 보기까지 그만큼 빈 화면을 본다.
        long firstPoll = properties.minPollInterval().toMillis();

        return new Ticket(accepted.token(), window.windowId(), accepted.seq(), firstPoll);
    }

    /**
     * 조회는 서버 시계로 창을 다시 구하지 않고 클라이언트가 돌려보낸 windowId를 쓴다.
     * 마감 직전에 진입한 사용자가 마감 직후에 조회하면 창이 달라져 자기 항목을 못 찾기 때문이다.
     *
     * <p>판정 순서가 중요하다. 승격된 사용자는 마감 뒤에도 통과해야 한다 —
     * 화면은 ADMITTED를 받고 나서야 claim으로 넘어가므로, 여기서 막으면 슬롯을 쥔 채 못 들어간다.
     */
    public Status status(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        Snapshot snapshot = repository.status(windowId, token, clock.millis(), properties);

        if (snapshot.admitted()) {
            return new Status(token, windowId, State.ADMITTED, 0, 0, 0, snapshot.total(), 0);
        }

        // 승격은 판매 시간 안의 현재 창에만 오므로, 이 조건에서 순번은 더 이상 줄지 않는다.
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);
        if (!windowId.equals(window.windowId()) || !window.isOpen(now)) {
            throw new QueueException.Closed("판매가 종료되었습니다. 다시 신청해 주세요.");
        }

        if (snapshot.gone()) {
            // Kafka 경유는 접수(202)와 등록 사이에 공백이 있고, 그 구간에서는 어느 ZSet에도 없어
            // status.lua가 만료와 구별할 수 없다. 그래서 판정을 여기서 한다.
            if (enqueueSink.maybePending(token, now)) {
                return new Status(token, windowId, State.PENDING, 0, 0, 0, snapshot.total(),
                        properties.minPollInterval().toMillis());
            }
            throw new QueueException.Expired("대기 정보를 찾을 수 없습니다.");
        }

        long ahead = snapshot.rank();
        long behind = snapshot.total() - snapshot.rank() - 1;
        return new Status(token, windowId, State.WAITING, ahead + 1, ahead, behind, snapshot.total(),
                snapshot.pollAfterMillis());
    }

    /**
     * 이 토큰이 지금 활성이면 만료 시각(epoch ms), 아니면 비어 있다. pass 쿠키 검증이 쓴다.
     *
     * <p>boolean이 아니라 시각을 주는 이유는 예약 화면의 남은 시간 때문이다.
     * status.lua를 그대로 쓰지만 폴링 기한은 찍지 않는다 — 승격된 뒤라 waiting에 없다.
     */
    public OptionalLong activeUntil(String windowId, String token) {
        Snapshot snapshot = repository.status(windowId, token, clock.millis(), properties);
        return snapshot.admitted() ? OptionalLong.of(snapshot.expireAt()) : OptionalLong.empty();
    }

    /**
     * 승격된 사용자가 입장권을 실제로 쓴다. 활성 유지 시간이 sessionTtl로 늘어난다.
     * 실패는 대개 admissionGrace가 지나 슬롯이 회수된 경우다.
     */
    public void claim(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        if (!repository.restamp(windowId, token, clock.millis(), properties.sessionTtl(), "입장 확정")) {
            throw new QueueException.Expired("입장 가능 시간이 지났습니다.");
        }
    }

    /**
     * 로그인이 끝난 시점에 부른다. 여기서부터가 예약에 주어진 시간이다.
     *
     * <p>claim에서 받은 sessionTtl은 "로그인할 시간"이라 역할이 끝났다. 남은 만큼을 들고 가지
     * 않고 reservationTtl로 짧게 다시 찍어야 정원이 그만큼 빨리 회전한다.
     */
    public void startReservation(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        if (!repository.restamp(windowId, token, clock.millis(), properties.reservationTtl(), "예약 시간 시작")) {
            throw new QueueException.Expired("입장 가능 시간이 지났습니다.");
        }
    }

    /**
     * 활성 슬롯을 자발적으로 반납한다. 로그아웃이 유일한 호출자다.
     *
     * <p>"로그아웃하고 다시 로그인해서 예약 시간을 새로 받는" 경로를 막는다 —
     * 슬롯이 없으면 로그인 화면에 도달하지 못하고 대기열부터 다시 타야 한다.
     */
    public void release(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        repository.release(windowId, token);
    }

    /**
     * 대기열에서 스스로 빠진다. 팝업을 닫거나 페이지를 떠날 때 클라이언트가 부른다.
     *
     * <p>실패를 알리지 않는다. 이탈 신호(pagehide)는 늦게 오거나 중복으로 오므로 이미 없는
     * 토큰·이미 승격된 토큰이 정상적으로 도착하고, 404를 줘 봐야 화면은 이미 사라진 뒤다.
     * windowId가 깨진 경우만 던진다 — 검사 없이 넘기면 그대로 Redis 키가 된다.
     */
    public void leave(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        repository.leave(windowId, token);
    }

    /**
     * 다음 폴링 기한이 지난 대기자를 회수한다. 스위퍼가 주기적으로 부른다.
     *
     * <p>여기서 "몇 초 지났으면 이탈인가"를 정하지 않는다. 사람마다 주기가 달라 판정 기준도
     * 다르고, 그 값은 조회 때 이미 기한으로 구워져 있다(status.lua).
     *
     * @return 이번 주기에 회수한 인원
     */
    public long sweepStale() {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            return 0;
        }

        return repository.sweep(window.windowId(), now.toEpochMilli(), properties.maxSweep());
    }

    /**
     * 서버 시계 기준, 판매 시간 안의 현재 창만 승격시킨다.
     * 창이 바뀌면 이전 창 대기자는 승격되지 않고 키 TTL로 자연 소멸한다 — 창 마감 = 판매 종료.
     */
    public Promotion promote() {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            return new Promotion(0, 0, 0);
        }

        return repository.promote(window.windowId(), clock.millis(), properties,
                window.activeDeadline());
    }

    /**
     * pollAfterMillis는 첫 조회까지 기다릴 시간이다. 클라이언트가 상수로 들고 있으면
     * 튜닝값을 바꿀 때마다 JS와 k6를 같이 고쳐야 하므로 응답이 직접 알려 준다.
     */
    public record Ticket(String token, String windowId, long seq, long pollAfterMillis) {}

    public enum State {
        /**
         * 접수됐지만 아직 대기열에 등록되기 전. Kafka 경유 진입에서만 나온다.
         * position/ahead/behind는 0이지만 화면이 숫자로 그리면 안 된다 — "곧 내 차례"로 읽힌다.
         */
        PENDING,
        /** 대기 중. position/ahead/behind가 의미를 가진다. */
        WAITING,
        /** 입장. 순번은 더 이상 없으므로 0으로 채운다. */
        ADMITTED
    }

    /**
     * position은 화면 표시용 1-based 순번, ahead/behind는 각각 내 앞·뒤 인원.
     * pollAfterMillis는 다음 조회까지 기다릴 시간이며 순번이 뒤일수록 길다(근거는 status.lua).
     */
    public record Status(String token, String windowId, State state,
                         long position, long ahead, long behind, long total,
                         long pollAfterMillis) {}
}
