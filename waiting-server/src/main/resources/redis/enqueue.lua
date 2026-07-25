-- 대기열 진입
-- KEYS[1] = waiting:holiday:{windowId}       ZSet   (member=uuid, score=seq)
-- KEYS[2] = waiting:holiday:{windowId}:seq   String (창별 단조 증가 시퀀스)
-- KEYS[3] = seen:holiday:{windowId}          ZSet   (member=uuid, score=마지막 확인 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = waitingDeadlineMillis  (창 마감 + waitingGrace)
-- ARGV[3] = seqDeadlineMillis      (창 마감 + seqGrace, waitingDeadline보다 늦을 것)
-- ARGV[4] = nowMillis
-- return  = 발급된 seq

local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])

-- 진입 시각이 곧 첫 "마지막 확인" 시각이다. 여기서 넣지 않고 조회(status.lua)에서만 넣으면,
-- 진입한 뒤 한 번도 조회하지 않은 사람은 seen에 없어 스위퍼가 영영 찾지 못한다.
-- 첫 조회는 0~폴링주기 사이로 무작위로 늦춰지므로(waiting.js) 그 사이에 떠나면 실제로 생긴다.
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])

-- 절대 시각이라 최초 1회면 된다. PTTL < 0은 evict/오삭제로 TTL이 사라진 경우를 복구한다.
-- seen은 waiting과 항상 함께 쓰고 함께 지우므로 수명도 같이 간다.
if seq == 1 or redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIREAT', KEYS[1], ARGV[2])
    redis.call('PEXPIREAT', KEYS[2], ARGV[3])
    redis.call('PEXPIREAT', KEYS[3], ARGV[2])
end

return seq
