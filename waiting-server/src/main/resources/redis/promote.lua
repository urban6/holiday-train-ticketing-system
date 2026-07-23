-- 대기열 → 활성 승격
-- KEYS[1] = waiting:{holiday}:{windowId}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:{holiday}:{windowId}    ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = nowMillis
-- ARGV[2] = capacity              활성 정원
-- ARGV[3] = maxBatch              한 번에 승격할 최대 인원
-- ARGV[4] = admissionGraceMillis  승격 후 claim까지 주는 시간
-- ARGV[5] = activeDeadlineMillis  active 키 자체의 TTL (창 마감 + waitingGrace)
-- return  = { promoted, active, waiting }
--
-- 정원을 세는 것과 꺼내는 것이 한 스크립트여야 한다.
-- 따로 하면 그 사이에 만료·승격이 끼어들어 정원을 넘길 수 있다.

local now = tonumber(ARGV[1])

-- score(=만료시각)가 지난 항목을 회수한다. 정원을 세기 전에 해야 한다.
-- 활성 인원을 개별 키 + Redis TTL로 관리했다면 만료는 공짜지만 인원을 세려면 SCAN이 필요하다.
-- 만료시각을 score에 넣은 덕에 정리는 ZREMRANGEBYSCORE 한 번, 인원은 ZCARD로 정확히 나온다.
redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
local active = redis.call('ZCARD', KEYS[2])

-- maxBatch가 없으면 정원이 통째로 빈 순간 만 명을 한 번에 올리게 된다.
-- Redis는 단일 스레드라 그동안 다른 모든 요청이 멈춘다 — 지연 스파이크의 원인.
local room = math.min(tonumber(ARGV[2]) - active, tonumber(ARGV[3]))
if room <= 0 then
    return {0, active, redis.call('ZCARD', KEYS[1])}
end

-- ZPOPMIN은 꺼내기와 지우기가 한 연산이라 ZRANGE + ZREM으로 나눌 필요가 없다.
-- 반환은 { member1, score1, member2, score2, ... } 평면 배열이다.
local popped = redis.call('ZPOPMIN', KEYS[1], room)
if #popped == 0 then
    return {0, active, 0}
end

-- 인자를 모아 ZADD 한 번으로 처리할 수도 있지만, unpack은 Lua 스택 한계(약 8000)가 있다.
-- 루프는 maxBatch에만 비례하므로 상한이 예측 가능하다.
local expireAt = now + tonumber(ARGV[4])
for i = 1, #popped, 2 do
    redis.call('ZADD', KEYS[2], expireAt, popped[i])
end

-- 절대 시각이라 매 주기 걸어도 결과가 같다.
redis.call('PEXPIREAT', KEYS[2], ARGV[5])

local promoted = #popped / 2
return {promoted, active + promoted, redis.call('ZCARD', KEYS[1])}
