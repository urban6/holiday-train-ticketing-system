-- 활성 슬롯의 만료를 now + ttl로 다시 찍는다
-- KEYS[1] = active:holiday:{windowId}   ZSet (member=uuid, score=만료 epoch ms)
-- ARGV[1] = uuid
-- ARGV[2] = nowMillis
-- ARGV[3] = ttlMillis
-- return  = 1 성공 / 0 활성이 아니거나 이미 만료 — 만료된 슬롯은 되살리지 않는다
--
-- 부르는 곳이 둘이고 뜻은 같다("지금부터 다시 이만큼"). ttl만 다르다.
--   입장 확정(claim) : admissionGrace → sessionTtl
--   로그인           : sessionTtl → reservationTtl

local expireAt = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not expireAt or tonumber(expireAt) <= tonumber(ARGV[2]) then
    return 0
end

redis.call('ZADD', KEYS[1], tonumber(ARGV[2]) + tonumber(ARGV[3]), ARGV[1])
return 1
