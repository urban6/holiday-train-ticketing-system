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

        // 판매 시간 밖에서는 줄을 세우지 않는다. 개시 전에 받아 주면 밤새 줄 서기가 되고,
        // 마감 뒤에 받아 주면 승격이 오지 않는 줄에 세우는 셈이다.
        if (!window.isOpen(now)) {
            throw new QueueException.Closed(
                    "판매 시간이 아닙니다. 판매 시간은 %s ~ %s입니다."
                            .formatted(properties.open(), properties.close()));
        }

        String uuid = UUID.randomUUID().toString();

        // 첫 조회 주기는 순번과 무관하게 항상 하한이다. 여기서는 아직 순번을 모르고,
        // 뒤쪽 사람에게 30초를 준 채로 시작하면 자기 번호를 보기까지 그만큼 빈 화면을 본다.
        // 첫 응답을 받은 다음부터 순번에 맞는 주기가 붙는다.
        long firstPoll = properties.minPollInterval().toMillis();
        long firstPollDeadline = now.toEpochMilli() + firstPoll + properties.pollGrace().toMillis();

        long seq = repository.enqueue(window.windowId(), uuid, firstPollDeadline,
                window.waitingDeadline(),
                window.seqDeadline());

        // log.debug("대기열 진입. window={}, seq={}", window.windowId(), seq);
        return new Ticket(uuid, window.windowId(), seq, firstPoll);
    }

    /**
     * 조회는 서버 시계로 창을 다시 구하지 않고 클라이언트가 돌려보낸 windowId를 쓴다.
     * 마감 직전에 진입한 사용자가 마감 직후에 조회하면 창이 달라져 자기 항목을 못 찾기 때문이다.
     *
     * <p>대신 그 windowId가 지금 승격이 오는 창인지는 따로 본다. 아니면 순번이 영원히 줄지 않으므로
     * 정상 응답을 계속 주는 대신 판매 종료를 알린다.
     *
     * <p>판정 순서가 중요하다. 승격된 사용자는 마감 뒤에도 통과해야 한다 —
     * 화면은 ADMITTED를 받고 나서야 입장 확정(claim)으로 넘어가므로, 여기서 막으면
     * 슬롯을 쥔 채 입장하지 못한다.
     */
    public Status status(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        Snapshot snapshot = repository.status(windowId, token, clock.millis(), properties);

        if (snapshot.admitted()) {
            // 입장한 사람에게도 total은 "지금 몇 명이 기다리는지"로 남으므로 같은 배수로 환산한다.
            return new Status(token, windowId, State.ADMITTED, 0, 0, 0,
                    snapshot.total() * repository.shardCount(), 0);
        }

        // 입장하지 못한 채 이 창의 판매가 끝난 경우다. 승격은 판매 시간 안의 현재 창에만
        // 오므로(promote 참고) 이 조건에서 순번은 더 이상 줄지 않는다.
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);
        if (!windowId.equals(window.windowId()) || !window.isOpen(now)) {
            throw new QueueException.Closed("판매가 종료되었습니다. 다시 신청해 주세요.");
        }

        if (snapshot.gone()) {
            throw new QueueException.Expired("대기 정보를 찾을 수 없습니다.");
        }

        // 샤드 안 순번을 전역으로 환산한다. 정확히 세려면 다른 샤드까지 물어야 하는데, 그러면
        // 조회 한 번이 모든 샤드를 때려 폴링에서 샤딩 이득이 사라진다(WaitingQueueRepository.status 참고).
        //
        // 균등 해시라 내 앞의 사람들은 샤드에 고르게 흩어져 있고, 따라서 내 샤드 순번은 전역
        // 순번의 약 1/샤드수다. 오차는 Binomial(p, 1/k)의 표준편차라 sqrt(p(k-1))이고,
        // 100만 번째에서 최대 3,282명(0.33%)이었다 — 화면에서 사람이 구별하지 못하는 폭이다.
        // 근거는 k6/shard-probe.sh의 측정 기록에 있다.
        //
        // 셋을 같은 배수로 곱하므로 "0 <= 앞 < 전체"도, 순번이 줄기만 하는 것도 그대로 성립한다.
        int shards = repository.shardCount();
        long ahead = snapshot.rank() * shards;
        long total = snapshot.total() * shards;
        long behind = total - ahead - 1;
        return new Status(token, windowId, State.WAITING, ahead + 1, ahead, behind, total,
                snapshot.pollAfterMillis());
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
     *
     * <p>같은 스크립트지만 이 경로가 폴링 기한을 찍는 일은 없다. status.lua의 ZADD는 대기열에
     * 남아 있을 때(rank가 있을 때)만 타는데, 여기 오는 사람은 이미 승격돼 ZPOPMIN으로 waiting에서
     * 빠진 뒤다. 화면 진입마다 기한이 밀려 유령이 살아나는 경로가 아니다.
     */
    public OptionalLong activeUntil(String windowId, String token) {
        Snapshot snapshot = repository.status(windowId, token, clock.millis(), properties);
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
     * 대기열에서 스스로 빠진다. 팝업을 닫거나 페이지를 떠날 때 클라이언트가 부른다.
     *
     * <p>실패를 알리지 않는다. 이미 없는 토큰, 이미 승격된 토큰, 지난 창의 토큰이 모두 정상적으로
     * 도착하기 때문이다 — 페이지 이탈 신호(pagehide)는 늦게 도착하거나 중복으로 도착할 수 있고,
     * 그때 404를 돌려줘 봐야 화면은 이미 사라진 뒤라 받을 사람이 없다.
     *
     * <p>windowId가 깨진 경우만 예외로 던진다. 그건 이탈이 아니라 잘못된 요청이고,
     * 검사 없이 넘기면 그대로 Redis 키가 된다.
     */
    public void leave(String windowId, String token) {
        QueueKeys.requireValidWindowId(windowId);

        repository.leave(windowId, token);
    }

    /**
     * 다음 폴링 기한이 지난 대기자를 회수한다. 스위퍼가 주기적으로 부른다.
     *
     * <p>여기서 "몇 초 지났으면 이탈인가"를 정하지 않는다. 사람마다 다른 주기를 알려 주므로
     * 판정 기준도 사람마다 다르고, 그 값은 조회 때 이미 기한으로 구워져 있다(status.lua).
     *
     * <p>승격(promote)과 같은 조건으로 현재 창만 훑는다. 창이 닫히면 승격이 오지 않아 순번이
     * 줄지 않고, 키도 곧 TTL로 통째로 사라지므로 회수할 이유가 없다.
     *
     * @return 이번 주기에 회수한 인원
     */
    public long sweepStale(int shard) {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            return 0;
        }

        return repository.sweep(window.windowId(), shard, now.toEpochMilli(), properties.maxSweep());
    }

    /** 스케줄러가 몇 번 돌아야 하는지. 설정을 스케줄러에 다시 주입하지 않으려고 여기서 노출한다. */
    public int shardCount() {
        return repository.shardCount();
    }

    /**
     * 서버 시계 기준, 판매 시간 안의 현재 창만 승격시킨다.
     * 창이 바뀌면 이전 창 대기자는 승격되지 않고 키 TTL로 자연 소멸한다 — 창 마감 = 판매 종료.
     *
     * <p>마감 뒤에는 만료 회수(promote.lua의 ZREMRANGEBYSCORE)도 함께 멈춘다. 그래도 정원이
     * 잘못 세어지지는 않는다 — 회수되지 않은 멤버는 status.lua가 score로 걸러내고,
     * 키 자체는 activeDeadline에 사라진다.
     */
    public Promotion promote(int shard) {
        Instant now = clock.instant();
        DailyWindow.Window window = dailyWindow.at(now);

        if (!window.isOpen(now)) {
            return new Promotion(0, 0, 0);
        }

        return repository.promote(window.windowId(), shard, clock.millis(), properties,
                window.activeDeadline());
    }

    /**
     * pollAfterMillis는 첫 조회까지 기다릴 시간이다. 클라이언트가 이 값을 상수로 들고 있으면
     * 튜닝값을 바꿀 때마다 JS와 k6를 같이 고쳐야 하므로, 진입 응답이 직접 알려 준다.
     *
     * <p>seq는 <b>샤드 안에서의</b> 발급 번호다. 샤드마다 카운터가 따로라 전역 순번이 아니고,
     * 전역 카운터를 두면 그 키 하나가 샤드로 나눈 부하를 도로 모아 새 병목이 된다.
     * 클라이언트는 이 값을 쓰지 않는다 — 화면에 뜨는 순번은 조회 응답의 position이다.
     */
    public record Ticket(String token, String windowId, long seq, long pollAfterMillis) {}

    public enum State {
        /** 대기 중. position/ahead/behind가 의미를 가진다. */
        WAITING,
        /** 입장. 순번은 더 이상 없으므로 0으로 채운다. */
        ADMITTED
    }

    /**
     * position은 화면 표시용 1-based 순번이다. ahead/behind는 각각 내 앞·뒤 인원.
     *
     * <p><b>position·ahead·behind·total은 근사값이다.</b> 대기열이 샤드로 나뉘어 있고 조회는 자기
     * 샤드만 보므로, 샤드 안 값에 샤드 수를 곱해 전역으로 환산한다. 오차는 100만 번째에서 0.33%
     * 수준이고, 그 대신 조회가 샤드 하나로 끝나 폴링 부하가 샤드 수만큼 나뉜다.
     * 근사여도 {@code 0 <= ahead < total}과 "순번은 줄기만 한다"는 그대로 지켜진다.
     *
     * <p>pollAfterMillis는 다음 조회까지 기다릴 시간이며, 순번이 뒤일수록 길다. 이 값을 서버가
     * 정하는 이유는 승격 속도와 정원을 서버만 알고 있기 때문이다 — 근거는 status.lua 주석에 있다.
     * 입장한 뒤에는 더 물어볼 것이 없으므로 0이다.
     */
    public record Status(String token, String windowId, State state,
                         long position, long ahead, long behind, long total,
                         long pollAfterMillis) {}
}
