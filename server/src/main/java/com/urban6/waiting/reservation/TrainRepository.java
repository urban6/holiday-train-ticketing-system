package com.urban6.waiting.reservation;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 열차 조회. {@link com.urban6.waiting.member.MemberRepository}와 같은 규약이다 —
 * JPA 없이 {@link JdbcClient}로 SQL을 그대로 쓰고, 결과는 레코드 생성자에 매핑한다.
 */
@Repository
@RequiredArgsConstructor
public class TrainRepository {

    private final JdbcClient jdbcClient;

    /**
     * 출발지·도착지·출발일·좌석종류가 일치하는 열차를, 요청한 출발시간 이후로 시간 순으로 준다.
     *
     * <p>출발시간은 "그 시각 이후"({@code >=})로 본다. 매진인 열차도 결과에 포함한다 —
     * 목록에서 빼면 "자리가 없다"와 "열차 자체가 없다"가 구분되지 않는다.
     *
     * <p>train.id를 train_id로 별칭해 {@link TrainAvailability#trainId()}에 맞춘다.
     */
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
