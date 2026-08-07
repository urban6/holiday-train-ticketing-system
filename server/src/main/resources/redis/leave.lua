-- 대기열 이탈
-- KEYS[1] = waiting:{date}:{shard}   ZSet (member=uuid, score=seq)
-- KEYS[2] = poll:{date}:{shard}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = uuid
-- return  = 1 실제로 뺐음 / 0 이미 없었음 (호출부는 둘 다 성공으로 본다)
--
-- 두 키에 걸치므로 묶는다. 갈라 두면 waiting에서만 빠지고 기한이 남아, 스위퍼가 매 주기
-- 이미 없는 토큰을 다시 지우려 든다.
--
-- active는 건드리지 않는다. 입장에 성공해 다음 화면으로 넘어가는 것도 pagehide라서
-- 그 순간 beacon이 날아오는데, 여기서 active까지 지우면 방금 받은 자리를 스스로 반납한다.
-- waiting만 지우면 그 시점엔 이미 승격돼 waiting에 없으므로 no-op이 되어 안전하다.
-- 활성 슬롯 반납은 로그아웃(release)의 몫이다.

local removed = redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])

return removed
