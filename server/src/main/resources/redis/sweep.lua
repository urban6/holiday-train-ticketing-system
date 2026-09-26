-- 폴링이 끊긴 대기자 회수
-- KEYS[1] = waiting:{date}:{shard}   ZSet (member=uuid, score=seq)
-- KEYS[2] = poll:{date}:{shard}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = nowMillis
-- ARGV[2] = maxSweep
-- return  = 회수한 인원
--
-- ZREMRANGEBYSCORE에는 LIMIT이 없어 maxSweep 상한을 걸 수 없으므로 멤버를 먼저 꺼낸다.

local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
if #stale == 0 then
    return 0
end

-- unpack 대신 루프를 쓰는 이유는 promote.lua와 같다(Lua 스택 한계).
for i = 1, #stale do
    redis.call('ZREM', KEYS[1], stale[i])
    redis.call('ZREM', KEYS[2], stale[i])
end

return #stale
