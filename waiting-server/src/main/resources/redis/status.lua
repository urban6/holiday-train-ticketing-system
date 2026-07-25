-- 대기 순번 / 입장 여부 조회
-- KEYS[1] = waiting:holiday:{windowId}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:holiday:{windowId}    ZSet (member=uuid, score=만료 epoch ms)
-- KEYS[3] = seen:holiday:{windowId}      ZSet (member=uuid, score=마지막 확인 epoch ms)
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
    -- 이 조회 자체가 하트비트다. 살아 있다는 신호가 이것 말고 없으므로, 여기서 시각을 다시
    -- 찍지 않으면 stale-timeout 뒤에 스위퍼가 멀쩡한 사용자를 이탈로 보고 줄에서 빼낸다.
    --
    -- 이 한 줄 때문에 조회 경로가 읽기 전용이 아니게 된다. 별도 하트비트 엔드포인트를 두면
    -- 읽기 전용은 지키지만 요청 수가 두 배가 되므로, 이미 오고 있는 폴링을 재해석하는 쪽을 골랐다.
    -- 같은 스크립트 안이라 왕복은 늘지 않고 O(log N) 명령 하나가 붙는다.
    redis.call('ZADD', KEYS[3], ARGV[2], ARGV[1])
    return {0, rank, total, -1}
end

local expireAt = redis.call('ZSCORE', KEYS[2], ARGV[1])
if expireAt and tonumber(expireAt) > tonumber(ARGV[2]) then
    -- ZSCORE는 문자열이라 그대로 담으면 호출부의 List<Long> 캐스팅이 깨진다.
    return {1, -1, total, math.floor(tonumber(expireAt))}
end

return {-1, -1, total, -1}
