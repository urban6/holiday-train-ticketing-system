-- 입장 확정 (승격된 사용자가 입장권을 실제로 쓴다)
-- KEYS[1] = active:{holiday}:{windowId}   ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- ARGV[3] = sessionTtlMillis
-- return  = 1 성공 / 0 활성이 아니거나 이미 만료
--
-- 승격 직후의 만료시각은 admissionGrace(짧다)뿐이다.
-- 여기서 sessionTtl로 늘리는 것이 "입장권을 썼다"는 표시가 된다.
-- 새로고침으로 이탈한 유령은 폴링을 하지 않아 이 호출까지 오지 못하고,
-- grace가 지나면 promote.lua의 ZREMRANGEBYSCORE가 슬롯을 회수한다.

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
return 1
