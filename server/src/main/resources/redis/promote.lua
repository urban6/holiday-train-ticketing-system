-- 대기열 → 활성 승격
-- KEYS[1] = waiting:{date}:{shard}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:{date}:{shard}    ZSet (member=uuid, score=만료 epoch ms)
-- KEYS[3] = poll:{date}:{shard}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = nowMillis
-- ARGV[2] = capacity
-- ARGV[3] = maxBatch
-- ARGV[4] = admissionGraceMillis
-- ARGV[5] = activeDeadlineMillis  active 키 자체의 만료 시각
-- return  = { promoted, active, waiting }
--
-- 세는 것과 꺼내는 것이 한 스크립트여야 정원을 넘기지 않는다.

local now = tonumber(ARGV[1])

-- 만료된 항목을 정원을 세기 전에 회수한다.
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
local active = redis.call('ZCARD', KEYS[2])

local room = math.min(tonumber(ARGV[2]) - active, tonumber(ARGV[3]))
if room <= 0 then
    return {0, active, redis.call('ZCARD', KEYS[1])}
end

local popped = redis.call('ZPOPMIN', KEYS[1], room)
if #popped == 0 then
    return {0, active, 0}
end

-- unpack으로 한 번에 넘기지 않는다. Lua 스택 한계(약 8000)에 걸리면 스크립트가 깨진다.
local expireAt = now + tonumber(ARGV[4])
for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[2], expireAt, popped[i])
    redis.call('ZREM', KEYS[3], popped[i])
end

redis.call('PEXPIREAT', KEYS[2], ARGV[5])

local promoted = #popped / 2
return {promoted, active + promoted, redis.call('ZCARD', KEYS[1])}
