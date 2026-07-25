-- 승격된 봇을 활성에서 회수한다
-- KEYS[1] = active:holiday:{windowId}   ZSet (member=uuid, score=만료 epoch ms)
-- return  = 회수한 봇 수
--
-- 봇은 폴링도 claim도 하지 않아, 그냥 두면 admission-grace만큼 빈 슬롯을 붙잡고 정원을 막는다.
-- 로그아웃(release)과 같은 ZREM으로 즉시 비운다 — "입장하자마자 나가는 사용자".

local members = redis.call('ZRANGE', KEYS[1], 0, -1)
local removed = 0

for i = 1, #members do
    if string.sub(members[i], 1, 4) == 'bot:' then
        redis.call('ZREM', KEYS[1], members[i])
        removed = removed + 1
    end
end

return removed
