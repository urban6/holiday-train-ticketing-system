package com.urban6.waiting.reservation;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 조회 결과 한 줄. train과 train_seat_inventory를 조인한 값이라, 열차 정보에 고른 좌석종류의
 * 잔여석까지 함께 담는다.
 *
 * <p>{@link com.urban6.waiting.member.MemberRepository.Credentials}와 같은 규약이다 —
 * JdbcClient가 레코드 생성자에 매핑하므로 SELECT의 컬럼(별칭)이 컴포넌트 이름·순서와 대응해야 한다.
 * seat_class(VARCHAR)는 {@link SeatClass}로, DATE/TIME은 LocalDate/LocalTime으로 기본 변환된다.
 */
public record TrainAvailability(
        long trainId,
        String trainNo,
        String origin,
        String destination,
        LocalDate departureDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        SeatClass seatClass,
        int remaining
) {}
