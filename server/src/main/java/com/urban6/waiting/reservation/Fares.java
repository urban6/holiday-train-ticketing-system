package com.urban6.waiting.reservation;

import java.util.Map;
import org.springframework.stereotype.Component;

/** 화면 표시용 고정 운임(원). 실제 요금 체계가 아니며 구간 방향은 가리지 않는다. */
@Component
public class Fares {

    /** 구간 키 → (일반, 특실) 운임. */
    private static final Map<String, long[]> TABLE = Map.of(
            routeKey("서울", "부산"), new long[]{59_800, 83_700},
            routeKey("서울", "광주"), new long[]{46_800, 65_500}
    );

    private static final long[] DEFAULT = {30_000, 42_000};

    public long of(String origin, String destination, SeatClass seatClass) {
        long[] fares = TABLE.getOrDefault(routeKey(origin, destination), DEFAULT);
        return seatClass == SeatClass.FIRST ? fares[1] : fares[0];
    }

    private static String routeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "-" + b : b + "-" + a;
    }
}
