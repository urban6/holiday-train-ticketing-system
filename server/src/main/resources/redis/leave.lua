-- 대기열 이탈
-- KEYS[1] = waiting:{date}:{shard}   ZSet (member=uuid, score=seq)
-- KEYS[2] = poll:{date}:{shard}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = uuid
-- return  = 1 실제로 뺐음 / 0 이미 없었음
--
-- active는 건드리지 않는다. 입장해서 다음 화면으로 넘어갈 때도 pagehide beacon이 오므로,
-- active까지 지우면 방금 받은 자리를 스스로 반납하게 된다.

local removed = redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])

return removed
