-- 승격된 봇을 활성에서 회수한다
-- KEYS[1] = active:{holiday}:{windowId}   ZSet (member=uuid, score=만료 epoch ms)
-- return  = 회수한 봇 수
--
-- 봇은 폴링도 claim도 하지 않으므로, 그냥 두면 아무도 쓰지 않는 슬롯을
-- admission-grace(150초)만큼 붙잡는다. 정원이 봇으로 차서 진짜 사용자가 못 들어온다.
-- 로그아웃(release)이 하는 일과 같은 ZREM으로 즉시 비운다 — "입장하자마자 나가는 사용자".
--
-- 멤버를 전부 훑지만 active의 크기는 queue.capacity가 상한이라 비용이 예측 가능하다.
-- 시연 프로파일에서만 도는 스크립트이므로 이 정도면 충분하다.

local members = redis.call('ZRANGE', KEYS[1], 0, -1)
local removed = 0

for i = 1, #members do
    if string.sub(members[i], 1, 4) == 'bot:' then
        redis.call('ZREM', KEYS[1], members[i])
        removed = removed + 1
    end
end

return removed
