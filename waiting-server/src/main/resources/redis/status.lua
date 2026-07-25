-- 대기 순번 / 입장 여부 조회
-- KEYS[1] = waiting:holiday:{windowId}   ZSet (member=uuid, score=seq)
-- KEYS[2] = active:holiday:{windowId}    ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- return  = { state, rank, total, expireAt }
--
-- state: 0=대기 중, 1=입장, -1=없음(만료됐거나 발급된 적 없거나 창이 다름)
-- rank는 0-based이며, 대기 중이 아니면 -1이다.
-- expireAt은 활성 만료 epoch ms이며, 활성이 아니면 -1이다.
-- Lua 테이블은 nil을 만나면 그 지점에서 잘려 나가므로 nil 대신 -1로 표현한다.
--
-- expireAt을 돌려주는 이유는 예약 화면의 남은 시간 때문이다. 어차피 활성 판정에 ZSCORE를
-- 이미 읽고 있으므로, 버리지 않고 함께 반환하면 화면이 Redis를 한 번 더 칠 이유가 없다.
--
-- ZCARD와 ZRANK를 따로 호출하면 그 사이에 큐가 변해
-- "앞 + 뒤 + 1 = 전체"가 어긋난다. 그래서 한 스크립트로 묶는다.
--
-- 이 스크립트는 읽기 전용이다. 만 명이 2초마다 폴링하면 약 5,000 rps인데,
-- 승격을 여기서 하지 않고 스케줄러에 맡긴 이유가 이 경로에 쓰기를 섞지 않기 위해서다.

local total = redis.call('ZCARD', KEYS[1])
local rank = redis.call('ZRANK', KEYS[1], ARGV[1])

-- ZRANK는 member가 없으면 nil을 주고 Lua에서 nil은 false다. 그래서 존재 여부만 보면 된다.
--
-- 고칠 때 주의: Lua에서 falsy는 nil과 false뿐이고 0은 truthy다. 1등의 rank가 0이라
-- 이 분기를 정상적으로 탄다. C나 JS 감각으로 rank > 0으로 바꾸면 1등만 여기를 못 타고
-- 아래 활성 검사로 떨어져 state -1(없음)을 받는다 — 화면에는 404 "대기 정보를 찾을 수 없습니다"다.
-- 한 명만 깨지므로 부하 테스트에도 시연에도 드러나지 않는다.
if rank then
    return {0, rank, total, -1}
end

-- 대기열에 없으면 활성인지 본다.
local expireAt = redis.call('ZSCORE', KEYS[2], ARGV[1])
if expireAt and tonumber(expireAt) > tonumber(ARGV[2]) then
    -- ZSCORE는 문자열을 준다. 그대로 테이블에 담으면 bulk string으로 나가
    -- 호출부의 List<Long> 캐스팅이 런타임에 깨진다. 숫자로 바꿔서 담는다.
    -- score는 정수 ms지만 Lua는 실수로 다루므로 floor로 소수점을 없앤다.
    return {1, -1, total, math.floor(tonumber(expireAt))}
end

return {-1, -1, total, -1}
