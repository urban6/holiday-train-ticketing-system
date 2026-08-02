-- 시드 계정
--
-- 회원가입 화면은 아직 없다. 로그인 흐름을 확인하려면 계정이 먼저 있어야 하므로
-- 테스트 계정 세 개를 넣는다. 비밀번호는 셋 다 'test1234!'다.
--
-- 해시는 지어낸 값이 아니라 BCryptPasswordEncoder().encode("test1234!")로 실제 생성한 것이다.
-- BCrypt는 salt가 매번 달라 같은 비밀번호라도 계정마다 값이 다르다 — 복붙해서 돌려쓰면 안 된다.
--
-- 학습용 프로젝트라 평문 비밀번호를 여기 적어 둔다.
-- 실제 서비스라면 시드 계정 자체가 있어서는 안 된다.

INSERT INTO member (login_id, password, name) VALUES
    ('test01', '$2a$10$/CDpLcLOqqQJLAJpXXG1belLWD35h5tg/wh62RDZwPX7lpkX/awj2', '김명절'),
    ('test02', '$2a$10$ZbKDs1ItWBFvI8Az8hvrMOeEc99OOsgbrU3R9LzEXbY3M1UjU9.ES', '이귀성'),
    ('test03', '$2a$10$pSZnRVIV1pzaVBGAIaQ0/OyHZVzZ8w68v1hOeXsdTNWt2SQeeuFii', '박열차');
