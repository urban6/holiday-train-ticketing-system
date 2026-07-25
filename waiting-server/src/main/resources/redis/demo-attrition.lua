-- 대기 중인 봇의 이탈
-- KEYS[1] = waiting:holiday:{windowId}   ZSet (member=uuid, score=seq)
-- ARGV[1] = count   이번 주기에 이탈시킬 인원. 무작위 값은 호출부가 정한다.
-- return  = 실제로 제거한 수
--
-- 승격만으로는 순번이 매 주기 정확히 max-batch씩 줄어 기계처럼 보인다. 봇이 스스로 포기해 감소 폭을 흔든다.
-- 앞에서 순서대로 빼면 그건 이탈이 아니라 승격이고, 뒤에서만 빼면 내 순번이 줄지 않는다. 그래서 ZRANDMEMBER다.
-- 실사용자 토큰은 UUID라 뽑혀도 접두사 필터에 걸러진다 — 대기 중인 진짜 사용자가 사라지는 일은 없다.

local members = redis.call('ZRANDMEMBER', KEYS[1], ARGV[1])
local removed = 0

for i = 1, #members do
    if string.sub(members[i], 1, 4) == 'bot:' then
        removed = removed + redis.call('ZREM', KEYS[1], members[i])
    end
end

return removed
