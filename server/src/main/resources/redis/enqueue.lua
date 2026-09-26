-- 대기열 진입
-- KEYS[1] = waiting:{date}:{shard}       ZSet   (member=uuid, score=seq)
-- KEYS[2] = waiting:{date}:{shard}:seq   String (창별 단조 증가 시퀀스)
-- KEYS[3] = poll:{date}:{shard}          ZSet   (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = waitingDeadlineMillis
-- ARGV[3] = seqDeadlineMillis      (waitingDeadline보다 늦을 것)
-- ARGV[4] = firstPollDeadlineMillis
-- return  = 발급된 seq

local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])

-- 진입만 하고 한 번도 조회하지 않은 채 떠난 사람도 스위퍼가 찾을 수 있게 첫 기한을 여기서 찍는다.
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])

-- PTTL < 0은 evict나 오삭제로 TTL이 사라진 경우를 복구한다.
if seq == 1 or redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIREAT', KEYS[1], ARGV[2])
    redis.call('PEXPIREAT', KEYS[2], ARGV[3])
    redis.call('PEXPIREAT', KEYS[3], ARGV[2])
end

return seq
