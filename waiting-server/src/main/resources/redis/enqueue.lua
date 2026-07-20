-- 대기열 진입
-- KEYS[1] = waiting:{holiday}:{windowId}       ZSet   (member=uuid, score=seq)
-- KEYS[2] = waiting:{holiday}:{windowId}:seq   String (창별 단조 증가 시퀀스)
-- ARGV[1] = uuid
-- ARGV[2] = waitingDeadlineMillis  (창 마감 + waitingGrace)
-- ARGV[3] = seqDeadlineMillis      (창 마감 + seqGrace, waitingDeadline보다 늦을 것)
-- return  = 발급된 seq

local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])

-- 절대 시각이므로 최초 1회만 설정하면 된다.
-- PTTL < 0 은 evict/오삭제로 ZSet이 재생성되어 TTL이 없는 경우를 복구한다.
if seq == 1 or redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIREAT', KEYS[1], ARGV[2])
    redis.call('PEXPIREAT', KEYS[2], ARGV[3])
end

return seq
