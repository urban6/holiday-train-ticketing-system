package com.urban6.waiting.reservation;

import java.time.LocalDate;
import java.time.LocalTime;

/** JdbcClient가 레코드 생성자에 매핑하므로 SELECT 별칭이 컴포넌트 이름·순서와 맞아야 한다. */
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
