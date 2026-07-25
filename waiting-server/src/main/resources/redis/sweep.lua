-- 폴링이 끊긴 대기자 회수
-- KEYS[1] = waiting:holiday:{windowId}   ZSet (member=uuid, score=seq)
-- KEYS[2] = seen:holiday:{windowId}      ZSet (member=uuid, score=마지막 확인 epoch ms)
-- ARGV[1] = staleBeforeMillis  이 시각 이전에 마지막으로 확인한 사람은 이탈로 본다
-- ARGV[2] = maxSweep           한 주기에 회수할 최대 인원
-- return  = 회수한 인원
--
-- 닫기·이탈 요청(leave)이 도달하지 못한 경우를 덮는다. 크래시·기기 꺼짐·OS의 탭 종료·
-- 네트워크 단절에는 브라우저 이벤트가 아예 오지 않아서, 그쪽은 이 스크립트 말고 잡을 수단이 없다.
--
-- waiting의 score는 seq(순번)라 만료시각을 담을 수 없다 — ZRANK가 순번의 근거이기 때문이다.
-- 그래서 active처럼 ZREMRANGEBYSCORE 한 번으로 끝내지 못하고 seen을 따로 둔다.
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
