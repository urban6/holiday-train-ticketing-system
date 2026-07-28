package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.LocalTime;
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

        private int maxBatch = 500;
        private Duration minPollInterval = Duration.ofSeconds(5);
        private Duration maxPollInterval = Duration.ofSeconds(30);
        private Duration pollGrace = Duration.ofSeconds(60);
        private Duration sweepInterval = Duration.ofSeconds(5);
        private Duration reservationTtl = Duration.ofMinutes(3);

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
                    1000, maxBatch,
                    Duration.ofSeconds(1), Duration.ofSeconds(150),
                    Duration.ofMinutes(10), reservationTtl,
                    minPollInterval, maxPollInterval, 4, pollGrace,
                    sweepInterval, 500);
        }
    }
}
