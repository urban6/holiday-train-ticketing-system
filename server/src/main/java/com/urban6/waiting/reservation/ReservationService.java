package com.urban6.waiting.reservation;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final TrainRepository trainRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationProperties properties;
    private final Fares fares;

    @Transactional(readOnly = true)
    public List<TrainRow> search(String origin, String destination,
                                 LocalDate departureDate, LocalTime departureTime,
                                 SeatClass seatClass) {
        return trainRepository.search(origin, destination, departureDate, departureTime, seatClass)
                .stream()
                .map(train -> new TrainRow(
                        train,
                        fares.of(train.origin(), train.destination(), train.seatClass()),
                        (int) Duration.between(train.departureTime(), train.arrivalTime()).toMinutes()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReservationSummary> myReservations(long memberId) {
        return reservationRepository.findByMember(memberId);
    }

    /**
     * 회원 행 잠금 → 한도 검사 → 재고 조건부 차감 → INSERT 순서다. 잠금이 없으면 같은 회원의
     * 동시 요청이 모두 한도 미만을 읽고 통과한다.
     */
    @Transactional
    public void reserve(long memberId, long trainId, SeatClass seatClass, int passengers) {
        // 0이나 음수는 조건부 차감을 통과해 DB CHECK 위반(500)이 되므로 먼저 막는다.
        if (passengers < 1) {
            throw new ReservationException.InvalidRequest("예약 인원은 1명 이상이어야 합니다.");
        }

        reservationRepository.lockMember(memberId);

        if (reservationRepository.countByMember(memberId) >= properties.maxPerMember()) {
            throw new ReservationException.LimitExceeded(
                    "예약은 최대 " + properties.maxPerMember() + "건까지 가능합니다.");
        }

        if (reservationRepository.decrementInventory(trainId, seatClass, passengers) == 0) {
            throw new ReservationException.SoldOut("선택하신 좌석의 잔여석이 부족합니다.");
        }

        reservationRepository.insert(memberId, trainId, seatClass, passengers);
    }

    @Transactional
    public void cancel(long memberId, long reservationId) {
        var cancelled = reservationRepository.cancelOwned(reservationId, memberId)
                .orElseThrow(() -> new ReservationException.InvalidRequest("취소할 예약을 찾을 수 없습니다."));
        reservationRepository.restoreInventory(
                cancelled.trainId(), cancelled.seatClass(), cancelled.passengerCount());
    }
}
