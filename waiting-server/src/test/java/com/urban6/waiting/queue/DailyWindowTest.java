package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 판매 영업시간이 창 계산과 판정에 반영되는지 확인한다.
 *
 * <p>Redis도 Spring 컨텍스트도 쓰지 않는다. 창 계산은 입력이 Instant 하나인 순수 함수라
 * 컨테이너를 띄울 이유가 없고, Docker 없이 도는 테스트가 하나는 있어야 한다.
 *
 * <p>운영 값(07:00~13:00)으로 검증한다. application.yml은 개발 편의로 종일 열어 두고 있어
 * 그 값으로는 경계가 드러나지 않는다.
 */
class DailyWindowTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate DAY = LocalDate.of(2026, 7, 25);

    private final DailyWindow dailyWindow =
            new DailyWindow(SEOUL, LocalTime.of(7, 0), LocalTime.of(13, 0));

    @Test
    @DisplayName("개시 정각은 열려 있고 그 1초 전은 닫혀 있다")
    void opensAtOpenTime() {
        assertThat(at(seoul(6, 59, 59)).isOpen(seoul(6, 59, 59))).isFalse();
        assertThat(at(seoul(7, 0, 0)).isOpen(seoul(7, 0, 0))).isTrue();
    }

    @Test
    @DisplayName("마감 1초 전은 열려 있고 마감 정각은 닫혀 있다")
    void closesAtCloseTime() {
        assertThat(at(seoul(12, 59, 59)).isOpen(seoul(12, 59, 59))).isTrue();
        assertThat(at(seoul(13, 0, 0)).isOpen(seoul(13, 0, 0))).isFalse();
    }

    @Test
    @DisplayName("개시·마감 시각이 설정값 그대로 창에 담긴다")
    void windowCarriesConfiguredHours() {
        DailyWindow.Window window = at(seoul(9, 0, 0));

        assertThat(window.openAt()).isEqualTo(seoul(7, 0, 0));
        assertThat(window.closeAt()).isEqualTo(seoul(13, 0, 0));
    }

    @Test
    @DisplayName("windowId는 서울 날짜의 yyyyMMdd다 — UTC로는 전날인 시각에도")
    void windowIdFollowsSeoulDate() {
        // 서울 07:00은 UTC로 전날 22:00이다. 존을 잘못 쓰면 여기서 20260724가 나온다.
        assertThat(seoul(7, 0, 0)).isEqualTo(Instant.parse("2026-07-24T22:00:00Z"));
        assertThat(at(seoul(7, 0, 0)).windowId()).isEqualTo("20260725");
    }

    @Test
    @DisplayName("활성 키 유예가 대기 키 유예보다 길다 — 만료 체인을 담아야 하기 때문")
    void activeGraceOutlivesWaitingGrace() {
        DailyWindow.Window window = at(seoul(9, 0, 0));

        assertThat(Duration.between(window.closeAt(), window.waitingDeadline()))
                .isEqualTo(Duration.ofMinutes(10));
        assertThat(Duration.between(window.closeAt(), window.activeDeadline()))
                .isEqualTo(DailyWindow.Window.ACTIVE_GRACE);
        assertThat(window.activeDeadline()).isAfter(window.waitingDeadline());
    }

    @Test
    @DisplayName("순번 카운터는 대기 키보다 오래 남는다 — 되감기면 순번이 겹친다")
    void seqOutlivesWaiting() {
        DailyWindow.Window window = at(seoul(9, 0, 0));

        assertThat(window.seqDeadline()).isAfter(window.waitingDeadline());
    }

    private DailyWindow.Window at(Instant now) {
        return dailyWindow.at(now);
    }

    /** 2026-07-25의 서울 시각을 Instant로 바꾼다. */
    private static Instant seoul(int hour, int minute, int second) {
        return DAY.atTime(hour, minute, second).atZone(SEOUL).toInstant();
    }
}
