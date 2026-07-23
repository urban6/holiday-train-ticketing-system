-- 활성 슬롯의 만료를 now + ttl로 다시 찍는다
-- KEYS[1] = active:{holiday}:{windowId}   ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- ARGV[3] = ttlMillis
-- return  = 1 성공 / 0 활성이 아니거나 이미 만료
--
-- 부르는 곳이 둘이고, 어느 쪽이든 "지금부터 다시 이만큼"이라는 뜻은 같다. ttl만 다르다.
--   입장 확정(claim)  : admissionGrace(짧다) → sessionTtl.  입장권을 실제로 썼다는 표시다.
--   로그인            : sessionTtl → reservationTtl.        예약에 주는 시간의 시작점이다.
--
-- 이미 만료된 슬롯은 되살리지 않는다(0을 반환). 그래서 회수된 자리를 로그인으로
-- 다시 붙잡을 수 없고, 정원은 언제나 promote.lua의 회수 결과와 일치한다.
--
-- 새로고침으로 이탈한 유령은 폴링을 하지 않아 claim까지 오지 못하고,
-- grace가 지나면 promote.lua의 ZREMRANGEBYSCORE가 슬롯을 회수한다.

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
return 1
