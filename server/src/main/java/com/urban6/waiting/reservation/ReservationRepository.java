package com.urban6.waiting.reservation;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 메서드들은 {@link ReservationService}의 한 트랜잭션 안에서 정해진 순서로 불려야 정합성이 성립한다. */
@Repository
@RequiredArgsConstructor
public class ReservationRepository {

    private final JdbcClient jdbcClient;

    /** 같은 회원의 동시 요청을 직렬화하려는 잠금이다. 결과는 쓰지 않는다. */
    public void lockMember(long memberId) {
        jdbcClient.sql("SELECT id FROM member WHERE id = :memberId FOR UPDATE")
                .param("memberId", memberId)
                .query(Long.class)
                .optional();
    }

    public long countByMember(long memberId) {
        return jdbcClient.sql("SELECT count(*) FROM reservation WHERE member_id = :memberId")
                .param("memberId", memberId)
                .query(Long.class)
                .single();
    }

    /**
     * 조건과 차감을 한 UPDATE에 담아 초과예약을 막는다. SELECT로 읽고 앱에서 검사한 뒤
     * 절대값으로 쓰면 이 보장이 깨진다.
     *
     * @return 0이면 잔여 부족(매진)
     */
    public int decrementInventory(long trainId, SeatClass seatClass, int passengers) {
        return jdbcClient.sql("""
                        UPDATE train_seat_inventory
                           SET remaining = remaining - :passengers
                         WHERE train_id = :trainId
                           AND seat_class = :seatClass
                           AND remaining >= :passengers
                        """)
                .param("trainId", trainId)
                .param("seatClass", seatClass.name())
                .param("passengers", passengers)
                .update();
    }

    public void insert(long memberId, long trainId, SeatClass seatClass, int passengers) {
        jdbcClient.sql("""
                        INSERT INTO reservation (member_id, train_id, seat_class, passenger_count)
                        VALUES (:memberId, :trainId, :seatClass, :passengers)
                        """)
                .param("memberId", memberId)
                .param("trainId", trainId)
                .param("seatClass", seatClass.name())
                .param("passengers", passengers)
                .update();
    }

    public List<ReservationSummary> findByMember(long memberId) {
        return jdbcClient.sql("""
                        SELECT r.id             AS reservation_id,
                               t.train_no       AS train_no,
                               t.origin         AS origin,
                               t.destination    AS destination,
                               t.departure_date AS departure_date,
                               t.departure_time AS departure_time,
                               t.arrival_time   AS arrival_time,
                               r.seat_class     AS seat_class,
                               r.passenger_count AS passenger_count
                        FROM reservation r
                        JOIN train t ON t.id = r.train_id
                        WHERE r.member_id = :memberId
                        ORDER BY r.created_at DESC
                        """)
                .param("memberId", memberId)
                .query(ReservationSummary.class)
                .list();
    }

    /**
     * {@code member_id} 조건이 소유권 검사를 겸한다. DELETE ... RETURNING이라 동시에 두 번 취소해도
     * 행을 실제로 지운 요청만 값을 받아 재고가 두 번 늘지 않는다.
     */
    public Optional<CancelledSeat> cancelOwned(long reservationId, long memberId) {
        return jdbcClient.sql("""
                        DELETE FROM reservation
                         WHERE id = :reservationId
                           AND member_id = :memberId
                        RETURNING train_id, seat_class, passenger_count
                        """)
                .param("reservationId", reservationId)
                .param("memberId", memberId)
                .query(CancelledSeat.class)
                .optional();
    }

    /** 실제로 지운 예약에 대해서만 부르므로 remaining이 total을 넘지 않는다. */
    public void restoreInventory(long trainId, SeatClass seatClass, int passengers) {
        jdbcClient.sql("""
                        UPDATE train_seat_inventory
                           SET remaining = remaining + :passengers
                         WHERE train_id = :trainId
                           AND seat_class = :seatClass
                        """)
                .param("trainId", trainId)
                .param("seatClass", seatClass.name())
                .param("passengers", passengers)
                .update();
    }

    public record CancelledSeat(long trainId, SeatClass seatClass, int passengerCount) {}
}
