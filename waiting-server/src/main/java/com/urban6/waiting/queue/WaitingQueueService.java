package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueRepository.Snapshot;
import java.time.Clock;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaitingQueueService {

    private static final Duration WAITING_GRACE = Duration.ofHours(1);
    private static final Duration SEQ_GRACE = Duration.ofHours(12);

    private final WaitingQueueRepository repository;
    private final DailyWindow dailyWindow;
    private final QueueProperties properties;
    private final Clock clock;

    public Ticket enqueue() {
        DailyWindow.Window window = dailyWindow.at(clock.instant());

        String uuid = UUID.randomUUID().toString();

        long seq = repository.enqueue(window.windowId(), uuid,
                window.closeAt().plus(WAITING_GRACE),
                window.closeAt().plus(SEQ_GRACE));

        // log.debug("대기열 진입. window={}, seq={}", window.windowId(), seq);
        return new Ticket(uuid, window.windowId(), seq);
    }

    /**
     * windowId는 서버 시계에서 다시 구하지 않고 클라이언트가 돌려보낸 값을 쓴다.
     * 자정 직전에 진입한 사용자가 자정 직후에 조회하면 창이 달라져 자기 항목을 못 찾기 때문이다.
     * 키 TTL이 "창 마감 + 1시간"이라 그 사이의 조회는 유효해야 한다.
     */
    public Status status(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        Snapshot snapshot = repository.status(windowId, token, clock.millis());
        if (snapshot.gone()) {
            throw new QueueException.Expired("대기 정보를 찾을 수 없습니다.");
        }

        if (snapshot.admitted()) {
            return new Status(token, windowId, State.ADMITTED, 0, 0, 0, snapshot.total());
        }

        long ahead = snapshot.rank();
        long behind = snapshot.total() - snapshot.rank() - 1;
        return new Status(token, windowId, State.WAITING, ahead + 1, ahead, behind, snapshot.total());
    }

    /**
     * 이 토큰이 지금 활성이면 만료 시각(epoch ms), 아니면 비어 있다.
     * 입장 자격이 필요한 화면(pass 쿠키 검증)이 쓴다.
     *
     * <p>status와 같은 스크립트를 쓴다. status.lua가 이미 active ZSet의 ZSCORE를 만료까지
     * 보고 판정하므로 새 스크립트를 만들 이유가 없다. 화면 진입당 한 번뿐이라 대기 인원과
     * 무관하게 비용이 일정하다 — 폴링 경로와 달리 인원수만큼 곱해지지 않는다.
     *
     * <p>boolean이 아니라 만료 시각을 돌려주는 이유는 예약 화면의 남은 시간 때문이다.
     * 게이트가 어차피 읽는 값이라, 화면이 같은 것을 다시 물어보지 않아도 된다.
     *
     * <p>windowId 형식은 호출자가 먼저 거른다. 여기서 던지면 페이지 요청에 400 JSON이 나간다.
     * Redis가 죽었을 때의 Unavailable(503)은 그대로 전파한다 — 대기열 자체가 돌지 않는 상황이라
     * 로그인 화면만 멀쩡한 척하는 것이 오히려 거짓말이다.
     */
    public OptionalLong activeUntil(String windowId, String token) {
        Snapshot snapshot = repository.status(windowId, token, clock.millis());
        return snapshot.admitted() ? OptionalLong.of(snapshot.expireAt()) : OptionalLong.empty();
    }

    /**
     * 승격된 사용자가 입장권을 실제로 쓴다. 활성 유지 시간이 sessionTtl로 늘어난다.
     * 실패는 대개 admissionGrace가 지나 슬롯이 회수된 경우다.
     * Expired(404)로 넘겨서 클라이언트의 기존 만료 처리에 그대로 맞물리게 한다.
     */
    public void claim(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        if (!repository.restamp(windowId, token, clock.millis(), properties.sessionTtl(), "입장 확정")) {
            throw new QueueException.Expired("입장 가능 시간이 지났습니다.");
        }
    }

    /**
     * 로그인이 끝난 시점에 부른다. 활성 유지 시간을 reservationTtl로 다시 찍는다 —
     * 여기서부터가 예약에 주어진 시간이고, 지나면 활성 슬롯이 회수되어 게이트가 랜딩으로 돌려보낸다.
     *
     * <p>claim에서 받은 sessionTtl(10분)은 "로그인할 시간"이지 "예약할 시간"이 아니다.
     * 로그인을 마친 순간 그 시간은 역할이 끝났으므로, 남은 만큼을 그대로 들고 가지 않고 짧게 다시 찍는다.
     * 그만큼 정원이 빨리 회전한다.
     *
     * <p>이미 회수된 슬롯은 되살아나지 않는다(restamp가 false). 게이트를 통과한 뒤 이 호출까지의
     * 짧은 사이에 만료된 경우이며, 호출자는 로그인을 성립시키지 않고 랜딩으로 보내야 한다.
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
     * <p>만료를 기다리지 않고 즉시 비우므로 뒷사람이 그만큼 빨리 들어온다.
     * 동시에 "로그아웃하고 다시 로그인해서 예약 시간을 새로 받는" 경로를 막는다 —
     * 슬롯이 없으면 로그인 화면 자체에 도달하지 못하고, 대기열부터 다시 타야 한다.
     */
    public void release(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        repository.release(windowId, token);
    }

    /**
     * 서버 시계 기준 현재 창만 승격시킨다.
     * 창이 바뀌면 이전 창 대기자는 승격되지 않고 키 TTL로 자연 소멸한다 — 창 마감 = 판매 종료.
     */
    public Promotion promote() {
        DailyWindow.Window window = dailyWindow.at(clock.instant());

        return repository.promote(window.windowId(), clock.millis(), properties,
                window.closeAt().plus(WAITING_GRACE));
    }

    public record Ticket(String token, String windowId, long seq) {}

    public enum State {
        /** 대기 중. position/ahead/behind가 의미를 가진다. */
        WAITING,
        /** 입장. 순번은 더 이상 없으므로 0으로 채운다. */
        ADMITTED
    }

    /** position은 화면 표시용 1-based 순번이다. ahead/behind는 각각 내 앞·뒤 인원. */
    public record Status(String token, String windowId, State state,
                         long position, long ahead, long behind, long total) {}
}
