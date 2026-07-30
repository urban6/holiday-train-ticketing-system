package com.urban6.waiting.reservation;

/**
 * 좌석종류. 좌석 하나하나가 아니라 특실/일반의 구분만 다룬다.
 *
 * <p>enum 이름(FIRST/STANDARD)이 곧 DB의 seat_class 값이자 폼 파라미터 값이다.
 * 한글 라벨도 여기 한 곳에만 둔다.
 */
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
