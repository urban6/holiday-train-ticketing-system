-- 회원 계정
--
-- 지금 이 시스템에 필요한 최소한만 담는다. 권한·이메일·전화번호는 쓰는 곳이 없다.
-- 실제 코레일이라면 회원번호·본인확인·등급이 붙겠지만, 이번 페이즈의 목적은
-- "대기열을 통과한 사람만 로그인할 수 있다"를 성립시키는 것이지 회원 도메인이 아니다.

CREATE TABLE member (
    id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    login_id   VARCHAR(30)  NOT NULL UNIQUE,
    -- BCrypt 해시는 60자 고정이다. 여유를 둔 것은 나중에 알고리즘을 바꿀 때
    -- '{bcrypt}' 같은 접두사가 붙는 형식(DelegatingPasswordEncoder)으로 옮겨갈 수 있어서다.
    password   VARCHAR(72)  NOT NULL,
    name       VARCHAR(50)  NOT NULL,
    -- TIMESTAMPTZ다. TIMESTAMP로 두면 시각에 시간대가 없어서 KST/UTC가 섞이는 순간
    -- 어느 쪽으로 읽어야 하는지 알 수 없게 된다. 대기열 창(window)이 KST 기준이라 특히 그렇다.
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- login_id의 UNIQUE 제약이 인덱스를 만든다. 조회는 login_id로만 하므로 추가 인덱스가 없다.
