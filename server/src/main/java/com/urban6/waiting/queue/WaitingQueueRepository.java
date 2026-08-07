package com.urban6.waiting.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@RequiredArgsConstructor
public class WaitingQueueRepository {

    private final StringRedisTemplate redis;
    private final QueueProperties properties;
    private final RedisScript<Long> enqueueScript;
    private final RedisScript<Long> restampScript;
    private final RedisScript<Long> leaveScript;
    private final RedisScript<Long> sweepScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> statusScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> promoteScript;

    /**
     * 순번을 발급하고 대기열에 등록한다. 두 작업은 Lua로 묶는다.
     *
     * <p>어느 샤드에 들어갈지는 토큰이 정한다. 여기서 골라 어딘가 적어 두는 것이 아니라 토큰만
     * 있으면 언제든 다시 계산되므로, 이후의 조회·이탈·입장 확정이 상태 없이 같은 샤드로 온다.
     *
     * @return 샤드 안에서의 순번. 전역 순번이 아니다 — 샤드마다 seq 카운터가 따로다.
     */
    public long enqueue(String date, String uuid, long firstPollDeadlineMillis,
                        Instant waitingDeadline, Instant seqDeadline) {
        int shard = shardOf(uuid);
        Long seq = execute(enqueueScript, "대기열 진입", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.seq(date, shard),
                        QueueKeys.pollDeadline(date, shard)),
                uuid,
                millis(waitingDeadline),
                millis(seqDeadline),
                String.valueOf(firstPollDeadlineMillis));

