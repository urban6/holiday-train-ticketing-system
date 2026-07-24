package com.urban6.waiting.reservation;

/**
 * {@link com.urban6.waiting.member.MemberException}과 같은 형태다.
 * {@code @RestControllerAdvice}는 붙이지 않는다 — 예약도 JSON API가 아니라 페이지 흐름이라,
 * 실패는 상태 코드가 아니라 예약 화면을 다시 그리며 안내 문구로 드러나야 하기 때문이다.
 * 컨트롤러가 이 예외를 잡아 플래시로 되돌린다.
 */
public sealed class ReservationException extends RuntimeException {

    private ReservationException(String message) {
        super(message);
    }

    /** 요청 자체가 성립하지 않는다(예: 인원이 1 미만). 폼의 min 힌트를 우회한 요청을 여기서 막는다. */
    public static final class InvalidRequest extends ReservationException {
        public InvalidRequest(String message) { super(message); }
    }

    /** 고른 좌석종류의 잔여석이 요청 인원보다 적다. */
    public static final class SoldOut extends ReservationException {
        public SoldOut(String message) { super(message); }
    }

    /** 1인당 예약 한도(reservation.max-per-member)에 도달했다. */
    public static final class LimitExceeded extends ReservationException {
        public LimitExceeded(String message) { super(message); }
    }
}
