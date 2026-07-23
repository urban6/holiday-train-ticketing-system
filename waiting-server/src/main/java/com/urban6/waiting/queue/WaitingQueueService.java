package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueRepository.Promotion;
import com.urban6.waiting.queue.WaitingQueueRepository.Snapshot;
import java.time.Clock;
import java.time.Duration;
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
     * 승격된 사용자가 입장권을 실제로 쓴다. 활성 유지 시간이 sessionTtl로 늘어난다.
     * 실패는 대개 admissionGrace가 지나 슬롯이 회수된 경우다.
     * Expired(404)로 넘겨서 클라이언트의 기존 만료 처리에 그대로 맞물리게 한다.
     */
    public void claim(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        if (!repository.claim(windowId, token, clock.millis(), properties)) {
            throw new QueueException.Expired("입장 가능 시간이 지났습니다.");
        }
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
