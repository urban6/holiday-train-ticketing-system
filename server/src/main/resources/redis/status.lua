-- 대기 순번 / 입장 여부 조회
-- KEYS[1] = waiting:{date}:{shard}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:{date}:{shard}    ZSet (member=uuid, score=만료 epoch ms)
-- KEYS[3] = poll:{date}:{shard}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- ARGV[3] = millisPerRank    순번 1당 주기 증가분(소수)
-- ARGV[4] = minPollMillis
-- ARGV[5] = maxPollMillis
-- ARGV[6] = pollGraceMillis
-- return  = { state, rank, total, expireAt, pollAfter }
--   state     0=대기 중, 1=입장, -1=없음(만료·미발급·다른 창)
--   rank      0-based, 대기 중이 아니면 -1
--   expireAt  활성 만료 epoch ms, 활성이 아니면 -1
--   pollAfter 다음 조회까지 기다릴 ms, 대기 중이 아니면 0
-- 없는 값을 nil 대신 -1로 두는 이유: Lua 테이블은 nil에서 잘려 뒤 필드가 사라진다.

local total = redis.call('ZCARD', KEYS[1])
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])

-- rank > 0으로 바꾸지 말 것. Lua에서 0은 truthy라 1등(rank=0)만 이 분기를 놓친다.
if rank then
    -- 뒤쪽일수록 성기게 묻게 한다.
    local pollAfter = math.floor(rank * tonumber(ARGV[3]))
    local minPoll = tonumber(ARGV[4])
    local maxPoll = tonumber(ARGV[5])
    if pollAfter < minPoll then
        pollAfter = minPoll
    elseif pollAfter > maxPoll then
        pollAfter = maxPoll
    end

    -- 이 조회가 하트비트다. 여기서 다음 기한을 찍지 않으면 스위퍼가 멀쩡한 사용자를 회수한다.
    -- 사람마다 주기가 달라서 마지막 조회 시각이 아니라 기한을 넣는다.
    redis.call('ZADD', KEYS[3], tonumber(ARGV[2]) + pollAfter + tonumber(ARGV[6]), ARGV[1])
    return {0, rank, total, -1, pollAfter}
end

local expireAt = redis.call('ZSCORE', KEYS[2], ARGV[1])
if expireAt and tonumber(expireAt) > tonumber(ARGV[2]) then
    -- ZSCORE는 문자열이라 그대로 담으면 호출부의 List<Long> 캐스팅이 깨진다.
    return {1, -1, total, math.floor(tonumber(expireAt)), 0}
end

return {-1, -1, total, -1, 0}
