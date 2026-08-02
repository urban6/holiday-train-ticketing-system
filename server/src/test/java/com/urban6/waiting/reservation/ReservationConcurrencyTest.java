package com.urban6.waiting.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urban6.waiting.TestcontainersConfiguration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 예약의 동시성 정확성을 검증한다 — 초과예약 방지(다른 회원끼리 재고 경합)와 1인당 한도 강제
 * (같은 회원의 동시 요청). 전용 데이터(회원 login_id 'ctest-%', 열차 train_no 'CTST-%')를 심고
 * 정리한다.
 *
 * <p>동시성은 가상 스레드로 발사하고 CountDownLatch로 출발선을 맞춘다. Hikari 풀(기본 10)이
 * 병렬도를 제한하지만 정확성에는 영향이 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationConcurrencyTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 24);

    @Autowired ReservationService reservationService;
    @Autowired ReservationRepository reservationRepository;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void clean() {
        jdbc.sql("""
                DELETE FROM reservation
                WHERE member_id IN (SELECT id FROM member WHERE login_id LIKE 'ctest-%')
                """).update();
        jdbc.sql("""
                DELETE FROM train_seat_inventory
                WHERE train_id IN (SELECT id FROM train WHERE train_no LIKE 'CTST-%')
                """).update();
        jdbc.sql("DELETE FROM train WHERE train_no LIKE 'CTST-%'").update();
        jdbc.sql("DELETE FROM member WHERE login_id LIKE 'ctest-%'").update();
    }

    @Test
    @DisplayName("서로 다른 회원이 남은 4석에 10명 동시 예약하면 정확히 4건만 성공하고 초과예약이 없다")
    void concurrentReservationsNeverOversell() {
        long trainId = insertTrain("CTST-1", 4, 4);
        List<Long> members = insertMembers(10);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        runConcurrently(members.stream().<Runnable>map(memberId -> () -> {
            try {
                reservationService.reserve(memberId, trainId, SeatClass.FIRST, 1);
                success.incrementAndGet();
            } catch (ReservationException.SoldOut e) {
                soldOut.incrementAndGet();
            }
        }).toList());

        assertThat(success).hasValue(4);
        assertThat(soldOut).hasValue(6);
        assertThat(remaining(trainId, SeatClass.FIRST)).isZero();
    }

    @Test
    @DisplayName("한 회원이 서로 다른 열차 10건을 동시 예약하면 정확히 6건만 성공한다(한도 직렬화)")
    void concurrentReservationsEnforcePerMemberLimit() {
        long memberId = insertMembers(1).getFirst();
        List<Long> trains = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            trains.add(insertTrain("CTST-L" + i, 10, 10));
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        runConcurrently(trains.stream().<Runnable>map(trainId -> () -> {
            try {
                reservationService.reserve(memberId, trainId, SeatClass.STANDARD, 1);
                success.incrementAndGet();
            } catch (ReservationException.LimitExceeded e) {
                limited.incrementAndGet();
            }
        }).toList());

        assertThat(success).hasValue(6);
        assertThat(limited).hasValue(4);
        assertThat(reservationRepository.countByMember(memberId)).isEqualTo(6);
    }

    @Test
    @DisplayName("잔여석보다 많은 인원은 매진으로 거절되고 재고는 변하지 않는다")
    void rejectsWhenPassengersExceedRemaining() {
        long trainId = insertTrain("CTST-2", 2, 2);
        long memberId = insertMembers(1).getFirst();

        assertThatThrownBy(() -> reservationService.reserve(memberId, trainId, SeatClass.FIRST, 3))
                .isInstanceOf(ReservationException.SoldOut.class);

        assertThat(remaining(trainId, SeatClass.FIRST)).isEqualTo(2);
    }

    @Test
    @DisplayName("한도까지 예약한 뒤 다음 예약은 순차적으로도 거절된다")
    void rejectsBeyondLimitSequentially() {
        long memberId = insertMembers(1).getFirst();
        List<Long> trains = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            trains.add(insertTrain("CTST-S" + i, 10, 10));
        }

        for (int i = 0; i < 6; i++) {
            reservationService.reserve(memberId, trains.get(i), SeatClass.STANDARD, 1);
        }

        assertThatThrownBy(() -> reservationService.reserve(memberId, trains.get(6), SeatClass.STANDARD, 1))
                .isInstanceOf(ReservationException.LimitExceeded.class);
        assertThat(reservationRepository.countByMember(memberId)).isEqualTo(6);
    }

    @Test
    @DisplayName("인원이 1 미만이면 InvalidRequest로 거절되고 재고·예약이 남지 않는다")
    void rejectsNonPositivePassengers() {
        long trainId = insertTrain("CTST-P", 5, 5);
        long memberId = insertMembers(1).getFirst();

        assertThatThrownBy(() -> reservationService.reserve(memberId, trainId, SeatClass.FIRST, 0))
                .isInstanceOf(ReservationException.InvalidRequest.class);
        assertThatThrownBy(() -> reservationService.reserve(memberId, trainId, SeatClass.FIRST, -2))
                .isInstanceOf(ReservationException.InvalidRequest.class);

        // 차감도 INSERT도 일어나지 않았다.
        assertThat(remaining(trainId, SeatClass.FIRST)).isEqualTo(5);
        assertThat(reservationRepository.countByMember(memberId)).isZero();
    }

    /** 모든 작업을 하나의 출발선에서 동시에 놓고, 전부 끝날 때까지 기다린다. */
    private void runConcurrently(List<Runnable> tasks) {
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Runnable task : tasks) {
                exec.submit(() -> {
                    try {
                        start.await();
                        task.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행 대기가 중단됐습니다.", e);
        }
    }

    private long insertTrain(String trainNo, int firstSeats, int standardSeats) {
        long id = jdbc.sql("""
                        INSERT INTO train (train_no, origin, destination, departure_date, departure_time, arrival_time)
                        VALUES (:no, '서울', '부산', :date, TIME '06:00', TIME '08:40')
                        RETURNING id
                        """)
                .param("no", trainNo)
                .param("date", DATE)
                .query(Long.class)
                .single();

        insertInventory(id, SeatClass.FIRST, firstSeats);
        insertInventory(id, SeatClass.STANDARD, standardSeats);
        return id;
    }

    private void insertInventory(long trainId, SeatClass seatClass, int seats) {
        jdbc.sql("""
                        INSERT INTO train_seat_inventory (train_id, seat_class, total, remaining)
                        VALUES (:trainId, :seatClass, :total, :remaining)
                        """)
                .param("trainId", trainId)
                .param("seatClass", seatClass.name())
                .param("total", seats)
                .param("remaining", seats)
                .update();
    }

    private List<Long> insertMembers(int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long id = jdbc.sql("""
                            INSERT INTO member (login_id, password, name)
                            VALUES (:loginId, 'x', :name)
                            RETURNING id
                            """)
                    .param("loginId", "ctest-" + i + "-" + System.nanoTime())
                    .param("name", "동시성" + i)
                    .query(Long.class)
                    .single();
            ids.add(id);
        }
        return ids;
    }

    private int remaining(long trainId, SeatClass seatClass) {
        return jdbc.sql("""
                        SELECT remaining FROM train_seat_inventory
                        WHERE train_id = :trainId AND seat_class = :seatClass
                        """)
                .param("trainId", trainId)
                .param("seatClass", seatClass.name())
                .query(Integer.class)
                .single();
    }
}
