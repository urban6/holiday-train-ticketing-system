-- KEYS[1] = active:{date}:{shard} ZSet(member=uuid, score=만료 epoch ms)
-- KEYS[2] = stage:{date}:{shard} Hash(field=uuid, value=claimed | reserving)
-- ARGV[1]=uuid ARGV[2]=nowMillis ARGV[3]=reservationTtlMillis
-- return 1 예약 시간 시작(이미 시작했어도 1) / 0 활성이 아니거나 이미 만료, 또는 입장 확정 전

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

local stage = redis.call('HGET', KEYS[2], ARGV[1])
-- 다시 로그인해도 만료를 다시 찍지 않는다.
if stage == 'reserving' then
    return 1
end
if stage ~= 'claimed' then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
redis.call('HSET', KEYS[2], ARGV[1], 'reserving')
return 1
