package com.urban6.waiting.reservation;

/** 페이지 흐름이라 ControllerAdvice 없이 컨트롤러가 잡아 안내 문구로 되돌린다. */
public sealed class ReservationException extends RuntimeException {

    private ReservationException(String message) {
        super(message);
    }

    public static final class InvalidRequest extends ReservationException {
        public InvalidRequest(String message) { super(message); }
    }

    public static final class SoldOut extends ReservationException {
        public SoldOut(String message) { super(message); }
    }

    public static final class LimitExceeded extends ReservationException {
        public LimitExceeded(String message) { super(message); }
    }
}
