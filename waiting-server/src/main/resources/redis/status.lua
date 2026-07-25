-- 대기 순번 / 입장 여부 조회
-- KEYS[1] = waiting:holiday:{windowId}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:holiday:{windowId}    ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- return  = { state, rank, total, expireAt }
--   state    0=대기 중, 1=입장, -1=없음(만료·미발급·다른 창)
--   rank     0-based, 대기 중이 아니면 -1
--   expireAt 활성 만료 epoch ms, 활성이 아니면 -1
-- 없는 값을 nil이 아니라 -1로 두는 이유: Lua 테이블은 nil에서 잘려 나가 뒤 필드가 사라진다.
--
-- ZCARD와 ZRANK를 따로 부르면 그 사이에 큐가 변해 "앞 + 뒤 + 1 = 전체"가 어긋난다.

local total = redis.call('ZCARD', KEYS[1])
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])

-- 없으면 nil(=false)이라 존재 여부만 본다.
-- rank > 0으로 바꾸지 말 것 — Lua에서 0은 truthy라 1등(rank=0)만 이 분기를 놓치고 state -1을 받는다.
if rank then
    return {0, rank, total, -1}
end

local expireAt = redis.call('ZSCORE', KEYS[2], ARGV[1])
if expireAt and tonumber(expireAt) > tonumber(ARGV[2]) then
    -- ZSCORE는 문자열이라 그대로 담으면 호출부의 List<Long> 캐스팅이 깨진다.
    return {1, -1, total, math.floor(tonumber(expireAt))}
end

return {-1, -1, total, -1}
