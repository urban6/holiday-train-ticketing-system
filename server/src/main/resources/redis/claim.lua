-- KEYS[1] = active:{date}:{shard} ZSet(member=uuid, score=만료 epoch ms)
-- KEYS[2] = stage:{date}:{shard} Hash(field=uuid, value=claimed | reserving)
-- ARGV[1]=uuid ARGV[2]=nowMillis ARGV[3]=sessionTtlMillis
-- return 1 확정됨(이미 확정됐어도 1) / 0 활성이 아니거나 이미 만료

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

-- 이미 단계가 있으면 만료를 다시 찍지 않는다. 다시 찍으면 만료 직전마다 불러 자리를 무기한 붙잡는다.
if redis.call('HSETNX', KEYS[2], ARGV[1], 'claimed') == 1 then
    redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])

    -- PEXPIRETIME은 Redis 7.0부터라 PTTL로 옮긴다. 음수(만료 없음)를 PEXPIRE에 넘기면 키가 즉시 지워진다.
    local keyTtl = redis.call('PTTL', KEYS[1])
    if keyTtl > 0 then
        redis.call('PEXPIRE', KEYS[2], keyTtl)
    end
end

return 1
