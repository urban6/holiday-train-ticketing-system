package com.urban6.waiting.reservation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TrainRepository {

    private final JdbcClient jdbcClient;

    /** 매진인 열차도 포함한다. 빼면 자리가 없는 것과 열차가 없는 것이 구분되지 않는다. */
    public List<TrainAvailability> search(String origin, String destination,
                                          LocalDate departureDate, LocalTime departureTime,
                                          SeatClass seatClass) {
        return jdbcClient.sql("""
                        SELECT t.id            AS train_id,
                               t.train_no      AS train_no,
                               t.origin        AS origin,
                               t.destination   AS destination,
                               t.departure_date AS departure_date,
                               t.departure_time AS departure_time,
                               t.arrival_time  AS arrival_time,
                               i.seat_class    AS seat_class,
                               i.remaining     AS remaining
                        FROM train t
                        JOIN train_seat_inventory i ON i.train_id = t.id
                        WHERE t.origin = :origin
                          AND t.destination = :destination
                          AND t.departure_date = :departureDate
                          AND t.departure_time >= :departureTime
                          AND i.seat_class = :seatClass
                        ORDER BY t.departure_time
                        """)
                .param("origin", origin)
                .param("destination", destination)
                .param("departureDate", departureDate)
                .param("departureTime", departureTime)
                .param("seatClass", seatClass.name())
                .query(TrainAvailability.class)
                .list();
    }
}
