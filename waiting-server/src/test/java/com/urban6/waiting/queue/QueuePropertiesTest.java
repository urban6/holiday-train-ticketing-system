package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.urban6.waiting.queue.QueueProperties.ShardEndpoint;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 설정 검증이 기동 시점에 터지는지 확인한다.
 *
 * <p>여기서 막는 값들은 공통점이 있다 — 잘못 넣어도 애플리케이션이 멀쩡히 뜨고, 며칠 뒤
 * 특정 조건에서만 이상하게 굴다가, 로그에는 아무것도 남기지 않는다. 그래서 기동 시점에
 * 던지게 해 뒀고, 그 장치가 실제로 도는지는 여기서만 확인할 수 있다.
 *
 * <p>Spring 컨텍스트도 Redis도 필요 없다. record 생성자를 직접 부른다.
 */
class QueuePropertiesTest {

    @Test
    @DisplayName("application.yml의 현재 값 조합은 통과한다")
    void theShippedConfigurationIsValid() {
        assertThatCode(QueuePropertiesTest::valid).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("순번 1당 주기 증가분은 정수 나눗셈으로 0이 되지 않는다")
    void millisPerRankSurvivesLargeBatches() {
        // max-batch가 promote-interval(ms)보다 커지는 순간이 함정이다. 정수로 먼저 나누면
        // 0이 되어 모두가 하한에 붙고, 순번에 따라 주기를 다르게 준다는 것 자체가 조용히 꺼진다.
        QueueProperties large = builder().maxBatch(2000).build();

        assertThat(large.millisPerRank()).isGreaterThan(0);
        assertThat(valid().millisPerRank()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("샤드 인스턴스 목록은 비어 있거나 샤드 수와 같은 개수여야 한다")
    void shardEndpointsMustBeAllOrNothing() {
        // 비워 두는 것은 "한 곳에 키로만 나눈다"는 유효한 설정이다(테스트·단일 인스턴스 개발).
        assertThatCode(() -> builder().shardCount(3).build()).doesNotThrowAnyException();

        // 반만 채우면 나머지 샤드가 조용히 엉뚱한 인스턴스로 간다. 기동에서 막는다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().shardCount(3)
                        .shardEndpoints(new ShardEndpoint("127.0.0.1", 6380)).build())
                .withMessageContaining("shard-endpoints");
    }

    @Test
    @DisplayName("같은 인스턴스를 두 샤드에 적으면 기동에 실패한다")
    void shardEndpointsMustBeDistinct() {
        // 한 인스턴스가 두 샤드분을 받으면 깊이가 갈린다. 전역 순번을 '샤드 안 순번 x 샤드 수'로
        // 근사하고 있어서, 깊이가 고르다는 전제가 깨지는 순간 순번이 그만큼 틀린다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().shardCount(2)
                        .shardEndpoints(new ShardEndpoint("127.0.0.1", 6380),
                                new ShardEndpoint("127.0.0.1", 6380))
                        .build())
                .withMessageContaining("두 번");
    }

    @Test
    @DisplayName("정원이 샤드 수보다 작으면 기동에 실패한다")
    void capacityMustCoverEveryShard() {
        // 몫이 0인 샤드가 생기면 그 샤드의 대기자는 영영 승격되지 않는다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().shardCount(4).capacity(3).build())
                .withMessageContaining("shard-count");
    }

    @Test
    @DisplayName("정원은 샤드가 나눠 갖되 합이 정확히 정원이다")
    void capacityIsSplitWithoutLoss() {
        // 정수 나눗셈만 하면 1000이 샤드 3개에서 999가 된다. 정원은 뒷단이 감당할 수 있는 수라
        // 조용히 줄어드는 쪽보다 적어 둔 값과 같은 쪽이 낫다.
        QueueProperties sharded = builder().shardCount(3).capacity(1000).build();

        assertThat(sharded.capacityOf(0)).isEqualTo(334);
        assertThat(sharded.capacityOf(1)).isEqualTo(333);
        assertThat(sharded.capacityOf(2)).isEqualTo(333);
        assertThat(sharded.capacityOf(0) + sharded.capacityOf(1) + sharded.capacityOf(2))
                .isEqualTo(1000);
    }

    @Test
    @DisplayName("주기 하한이 상한 이상이면 기동에 실패한다")
    void minPollIntervalMustBeBelowMax() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().minPollInterval(Duration.ofSeconds(30)).build())
                .withMessageContaining("min-poll-interval");
    }

    @Test
    @DisplayName("주기 상한이 유예보다 크면 기동에 실패한다")
    void maxPollIntervalMustNotExceedGrace() {
        // 비활성 탭 타이머가 늦춰지는 폭은 주기에 비례해 커진다. 유예가 주기보다 작으면
        // 뒤쪽 사람일수록 시킨 대로 기다렸는데도 회수될 여지가 커진다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().pollGrace(Duration.ofSeconds(10)).build())
                .withMessageContaining("poll-grace");
    }

    @Test
    @DisplayName("주기 상한의 두 배가 입장 확정 시간 이상이면 기동에 실패한다")
    void maxPollIntervalMustLeaveRoomToNoticeAdmission() {
        // 승격을 알아채는 수단도 폴링뿐이라, 주기를 키우면 그만큼이 claim 시간에서 깎인다.
        // 유예도 함께 올려야 앞의 max-poll-interval <= poll-grace 검증을 지나 여기까지 온다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder()
                        .maxPollInterval(Duration.ofSeconds(80))
                        .pollGrace(Duration.ofSeconds(80))
                        .build())
                .withMessageContaining("admission-grace");
    }

    @Test
    @DisplayName("스윕 주기가 유예 이상이면 기동에 실패한다")
    void sweepIntervalMustBeBelowGrace() {
        // 회수가 판정 기준보다 늦어지면, 값을 읽고도 언제 빠지는지 예측할 수 없다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().sweepInterval(Duration.ofSeconds(60)).build())
                .withMessageContaining("sweep-interval");
    }

    @Test
    @DisplayName("만료 체인이 활성 키 유예를 넘으면 기동에 실패한다")
    void expiryChainMustFitInsideTheActiveKeyLifetime() {
        // active 키가 먼저 사라지면 활성 사용자 전원이 동시에 슬롯을 잃는다.
        // 자정이나 마감 근처에만 재현되고 로그에 아무것도 남지 않는 종류의 사고다.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> builder().reservationTtl(Duration.ofHours(1)).build())
                .withMessageContaining("만료 체인");
    }

    private static QueueProperties valid() {
        return builder().build();
    }

    private static Builder builder() {
        return new Builder();
    }

    /** application.yml의 현재 값에서 한 항목만 바꿔 가며 검증을 하나씩 건드리기 위한 것. */
    private static final class Builder {

        private int shardCount = 3;
        private List<ShardEndpoint> shardEndpoints = List.of();
        private int capacity = 1000;
        private int maxBatch = 500;
        private Duration minPollInterval = Duration.ofSeconds(5);
        private Duration maxPollInterval = Duration.ofSeconds(30);
        private Duration pollGrace = Duration.ofSeconds(60);
        private Duration sweepInterval = Duration.ofSeconds(5);
        private Duration reservationTtl = Duration.ofMinutes(3);

        Builder shardCount(int value) {
            this.shardCount = value;
            return this;
        }

        Builder shardEndpoints(ShardEndpoint... values) {
            this.shardEndpoints = List.of(values);
            return this;
        }

        Builder capacity(int value) {
            this.capacity = value;
            return this;
        }

        Builder maxBatch(int value) {
            this.maxBatch = value;
            return this;
        }

        Builder minPollInterval(Duration value) {
            this.minPollInterval = value;
            return this;
        }

        Builder maxPollInterval(Duration value) {
            this.maxPollInterval = value;
            return this;
        }

        Builder pollGrace(Duration value) {
            this.pollGrace = value;
            return this;
        }

        Builder sweepInterval(Duration value) {
            this.sweepInterval = value;
            return this;
        }

        Builder reservationTtl(Duration value) {
            this.reservationTtl = value;
            return this;
        }

        QueueProperties build() {
            return new QueueProperties(
                    LocalTime.parse("00:00"), LocalTime.parse("23:59:59"),
                    shardCount, shardEndpoints,
                    capacity, maxBatch,
                    Duration.ofSeconds(1), Duration.ofSeconds(150),
                    Duration.ofMinutes(10), reservationTtl,
                    minPollInterval, maxPollInterval, 4, pollGrace,
                    sweepInterval, 500);
        }
    }
}
