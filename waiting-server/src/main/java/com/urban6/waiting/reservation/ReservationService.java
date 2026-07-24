package com.urban6.waiting.reservation;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 열차 조회와 예약. 이 프로젝트에서 {@code @Transactional}을 처음 쓰는 지점이다 —
 * 재고 차감과 예약 INSERT가 두 행에 걸친 쓰기라 하나로 묶여야 하기 때문이다.
 * 트랜잭션 매니저는 spring-boot-starter-jdbc가 자동 구성한다.
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final TrainRepository trainRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationProperties properties;
    private final Fares fares;

    /**
     * 조회 결과에 화면 표시용 운임·소요시간을 붙여 돌려준다. 조회 자체는 리포지토리가 그대로 하고,
     * DB에 없는 파생값(고정 운임, 출발~도착 소요시간)만 여기서 {@link TrainRow}로 감싼다.
     */
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

    public int maxPerMember() {
        return properties.maxPerMember();
    }

    /**
     * 예약을 확정한다. 하나의 트랜잭션 안에서 아래 순서로 진행하며, 어느 단계가 실패하면 전체가
     * 롤백되어 재고도 예약도 남지 않는다.
     *
     * <ol>
     *   <li><b>회원 행 잠금</b> — 같은 회원의 동시 요청을 직렬화한다. 이게 없으면 두 요청이 모두
     *       한도 미만을 읽어 6건을 넘길 수 있다(READ COMMITTED의 count 레이스).</li>
     *   <li><b>한도 검사</b> — 값싼 count를 재고 차감보다 먼저 본다. 잠금 뒤라 앞선 커밋이 반드시
     *       반영된 값을 읽는다.</li>
     *   <li><b>재고 원자적 차감</b> — 조건부 UPDATE. 0행이면 매진이다. 서로 다른 회원 사이의
     *       초과예약은 이 한 문장이 막는다.</li>
     *   <li><b>예약 INSERT</b>.</li>
     * </ol>
     *
     * <p>self-invocation이 아니라 컨트롤러가 이 메서드를 외부 호출해야 프록시가 트랜잭션을 건다.
     */
    @Transactional
    public void reserve(long memberId, long trainId, SeatClass seatClass, int passengers) {
        // 폼의 min="1"은 클라이언트 힌트일 뿐이다. 0이나 음수가 그대로 넘어오면 조건부 차감을 통과해
        // DB CHECK 위반(ReservationException이 아닌 예외)으로 500이 되므로, 여기서 먼저 막는다.
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

    /**
     * 예약을 취소한다. 소유권 검사·삭제·재고 반납을 한 트랜잭션으로 묶는다.
     *
     * <p>삭제가 곧 소유권 검사다({@code member_id} 조건). 내 예약이 아니거나 이미 취소된 뒤라
     * 지운 행이 없으면 반납하지 않고 실패로 알린다. 삭제로 슬롯도 함께 반납되므로(count가 줄어) 한도도 회복된다.
     */
    @Transactional
    public void cancel(long memberId, long reservationId) {
        var cancelled = reservationRepository.cancelOwned(reservationId, memberId)
                .orElseThrow(() -> new ReservationException.InvalidRequest("취소할 예약을 찾을 수 없습니다."));
        reservationRepository.restoreInventory(
                cancelled.trainId(), cancelled.seatClass(), cancelled.passengerCount());
    }
}
