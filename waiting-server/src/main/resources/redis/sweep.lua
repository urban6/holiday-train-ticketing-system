-- 폴링이 끊긴 대기자 회수
-- KEYS[1] = waiting:holiday:{windowId}   ZSet (member=uuid, score=seq)
-- KEYS[2] = poll:holiday:{windowId}      ZSet (member=uuid, score=다음 폴링 기한 epoch ms)
-- ARGV[1] = nowMillis          기한이 이 시각을 지난 사람은 이탈로 본다
-- ARGV[2] = maxSweep           한 주기에 회수할 최대 인원
-- return  = 회수한 인원
--
-- 닫기·이탈 요청(leave)이 도달하지 못한 경우를 덮는다. 크래시·기기 꺼짐·OS의 탭 종료·
-- 네트워크 단절에는 브라우저 이벤트가 아예 오지 않아서, 그쪽은 이 스크립트 말고 잡을 수단이 없다.
--
-- 임계값이 지금 시각 하나인 것은 status.lua가 사람마다 다른 기한을 score에 미리 구워 두기
-- 때문이다. 여기서 "몇 초 지났으면 이탈인가"를 다시 정하면 안 된다 — 그 값은 사람마다 다르고,
-- 이미 그 사람에게 알려 준 주기에 담겨 있다.
--
-- score가 기한이 됐어도 active처럼 ZREMRANGEBYSCORE 한 번으로 끝낼 수는 없다. 그 명령에는
-- LIMIT이 없어 maxSweep 상한을 걸 방법이 없고, waiting에서도 빼야 해서 멤버 목록이 필요하다.
--
-- maxSweep 상한은 promote.lua의 maxBatch와 같은 이유다. Redis는 단일 스레드라 스크립트가
-- 도는 동안 다른 모든 요청이 멈추는데, ZRANGEBYSCORE도 O(log N + M)이라 M이 곧 그 시간이다.
-- 대량 이탈이 한 번에 몰리는 순간 상한이 없으면 그게 그대로 지연 스파이크가 된다.

local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
if #stale == 0 then
    return 0
end

-- ZREM에 한 번에 몰지 않고 루프로 돈다. promote.lua와 같은 이유로 unpack에는
-- Lua 스택 한계(약 8000)가 있어서, maxSweep을 키우면 스크립트가 아예 깨진다.
for i = 1, #stale do
    redis.call('ZREM', KEYS[1], stale[i])
    redis.call('ZREM', KEYS[2], stale[i])
end

return #stale
