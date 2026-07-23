-- 시연용 더미 대기자 시드
-- KEYS[1] = waiting:{holiday}:{windowId}       ZSet   (member=uuid, score=seq)
-- KEYS[2] = waiting:{holiday}:{windowId}:seq   String (창별 단조 증가 시퀀스)
-- ARGV[1] = count                  이번 호출에 넣을 인원
-- ARGV[2] = waitingDeadlineMillis  (창 마감 + waitingGrace)
-- ARGV[3] = seqDeadlineMillis      (창 마감 + seqGrace)
-- return  = 마지막으로 발급된 seq
--
-- enqueue.lua와 같은 자료구조에 같은 방식으로 쓴다. 다른 점은 한 번에 여러 명이라는 것뿐이다.
-- 실사용자와 같은 seq 카운터를 쓰므로 봇과 사람이 섞여도 진입 순서가 그대로 보존된다.

local count = tonumber(ARGV[1])

-- INCR을 count번 부르는 대신 블록을 통째로 예약한다.
-- 그래야 이 스크립트가 도는 동안 실사용자가 진입해도 순번이 겹치지 않는다.
local last = redis.call('INCRBY', KEYS[2], count)
local first = last - count + 1

-- ZADD에 인자를 몰아 한 번에 넣을 수도 있지만 unpack은 Lua 스택 한계(약 8000)가 있다.
-- 호출부가 청크를 잘라 주므로 루프 횟수의 상한도 거기서 정해진다.
for seq = first, last do
    -- 실사용자 토큰은 UUID라 'bot:'으로 시작할 수 없다. 이 접두사가 곧 봇의 식별자다.
    redis.call('ZADD', KEYS[1], seq, 'bot:' .. seq)
end

-- enqueue.lua와 같은 조건이다. 절대 시각이므로 최초 1회만 설정하면 되고,
-- PTTL < 0은 evict/오삭제로 TTL이 사라진 경우를 복구한다.
if first == 1 or redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIREAT', KEYS[1], ARGV[2])
    redis.call('PEXPIREAT', KEYS[2], ARGV[3])
end

return last
