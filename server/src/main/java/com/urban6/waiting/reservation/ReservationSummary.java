package com.urban6.waiting.reservation;

import java.time.LocalDate;
import java.time.LocalTime;

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
