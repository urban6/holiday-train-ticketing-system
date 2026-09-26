-- 활성 슬롯의 만료를 now + ttl로 다시 찍는다
-- KEYS[1] = active:{date}:{shard}   ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- ARGV[3] = ttlMillis
-- return  = 1 성공 / 0 활성이 아니거나 이미 만료. 만료된 슬롯은 되살리지 않는다

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
return 1
