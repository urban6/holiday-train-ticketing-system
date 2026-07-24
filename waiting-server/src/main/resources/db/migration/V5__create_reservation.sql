-- 예약
--
-- 한 행 = 한 건의 예약(회원 + 열차 + 좌석종류 + 인원). 좌석번호는 아직 없다.
-- 재고 차감(train_seat_inventory)과 이 INSERT는 같은 트랜잭션으로 묶여 all-or-nothing이 된다 —
-- 하나라도 실패하면 재고도 예약도 남지 않는다.

CREATE TABLE reservation (
    id              BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id       BIGINT       NOT NULL REFERENCES member(id),
    train_id        BIGINT       NOT NULL REFERENCES train(id),
    seat_class      VARCHAR(10)  NOT NULL,
    passenger_count INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_res_seat_class CHECK (seat_class IN ('FIRST', 'STANDARD')),
    CONSTRAINT ck_res_passengers CHECK (passenger_count > 0)
);

-- 내 예약 목록 조회와 1인당 최대 6건 한도 검사(count)가 모두 member_id로 걸린다.
CREATE INDEX idx_reservation_member ON reservation (member_id);
