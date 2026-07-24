package com.urban6.waiting.reservation;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 내 예약 목록 한 줄. reservation과 train을 조인해 화면에 필요한 열차 정보까지 담는다.
 *
 * <p>{@link TrainAvailability}와 같은 매핑 규약을 따른다 — SELECT 컬럼(별칭)이 컴포넌트에 대응한다.
 * 정렬은 created_at으로 하지만(최근 예약 먼저) 화면에 시각을 표시하지 않아 컴포넌트에는 두지 않는다.
 */
public record ReservationSummary(
        long reservationId,
        String trainNo,
        String origin,
        String destination,
        LocalDate departureDate,
        LocalTime departureTime,
        LocalTime arrivalTime,
        SeatClass seatClass,
        int passengerCount
) {}