        if (seq == null) {
            throw new IllegalStateException("스크립트가 seq를 반환하지 않았습니다: " + date);
        }
        return seq;
    }

    /**
     * 대기 순번과 입장 여부를 함께 읽는다.
     * 두 값 사이에 큐가 변하면 앞뒤 합이 어긋나므로 Lua Script로 묶어서 실행한다.
     *
     * <p>대기 중이면 다음 폴링 기한을 찍는다 — 이 조회가 곧 하트비트라 이름과 달리 순수 읽기가
     * 아니다. 근거는 status.lua 주석에 있다.
     *
     * <p><b>자기 샤드 한 곳만 본다.</b> 다른 샤드까지 세면 전역 순번이 정확해지지만, 조회 한 번이
     * 모든 샤드를 때리게 되어 샤드마다 받는 호출 수가 나누기 전과 같아진다 — 지배 부하인 폴링에서
     * 샤딩 이득이 통째로 사라진다. 대신 서비스가 샤드 수를 곱해 근사한다(WaitingQueueService 참고).
     */
    @SuppressWarnings("unchecked")
    public Snapshot status(String date, String uuid, long nowMillis, QueueProperties properties) {
        int shard = shardOf(uuid);
        List<Long> result = execute(statusScript, "순번 조회", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.active(date, shard),
                        QueueKeys.pollDeadline(date, shard)),
                uuid,
                String.valueOf(nowMillis),
                String.valueOf(properties.millisPerRank()),
                String.valueOf(properties.minPollInterval().toMillis()),
                String.valueOf(properties.maxPollInterval().toMillis()),
                String.valueOf(properties.pollGrace().toMillis()));

        if (result == null || result.size() < 5) {
            throw new IllegalStateException(
                    "스크립트가 state/rank/total/expireAt/pollAfter를 반환하지 않았습니다: " + date);
        }
        return new Snapshot(result.get(0), result.get(1), result.get(2), result.get(3), result.get(4));
    }

    /**
     * 만료된 활성 사용자를 회수하고, 정원에 빈 만큼 대기열 앞에서 승격시킨다.
     * 정원을 세는 것과 꺼내는 것이 갈라지면 그 사이에 정원을 넘길 수 있어 한 스크립트다.
     *
     * <p>샤드 하나만 처리한다. 샤드마다 전역 정원의 몫({@link QueueProperties#capacityOf})을 갖고
     * 자기 줄의 앞에서 꺼내므로, 전역으로 엄격한 선착순은 아니다. {@code maxBatch}는 샤드 수로
     * 나누지 않는다 — 이 값이 막으려는 것은 "한 인스턴스의 단일 스레드가 한 번에 얼마나 오래
     * 잡히는가"이고, 인스턴스가 늘어도 각자의 사정은 그대로다.
     */
    @SuppressWarnings("unchecked")
    public Promotion promote(String date, int shard, long nowMillis, QueueProperties properties,
                             Instant activeDeadline) {
        List<Long> result = execute(promoteScript, "승격", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.active(date, shard),
                        QueueKeys.pollDeadline(date, shard)),
                String.valueOf(nowMillis),
                String.valueOf(properties.capacityOf(shard)),
                String.valueOf(properties.maxBatch()),
                String.valueOf(properties.admissionGrace().toMillis()),
                millis(activeDeadline));

        if (result == null || result.size() < 3) {
            throw new IllegalStateException("스크립트가 승격 결과를 반환하지 않았습니다: " + date);
        }
        return new Promotion(result.get(0), result.get(1), result.get(2));
    }

    /**
     * 활성 슬롯의 만료시각을 지금부터 ttl 뒤로 다시 찍는다.
     * 입장 확정(sessionTtl)과 로그인(reservationTtl)이 같은 스크립트를 ttl만 바꿔 쓴다.
     *
     * @return 활성이 아니거나 이미 만료됐으면 false
     */
    public boolean restamp(String date, String uuid, long nowMillis, Duration ttl, String operation) {
        int shard = shardOf(uuid);
        Long restamped = execute(restampScript, operation, date, shard,
                List.of(QueueKeys.active(date, shard)),
                uuid,
                String.valueOf(nowMillis),
                String.valueOf(ttl.toMillis()));

        return restamped != null && restamped == 1L;
    }

    /**
     * 대기열에서 뺀다. 사용자가 팝업을 닫거나 페이지를 떠난 경우다.
     *
     * <p>두 키에 걸치므로 Lua로 묶는다. active는 건드리지 않는다(근거는 leave.lua 주석).
     *
     * @return 실제로 뺐으면 true. 이미 없었으면 false지만 호출자는 둘 다 성공으로 다룬다.
     */
    public boolean leave(String date, String uuid) {
        int shard = shardOf(uuid);
        Long removed = execute(leaveScript, "대기열 이탈", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.pollDeadline(date, shard)),
                uuid);

        return removed != null && removed == 1L;
    }

    /**
     * 다음 폴링 기한이 지난 대기자를 회수한다.
     *
     * <p>브라우저 이벤트가 아예 오지 않는 경로(크래시·기기 꺼짐·네트워크 단절)를 덮는
     * 유일한 수단이다. 기한은 사람마다 달라 조회 때 이미 score에 구워져 있다.
     *
     * <p>샤드 하나만 훑는다. maxSweep도 승격의 maxBatch와 같은 이유로 샤드당 상한이다.
     *
     * @return 이번에 회수한 인원. maxSweep이 상한이다.
     */
    public long sweep(String date, int shard, long nowMillis, int maxSweep) {
        Long swept = execute(sweepScript, "이탈 회수", date, shard,
                List.of(QueueKeys.waiting(date, shard), QueueKeys.pollDeadline(date, shard)),
                String.valueOf(nowMillis),
                String.valueOf(maxSweep));

        return swept == null ? 0 : swept;
    }

    /**
     * 활성 슬롯을 즉시 비운다. 로그아웃처럼 사용자가 스스로 나가는 경우다.
     * 단일 명령이라 Lua로 묶지 않는다 — 원자성이 필요한 "세고 꺼내기"가 없다.
     */
    public void release(String date, String uuid) {
        int shard = shardOf(uuid);
        run("입장권 반납", date, shard,
                () -> redis.opsForZSet().remove(QueueKeys.active(date, shard), uuid));
    }

    private int shardOf(String uuid) {
        return QueueKeys.shardOf(uuid, properties.shardCount());
    }

    private <T> T execute(RedisScript<T> script, String operation, String date, int shard,
                          List<String> keys, String... args) {
        return run(operation, date, shard, () -> redis.execute(script, keys, (Object[]) args));
    }

    /**
     * Redis 예외를 도메인 예외로 옮기는 지점.
     *
     * <p>샤드 번호를 로그에 남긴다. 노드가 여럿이면 한 대만 아픈 경우가 생기는데,
     * 그때 어느 대인지가 로그에 없으면 전체 장애와 구분되지 않는다.
     *
     * <p>Cluster의 {@code CLUSTERDOWN}·리다이렉트 초과 같은 예외는 Spring Data Redis의 Lettuce
     * 예외 변환기가 별도 타입으로 인식하지 못해 일반 {@link RedisSystemException}으로 떨어진다 —
     * 새 catch 절이 필요한 게 아니라 이미 아래에서 잡힌다.
     */
    private <T> T run(String operation, String date, int shard, Supplier<T> call) {
        try {
            return call.get();
        } catch (RedisConnectionFailureException | QueryTimeoutException e) {
            log.error("{} 실패 - Redis 통신 오류. window={}, shard={}", operation, date, shard, e);
            throw new QueueException.Unavailable("대기열이 일시적으로 불가합니다.", e);
        } catch (RedisSystemException e) {
            // NOSCRIPT는 Spring이 EVAL 재시도로 처리하므로 여기 오지 않는다.
            log.error("{} 스크립트 실행 오류. window={}, shard={}", operation, date, shard, e);
            throw new QueueException.Unavailable("대기열 처리 중 오류가 발생했습니다.", e);
        }
    }

    private static String millis(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    /**
     * status.lua의 한 스냅샷. rank는 0-based, expireAt은 활성 만료 epoch ms이며
     * 각각 해당하지 않으면 -1이다. pollAfterMillis는 대기 중이 아니면 0.
     *
     * <p><b>rank와 total은 그 사람의 샤드 안에서의 값이다.</b> 전역 값은 서비스가 샤드 수를
     * 곱해 근사한다.
     */
    public record Snapshot(long state, long rank, long total, long expireAt, long pollAfterMillis) {

        private static final long WAITING = 0;
        private static final long ADMITTED = 1;

        public boolean waiting() {
            return state == WAITING;
        }

        public boolean admitted() {
            return state == ADMITTED;
        }

        /** 만료됐거나, 발급된 적 없거나, 창이 다르다. */
        public boolean gone() {
            return !waiting() && !admitted();
        }
    }

    /** promoted는 이번 주기에 올라간 인원, active/waiting은 실행 직후의 각 인원. */
    public record Promotion(long promoted, long active, long waiting) {}
}
