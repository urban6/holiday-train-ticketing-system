package com.urban6.waiting.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import com.urban6.waiting.TestcontainersConfiguration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 열차 조회 필터를 검증한다. 시드(V4)나 다른 테스트와 겹치지 않도록 전용 노선("테스트시")을
 * 직접 심고 정리한다 — remaining 값 자체는 보지 않고 필터·개수·순서만 보므로, 다른 테스트가
 * 시드 재고를 건드려도 영향받지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TrainRepositorySearchTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 24);

    @Autowired TrainRepository trainRepository;
    @Autowired JdbcClient jdbc;

    /**
     * TST-A 06:00 목적지, TST-B 09:00 목적지(일반 매진), TST-C 07:00 다른곳.
     * 모두 출발지 "테스트시".
     */
    @BeforeEach
    void seed() {
        insertTrain("TST-A", "테스트시", "목적지", LocalTime.of(6, 0), 10, 20);
        insertTrain("TST-B", "테스트시", "목적지", LocalTime.of(9, 0), 10, 0);
        insertTrain("TST-C", "테스트시", "다른곳", LocalTime.of(7, 0), 10, 20);
    }

    @AfterEach
    void clean() {
        jdbc.sql("""
                DELETE FROM train_seat_inventory
                WHERE train_id IN (SELECT id FROM train WHERE train_no LIKE 'TST-%')
                """).update();
        jdbc.sql("DELETE FROM train WHERE train_no LIKE 'TST-%'").update();
    }

    @Test
    @DisplayName("출발지·도착지·날짜·좌석종류가 일치하는 열차만 출발시간 순으로 준다")
    void filtersByRouteAndClass() {
        List<TrainAvailability> results =
                trainRepository.search("테스트시", "목적지", DATE, LocalTime.MIN, SeatClass.STANDARD);

        // TST-A, TST-B만(TST-C는 도착지가 다르다). 출발시간 순.
        assertThat(results).extracting(TrainAvailability::trainNo).containsExactly("TST-A", "TST-B");
        assertThat(results).allSatisfy(r -> assertThat(r.seatClass()).isEqualTo(SeatClass.STANDARD));
    }

    @Test
    @DisplayName("출발시간은 그 시각 이후(>=)만 포함한다")
    void filtersByDepartureTimeInclusiveFromRequestedTime() {
        List<TrainAvailability> results =
                trainRepository.search("테스트시", "목적지", DATE, LocalTime.of(7, 0), SeatClass.STANDARD);

        // 06:00(TST-A)은 빠지고 09:00(TST-B)만 남는다.
        assertThat(results).extracting(TrainAvailability::trainNo).containsExactly("TST-B");
    }

    @Test
    @DisplayName("매진(잔여 0) 열차도 결과에 포함된다")
    void includesSoldOutTrains() {
        List<TrainAvailability> results =
                trainRepository.search("테스트시", "목적지", DATE, LocalTime.MIN, SeatClass.STANDARD);

        assertThat(results)
                .filteredOn(r -> r.trainNo().equals("TST-B"))
                .singleElement()
                .satisfies(r -> assertThat(r.remaining()).isZero());
    }

    @Test
    @DisplayName("좌석종류가 다르면 같은 열차라도 그 종류의 잔여석을 준다")
    void filtersBySeatClass() {
        List<TrainAvailability> first =
                trainRepository.search("테스트시", "목적지", DATE, LocalTime.MIN, SeatClass.FIRST);

        assertThat(first).extracting(TrainAvailability::trainNo).containsExactly("TST-A", "TST-B");
        assertThat(first).allSatisfy(r -> assertThat(r.seatClass()).isEqualTo(SeatClass.FIRST));
    }

    @Test
    @DisplayName("일치하는 열차가 없으면 빈 목록을 준다")
    void noMatchReturnsEmpty() {
        assertThat(trainRepository.search("없는역", "목적지", DATE, LocalTime.MIN, SeatClass.STANDARD))
                .isEmpty();
    }

    private void insertTrain(String trainNo, String origin, String destination,
                             LocalTime departure, int firstSeats, int standardSeats) {
        long id = jdbc.sql("""
                        INSERT INTO train (train_no, origin, destination, departure_date, departure_time, arrival_time)
                        VALUES (:no, :origin, :destination, :date, :departure, :arrival)
                        RETURNING id
                        """)
                .param("no", trainNo)
                .param("origin", origin)
                .param("destination", destination)
                .param("date", DATE)
                .param("departure", departure)
                .param("arrival", departure.plusHours(2))
                .query(Long.class)
                .single();

        insertInventory(id, SeatClass.FIRST, firstSeats);
        insertInventory(id, SeatClass.STANDARD, standardSeats);
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
}
