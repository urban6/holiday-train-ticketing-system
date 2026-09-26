package com.urban6.waiting.reservation;

/** enum 이름이 곧 DB의 seat_class 값이자 폼 파라미터 값이다. */
public enum SeatClass {

    FIRST("특실"),
    STANDARD("일반");

    private final String label;

    SeatClass(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
