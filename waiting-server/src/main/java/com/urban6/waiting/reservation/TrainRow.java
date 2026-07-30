package com.urban6.waiting.reservation;

/**
 * 조회 결과 카드 한 줄. {@link TrainAvailability}는 JdbcClient가 컬럼에 그대로 매핑하는 값이라
 * 건드리지 않고, 운임·소요시간처럼 DB에 없는 파생값만 여기서 감싼다.
 */
public record TrainRow(TrainAvailability train, long fare, int durationMinutes) {}
