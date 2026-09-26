package com.urban6.waiting.reservation;

/** DB에 없는 파생값(운임·소요시간)을 조회 결과에 붙인다. */
public record TrainRow(TrainAvailability train, long fare, int durationMinutes) {}
