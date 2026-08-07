package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import com.urban6.waiting.queue.WaitingQueueService.State;
import com.urban6.waiting.queue.WaitingQueueService.Status;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import com.urban6.waiting.queue.schedule.AdmissionScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 대기열을 샤드로 나눴을 때의 동작을 확인한다. 다른 큐 테스트는 모두 {@code shard-count=1}로
 * 고정해 정확한 수를 단언하므로, 샤딩된 경로를 보는 곳은 여기뿐이다.
 *
 * <p>확인하는 것은 셋이다.
 * <ol>
 *   <li><b>라우팅</b> — 토큰 하나가 언제나 같은 샤드로 가는가. 어긋나면 조회가 자기 항목을 못 찾는다.
 *   <li><b>순번 근사</b> — {@code 샤드 안 순번 x 샤드 수}가 전역 순번에 얼마나 가까운가.
 *       이 근사를 쓰는 이유는 정확히 세려면 조회 한 번이 모든 샤드를 때려야 하고, 그러면
 *       샤드마다 받는 호출 수가 나누기 전과 같아져 폴링에서 이득이 통째로 사라지기 때문이다.
 *   <li><b>순회</b> — 스케줄러가 모든 샤드를 도는가. 한 샤드를 빠뜨리면 그 줄만 영영 멈춘다.
 * </ol>
 *
 * <p>여기서 처리량은 재지 않는다. 테스트용 Redis는 한 대라 샤드를 키로만 가른 상태이고,
 * 그 구성에서는 이벤트 루프가 하나라 배수가 나오지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "queue.shard-count=3",
        // 샤드당 정원 1. 기동 직후 fixedDelay의 첫 실행이 한 번 도는데, 정원이 넉넉하면 그때
        // 대기자가 통째로 승격돼 순번을 잴 것이 남지 않는다. 정원을 최소로 낮춰 그 영향을
        // 샤드당 한 명으로 묶는다(검증 허용폭 안이다).
        "queue.capacity=3",
        "queue.promote-interval=1h",
        // poll-grace(60s)보다 작아야 기동 검증을 통과한다.
        "queue.sweep-interval=59s"
})
class QueueShardingTest {

    private static final int SHARDS = 3;

    @Autowired WaitingQueueService waitingQueueService;
    @Autowired AdmissionScheduler admissionScheduler;
    @Autowired QueueProperties properties;
    @Autowired DailyWindow dailyWindow;
    @Autowired Clock clock;
    @Autowired MeterRegistry registry;
    @Autowired StringRedisTemplate redis;

    /** 창 키는 하루 단위라 테스트끼리 공유된다. 샤드마다 네 개씩 지운다. */
    @AfterEach
    void clearWindow() {
        List<String> keys = new ArrayList<>();
        for (int shard = 0; shard < SHARDS; shard++) {
            keys.add(QueueKeys.waiting(date(), shard));
            keys.add(QueueKeys.seq(date(), shard));
            keys.add(QueueKeys.active(date(), shard));
            keys.add(QueueKeys.pollDeadline(date(), shard));
        }
        redis.delete(keys);
    }

    @Test
    @DisplayName("토큰은 샤드에 고르게 흩어진다 — 순번 근사가 서 있는 유일한 전제다")
    void shardOfSpreadsTokensEvenly() {
        // 전역 순번을 '샤드 안 순번 x 샤드 수'로 계산하므로, 분배가 치우치면 순번이 그만큼 틀린다.
        // 여기가 깨지면 순번이 틀린 것이 아니라 순번을 계산하는 방식의 근거가 사라진다.
        int total = 100_000;
        int[] counts = new int[SHARDS];
        for (int i = 0; i < total; i++) {
            counts[QueueKeys.shardOf(UUID.randomUUID().toString(), SHARDS)]++;
        }

        // 이론상 표준편차가 sqrt(n x 1/3 x 2/3) = 149명이라 2%(667명)는 4.5시그마다.
        // 여유를 크게 두는 이유는 이 테스트가 잡으려는 것이 통계적 흔들림이 아니라
        // "해시가 한쪽으로 쏠린다"는 결함이기 때문이다.
        double ideal = (double) total / SHARDS;
        for (int shard = 0; shard < SHARDS; shard++) {
            assertThat(counts[shard]).isCloseTo((int) ideal,
                    org.assertj.core.data.Percentage.withPercentage(2));
        }
    }

    @Test
    @DisplayName("한 토큰은 자기 샤드에만 있다 — 조회·이탈이 상태 없이 같은 곳으로 돌아온다")
    void eachTokenLivesInExactlyOneShard() {
        List<Ticket> tickets = enqueue(30);

        for (Ticket ticket : tickets) {
            int expected = QueueKeys.shardOf(ticket.token(), SHARDS);
            for (int shard = 0; shard < SHARDS; shard++) {
                Long rank = redis.opsForZSet().rank(QueueKeys.waiting(date(), shard), ticket.token());
                if (shard == expected) {
                    assertThat(rank).as("샤드 %d에 있어야 한다", shard).isNotNull();
                } else {
                    assertThat(rank).as("샤드 %d에는 없어야 한다", shard).isNull();
                }
            }
        }
    }

