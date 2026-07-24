package com.urban6.waiting.reservation;

/**
 * 조회 결과 카드 한 줄. {@link TrainAvailability}(열차 + 고른 좌석의 잔여)에 화면 표시용
 * 운임과 소요시간을 더한 뷰 레코드다.
 *
 * <p>{@link TrainAvailability}는 JdbcClient가 조회 컬럼에 그대로 매핑하는 값이라 건드리지 않고,
 * 운임·소요시간처럼 DB에 없는 파생값만 여기서 감싼다. 소요시간은 분 단위로 담고
 * 시:분 표기는 화면에서 만든다.
 */
public record TrainRow(TrainAvailability train, long fare, int durationMinutes) {}
