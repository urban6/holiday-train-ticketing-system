-- 열차와 좌석 재고
--
-- 이번 페이즈의 목적은 "대기열을 통과해 로그인한 사람이 열차를 조회하고 예약한다"이고,
-- 그 예약이 정원(잔여석)을 넘지 못하게 막는 것이다. 좌석 하나하나(좌석번호·호차)는 아직 다루지 않는다.
-- 예약은 "열차 + 좌석종류(특실/일반)"까지만 고른다.
--
-- 재고를 train 컬럼(first_remaining/standard_remaining)이 아니라 별도 테이블 행으로 둔다.
-- 그래야 원자적 차감 UPDATE가 (train_id, seat_class) 단일 행을 좁게 잠그고,
-- 특실과 일반의 경합이 서로 다른 행이라 격리된다.

CREATE TABLE train (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    train_no       VARCHAR(20)  NOT NULL,          -- 표시용 편명 (예: KTX 101)
    origin         VARCHAR(30)  NOT NULL,          -- 출발지
    destination    VARCHAR(30)  NOT NULL,          -- 도착지
    departure_date DATE         NOT NULL,          -- 출발일
    departure_time TIME         NOT NULL,          -- 출발시간
    arrival_time   TIME         NOT NULL,          -- 도착시간 (표시용)
    -- member.created_at과 같은 이유로 TIMESTAMPTZ다. 시간대 없는 TIMESTAMP는 KST/UTC를 섞는다.
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 조회는 항상 출발지+도착지+출발일로 걸러 출발시간 순으로 본다.
-- member.login_id의 UNIQUE 인덱스처럼 이 테이블의 존재 이유 자체가 필터 조회라,
-- "조회는 한 컬럼으로만 하므로 추가 인덱스가 없다"는 member의 관례에 대한 정당한 예외로
-- 복합 인덱스 하나를 둔다.
CREATE INDEX idx_train_search ON train (origin, destination, departure_date, departure_time);

CREATE TABLE train_seat_inventory (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    train_id   BIGINT       NOT NULL REFERENCES train(id),
    seat_class VARCHAR(10)  NOT NULL,              -- FIRST(특실) | STANDARD(일반)
    total      INTEGER      NOT NULL,
    remaining  INTEGER      NOT NULL,
    -- 한 열차의 좌석종류는 특실·일반 각각 한 행뿐이다. 원자적 차감이 이 단일 행을 타깃한다.
    CONSTRAINT uq_train_seat_class   UNIQUE (train_id, seat_class),
    CONSTRAINT ck_seat_class         CHECK (seat_class IN ('FIRST', 'STANDARD')),
    -- 조건부 UPDATE(remaining >= :n)가 이미 음수를 막지만, DB 차원의 최종 안전망을 하나 더 둔다.
    -- 애플리케이션 가드가 어떤 이유로 뚫려도 재고가 음수로 내려가는 일은 없어야 한다.
    CONSTRAINT ck_remaining_nonneg   CHECK (remaining >= 0),
    CONSTRAINT ck_remaining_le_total CHECK (remaining <= total)
);
