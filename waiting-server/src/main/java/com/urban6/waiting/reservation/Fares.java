package com.urban6.waiting.reservation;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 구간+좌석종류별 고정 운임(원). 시연·학습용이라 실제 요금 체계가 아니라 화면에 보일 참조값이다.
 *
 * <p>DB에 요금을 모델링하지 않는다 — 이 프로젝트의 관심사는 대기열·동시성이지 요금 산정이 아니다.
 * 운임은 조회 결과 카드에만 쓰이는 뷰 전용 값이라 여기 한 곳에 둔다(구간×좌석 매트릭스라
 * application.yml보다 코드가 덜 번잡하다).
 *
 * <p>구간은 방향을 가리지 않는다 — 서울↔부산은 어느 쪽으로 가든 같은 운임으로 본다.
 */
@Component
public class Fares {

    /** 방향 무관 구간 키 → (일반, 특실) 운임. */
    private static final Map<String, long[]> TABLE = Map.of(
            routeKey("서울", "부산"), new long[]{59_800, 83_700},
            routeKey("서울", "광주"), new long[]{46_800, 65_500}
    );

    /** 표에 없는 구간의 기본 운임. */
    private static final long[] DEFAULT = {30_000, 42_000};

    public long of(String origin, String destination, SeatClass seatClass) {
        long[] fares = TABLE.getOrDefault(routeKey(origin, destination), DEFAULT);
        return seatClass == SeatClass.FIRST ? fares[1] : fares[0];
    }

    // 두 역을 정렬해 합치므로 (서울,부산)과 (부산,서울)이 같은 키가 된다.
    private static String routeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "-" + b : b + "-" + a;
    }
}
