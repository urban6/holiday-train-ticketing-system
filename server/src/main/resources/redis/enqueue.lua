-- 대기열 진입
-- KEYS[1] = waiting:holiday:{windowId}       ZSet   (member=uuid, score=seq)
-- KEYS[2] = waiting:holiday:{windowId}:seq   String (창별 단조 증가 시퀀스)
-- KEYS[3] = poll:holiday:{windowId}          ZSet   (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = waitingDeadlineMillis  (창 마감 + waitingGrace)
-- ARGV[3] = seqDeadlineMillis      (창 마감 + seqGrace, waitingDeadline보다 늦을 것)
-- ARGV[4] = firstPollDeadlineMillis (now + minPollInterval + pollGrace)
-- return  = 발급된 seq

local seq = redis.call('INCR', KEYS[2])
redis.call('ZADD', KEYS[1], seq, ARGV[1])

-- 첫 조회 기한을 미리 찍어 둔다. 여기서 넣지 않고 조회(status.lua)에서만 넣으면,
-- 진입한 뒤 한 번도 조회하지 않은 사람은 이 키에 없어 스위퍼가 영영 찾지 못한다.
-- 첫 조회는 0~하한 사이로 무작위로 늦춰지므로(waiting.js) 그 사이에 떠나면 실제로 생긴다.
--
-- 여기서는 순번을 모르니 주기 공식을 쓰지 않는다 — 진입 응답이 알려 주는 첫 주기는 항상
-- 하한이고(WaitingQueueService.enqueue), 기한도 그 하한으로 계산해 넘어온다.
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[1])

-- 절대 시각이라 최초 1회면 된다. PTTL < 0은 evict/오삭제로 TTL이 사라진 경우를 복구한다.
-- poll은 waiting과 항상 함께 쓰고 함께 지우므로 수명도 같이 간다.
if seq == 1 or redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIREAT', KEYS[1], ARGV[2])
    redis.call('PEXPIREAT', KEYS[2], ARGV[3])
    redis.call('PEXPIREAT', KEYS[3], ARGV[2])
end

return seq