    @Test
    @DisplayName("전역 순번은 샤드 안 순번 x 샤드 수로 근사되고, 오차가 예측 범위 안에 머문다")
    void globalRankStaysWithinThePredictedError() {
        // 아무도 빠지지 않았으므로 진입 순서 i가 곧 그 사람의 참 전역 순번이다.
        List<Ticket> tickets = enqueue(2000);

        // 오차는 Binomial(p, 1/k)의 표준편차라 sqrt(p(k-1))이다. 6시그마는 통계적으로 오지 않으므로,
        // 여기 걸리면 흔들림이 아니라 근사식이나 분배가 깨진 것이다. 상수 항은 기동 직후
        // 첫 승격이 샤드당 한 명씩 빼 갈 수 있는 몫이다.
        for (int i = 0; i < tickets.size(); i += 20) {
            Status status = waitingQueueService.status(tickets.get(i).date(), tickets.get(i).token());
            if (status.state() != State.WAITING) {
                continue;
            }
            double sigma = Math.sqrt((double) i * (SHARDS - 1));
            assertThat(Math.abs(status.ahead() - i))
                    .as("전역 순번 %d의 근사 오차", i)
                    .isLessThanOrEqualTo((long) (6 * sigma) + SHARDS);
        }
    }

    @Test
    @DisplayName("근사여도 앞 인원은 전체보다 작고 뒤 인원은 음수가 되지 않는다")
    void approximationKeepsTheRankInvariants() {
        // 셋을 같은 배수로 곱하기 때문에 성립한다. 어느 하나만 환산하면 여기서 깨진다 —
        // 화면이 "내 앞 1,200명 / 전체 900명"을 띄우게 된다.
        List<Ticket> tickets = enqueue(300);

        for (Ticket ticket : tickets) {
            Status status = waitingQueueService.status(ticket.date(), ticket.token());
            if (status.state() != State.WAITING) {
                continue;
            }
            assertThat(status.ahead()).isBetween(0L, status.total() - 1);
            assertThat(status.behind()).isNotNegative();
            assertThat(status.position()).isEqualTo(status.ahead() + 1);
            assertThat(status.ahead() + status.behind() + 1).isEqualTo(status.total());
        }
    }

    @Test
    @DisplayName("승격이 끼어들어도 앞 인원은 줄기만 한다")
    void aheadNeverGrows() {
        // 승격은 각 샤드의 앞에서만 빼고 신규는 뒤에 붙으므로, 샤드 안 순번이 늘어날 경로가 없다.
        // 곱셈은 단조성을 보존하므로 환산한 값도 그대로다 — k6/status.js가 같은 것을 본다.
        List<Ticket> tickets = enqueue(300);
        Ticket last = tickets.get(tickets.size() - 1);

        long previous = Long.MAX_VALUE;
        for (int cycle = 0; cycle < 10; cycle++) {
            Status status = waitingQueueService.status(last.date(), last.token());
            if (status.state() != State.WAITING) {
                break;
            }
            assertThat(status.ahead()).isLessThanOrEqualTo(previous);
            previous = status.ahead();

            admissionScheduler.promote();
        }
    }

    @Test
    @DisplayName("승격 스케줄러가 모든 샤드를 돈다 — 한 샤드를 빠뜨리면 그 줄만 영영 멈춘다")
    void promotionVisitsEveryShard() {
        // 샤드당 정원이 1이라 한 주기에 샤드마다 정확히 한 명이 올라간다.
        // 루프가 빠지면 안 돈 샤드의 active가 비어 있어 그대로 드러난다.
        enqueue(300);

        admissionScheduler.promote();

        long activeTotal = 0;
        for (int shard = 0; shard < SHARDS; shard++) {
            Long active = redis.opsForZSet().size(QueueKeys.active(date(), shard));
            assertThat(active).as("샤드 %d의 활성 인원", shard).isEqualTo(properties.capacityOf(shard));
            activeTotal += active == null ? 0 : active;
        }

        // 샤드가 나눠 가진 몫의 합이 곧 전역 정원이다. 넘으면 뒷단이 감당하기로 한 수를 넘긴 것이다.
        assertThat(activeTotal).isEqualTo(properties.capacity());
    }

    @Test
    @DisplayName("지표가 샤드별로 갈라져 나온다 — 깊이가 벌어지는 것을 알아챌 유일한 지점이다")
    void metricsAreTaggedPerShard() {
        enqueue(30);
        admissionScheduler.promote();

        for (int shard = 0; shard < SHARDS; shard++) {
            String tag = String.valueOf(shard);
            assertThat(registry.get("queue.waiting").tag("shard", tag).gauge()).isNotNull();
            assertThat(registry.get("queue.active").tag("shard", tag).gauge().value())
                    .as("샤드 %d의 활성 인원", shard)
                    .isEqualTo(properties.capacityOf(shard));
        }
    }

    private List<Ticket> enqueue(int count) {
        List<Ticket> tickets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tickets.add(waitingQueueService.enqueue());
        }
        return tickets;
    }

    private String date() {
        return dailyWindow.at(clock.instant()).date();
    }
}
