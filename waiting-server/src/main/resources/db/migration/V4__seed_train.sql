-- 시드 열차
--
-- 조회 화면은 아직 열차 등록 기능이 없다. 조회·예약 흐름을 확인하려면 열차가 먼저 있어야 하므로
-- 몇 편을 넣는다. 조회 필터(출발지·도착지·출발일·출발시간·좌석종류)를 눈으로 검증할 수 있게
-- 같은 노선의 여러 시간대 + 다른 노선을 섞는다.
--
-- 출발일은 now()가 아니라 고정 미래일(명절 즈음)로 둔다. now() 의존 시드는 실행 시점마다 값이 달라져
-- 테스트가 재현되지 않는다. 대기열 창(window)이 날짜 기준인 것과 같은 이유로 날짜를 못 박는다.
--
-- 재고는 작게 둔다. 매진과 초과예약 경합을 화면에서 바로 볼 수 있어야 하기 때문이다.

INSERT INTO train (train_no, origin, destination, departure_date, departure_time, arrival_time) VALUES
    ('KTX 101', '서울', '부산', DATE '2026-09-24', TIME '06:00', TIME '08:40'),
    ('KTX 103', '서울', '부산', DATE '2026-09-24', TIME '09:30', TIME '12:10'),
    ('KTX 201', '서울', '광주', DATE '2026-09-24', TIME '07:00', TIME '08:50'),
    ('KTX 305', '부산', '서울', DATE '2026-09-24', TIME '10:00', TIME '12:40');

-- 모든 열차에 특실 20석, 일반 40석을 채운다. remaining은 초기값으로 total과 같다.
INSERT INTO train_seat_inventory (train_id, seat_class, total, remaining)
SELECT id, 'FIRST', 20, 20 FROM train;

INSERT INTO train_seat_inventory (train_id, seat_class, total, remaining)
SELECT id, 'STANDARD', 40, 40 FROM train;
