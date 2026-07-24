-- 시연 계정 비밀번호를 test1234! → qwer1234! 로 교체한다.
--
-- test1234! 가 유출 코퍼스에 흔히 포함된 값이라 Chrome이 로그인 시 "데이터 유출" 경고를 띄웠다.
-- V2는 이미 적용돼 있어 그 파일을 고치면 Flyway 체크섬이 깨지므로, 여기서 UPDATE로 덮는다.
-- 신규 DB에서도 V2가 test1234! 를 시드한 뒤 이 마이그레이션이 qwer1234! 로 바꿔 결과가 일관된다.
--
-- 해시는 BCryptPasswordEncoder().encode("qwer1234!") 와 호환되는 cost-10 BCrypt다.
UPDATE member
SET password = '$2y$10$qwby5EkxL4TjlSQau.PlouO8ORZRTYGbDxiqe4MetvEVu.nVV6zxO'
WHERE login_id IN ('test01', 'test02', 'test03');
