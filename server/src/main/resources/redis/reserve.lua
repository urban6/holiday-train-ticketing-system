-- KEYS[1] = active:{date}:{shard} ZSet(member=uuid, score=만료 epoch ms)
-- KEYS[2] = stage:{date}:{shard} Hash(field=uuid, value=claimed | 로그인한 memberId)
-- ARGV[1]=uuid ARGV[2]=nowMillis ARGV[3]=reservationTtlMillis ARGV[4]=memberId
-- return 1 예약 시간 시작(같은 회원이 이미 시작했어도 1)
--        0 활성이 아니거나 이미 만료, 입장 확정 전, 또는 다른 회원에 묶임

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

local stage = redis.call('HGET', KEYS[2], ARGV[1])
-- 같은 회원이 다시 로그인해도 만료를 다시 찍지 않는다.
if stage == ARGV[4] then
    return 1
end
-- 다른 회원에 묶였으면 거절한다. 입장권 하나를 여러 계정이 나눠 쓰지 못하게 한다.
if stage ~= 'claimed' then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
return 1
