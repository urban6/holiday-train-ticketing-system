package com.urban6.waiting.reservation;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 예약 쓰기·조회. {@link com.urban6.waiting.member.MemberRepository}와 같은 규약이다.
 *
 * <p>여기 메서드들은 {@link ReservationService}의 한 트랜잭션 안에서 정해진 순서로 호출되어야
 * 정합성이 성립한다. 개별 호출만 보면 원자적으로 보이지만, 초과예약과 한도 초과를 막는 것은
 * 이 메서드들의 조합과 그것을 감싸는 트랜잭션이다.
 */
@Repository
@RequiredArgsConstructor
public class ReservationRepository {

    private final JdbcClient jdbcClient;

    /**
     * 회원 행에 배타 잠금을 건다. 같은 회원의 동시 예약요청을 직렬화해 한도 검사(count)와 INSERT
     * 사이에 다른 요청이 끼어드는 것을 막는다. 다른 회원은 서로 다른 행이라 병렬로 진행한다.
     *
     * <p>SELECT 결과 자체는 쓰지 않는다 — 이 호출의 목적은 잠금이다. 회원은 로그인 세션에서 온
     * 존재하는 id라 결과 유무는 확인하지 않는다.
     */
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
     * 재고를 인원수만큼 원자적으로 차감한다. 초과예약을 막는 핵심이다.
     *
     * <p>조건({@code remaining >= :passengers})과 차감을 한 UPDATE 문에 담는다. Postgres가 대상 행에
     * 배타 잠금을 걸고, 앞선 트랜잭션이 커밋한 뒤 깨어난 요청은 최신 remaining으로 조건을 다시 평가한다
     * (READ COMMITTED). 그래서 여러 요청이 동시에 들어와도 잔여석을 넘겨 팔지 않는다.
     * SELECT로 읽고 앱에서 검사한 뒤 절대값으로 쓰는 방식은 이 보장을 깨뜨리므로 쓰지 않는다.
     *
     * @return 갱신된 행 수. 1이면 차감 성공, 0이면 잔여 부족(또는 없는 조합)이라 매진이다.
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

    /**
     * 내 예약 목록. 최근 예약이 위로 오게 정렬한다.
     * reservation.id를 reservation_id로 별칭해 {@link ReservationSummary#reservationId()}에 맞춘다.
     */
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
     * 내 예약 하나를 취소(삭제)하고 어떤 좌석을 반납해야 하는지 돌려준다.
     *
     * <p>{@code member_id} 조건이 소유권 검사를 겸한다 — 남의 예약 id를 넣어도 매치되는 행이 없다.
     * DELETE ... RETURNING으로 삭제와 값 반환을 한 문장에 담아, 같은 예약을 동시에 두 번 취소해도
     * 실제로 행을 지운 요청만 값을 받는다(다른 쪽은 빈 Optional). 반납은 그 요청만 하므로
     * 재고가 두 번 늘지 않는다.
     *
     * @return 삭제된 예약의 좌석 정보. 이미 없거나 남의 예약이면 빈 Optional.
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

    /**
     * 취소한 인원수만큼 재고를 되돌린다. 실제 차감했던 예약을 지운 뒤에만 호출하므로
     * remaining이 total을 넘지 않는다({@code decrementInventory}의 역연산).
     */
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

    /**
     * 취소로 삭제된 예약이 반납할 좌석. {@code MemberRepository.Credentials}처럼 이 리포지토리
     * 안에서만 쓰는 매핑 전용 레코드다. 컬럼(train_id·seat_class·passenger_count)이 컴포넌트에 대응한다.
     */
    public record CancelledSeat(long trainId, SeatClass seatClass, int passengerCount) {}
}
