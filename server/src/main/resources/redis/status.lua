-- 대기 순번 / 입장 여부 조회
-- KEYS[1] = waiting:{date}:{shard}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:{date}:{shard}    ZSet (member=uuid, score=만료 epoch ms)
-- KEYS[3] = poll:{date}:{shard}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- ARGV[3] = millisPerRank    순번 1당 주기 증가분(소수). QueueProperties가 접어서 넘긴다
-- ARGV[4] = minPollMillis    주기 하한
-- ARGV[5] = maxPollMillis    주기 상한
-- ARGV[6] = pollGraceMillis  기한에 얹는 유예
-- return  = { state, rank, total, expireAt, pollAfter }
--   state     0=대기 중, 1=입장, -1=없음(만료·미발급·다른 창)
--   rank      0-based, 대기 중이 아니면 -1
--   expireAt  활성 만료 epoch ms, 활성이 아니면 -1
--   pollAfter 다음 조회까지 기다릴 ms. 대기 중이 아니면 0 —
--             rank·expireAt의 -1과 달리 "값이 없다"가 아니라 "더 묻지 마라"다.
-- 없는 값을 nil이 아니라 -1로 두는 이유: Lua 테이블은 nil에서 잘려 나가 뒤 필드가 사라진다.
--
-- ZCARD와 ZRANK를 따로 부르면 그 사이에 큐가 변해 "앞 + 뒤 + 1 = 전체"가 어긋난다.

local total = redis.call('ZCARD', KEYS[1])
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])

-- 없으면 nil(=false)이라 존재 여부만 본다.
-- rank > 0으로 바꾸지 말 것 — Lua에서 0은 truthy라 1등(rank=0)만 이 분기를 놓치고 state -1을 받는다.
if rank then
    -- 뒤쪽 사람일수록 성기게 묻게 한다. 앞의 인원이 다 빠지기 전에는 답이 "아직 아닙니다"로
    -- 같은데, 모두에게 같은 주기를 주면 그 낭비가 대기 인원만큼 곱해진다.
    -- 남은 시간(순번 × millisPerRank)은 승격 상한으로 잰 하한이라, 주기를 여기에 맞추면
    -- 자기 차례를 자면서 넘기지 않는다. 근거는 QueueProperties.millisPerRank() 참고.
    local pollAfter = math.floor(rank * tonumber(ARGV[3]))
    local minPoll = tonumber(ARGV[4])
    local maxPoll = tonumber(ARGV[5])
    if pollAfter < minPoll then
        pollAfter = minPoll
    elseif pollAfter > maxPoll then
        pollAfter = maxPoll
    end

    -- 이 조회 자체가 하트비트다. 살아 있다는 신호가 이것 말고 없으므로, 여기서 다음 기한을
    -- 다시 찍지 않으면 스위퍼가 멀쩡한 사용자를 이탈로 보고 줄에서 빼낸다.
    --
    -- 시각이 아니라 기한을 넣는다. 사람마다 주기가 다르니 판정 기준도 사람마다 달라야 하는데,
    -- 여기서 "방금 봤다"만 남기면 스위퍼가 그 사람에게 몇 초를 줬는지 알 길이 없다.
    -- 지금 알려 주는 주기를 여기서 더해 두면 sweep.lua는 지금 시각 하나만 보면 된다.
    --
    -- 이 한 줄 때문에 조회 경로가 읽기 전용이 아니게 된다. 별도 하트비트 엔드포인트를 두면
    -- 읽기 전용은 지키지만 요청 수가 두 배가 되므로, 이미 오고 있는 폴링을 재해석하는 쪽을 골랐다.
    -- 같은 스크립트 안이라 왕복은 늘지 않고 O(log N) 명령 하나가 붙는다.
    redis.call('ZADD', KEYS[3], tonumber(ARGV[2]) + pollAfter + tonumber(ARGV[6]), ARGV[1])
    return {0, rank, total, -1, pollAfter}
end

local expireAt = redis.call('ZSCORE', KEYS[2], ARGV[1])
if expireAt and tonumber(expireAt) > tonumber(ARGV[2]) then
    -- ZSCORE는 문자열이라 그대로 담으면 호출부의 List<Long> 캐스팅이 깨진다.
    return {1, -1, total, math.floor(tonumber(expireAt)), 0}
end

return {-1, -1, total, -1, 0}
