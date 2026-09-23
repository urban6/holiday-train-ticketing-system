# Redis 용량 산정: 메모리와 명령 시간

## 왜 알아야 하는가

Redis 설계는 두 예산 안에서 한다. 메모리와 명령 시간.
이 둘을 실측하지 않으면 자료구조도 노드 수도 근거 없이 정하게 된다.

## 메모리

- 모든 데이터가 RAM에 있다. 데이터 크기 = 메모리 = 담을 수 있는 상한.
- 원소 하나의 비용은 값보다 오버헤드가 크다. dict 엔트리, 객체 헤더, 문자열 헤더가 붙고 TTL이 있으면 만료 dict 엔트리가 하나 더 붙는다.
- ZSet은 dict + skiplist 두 구조를 함께 유지해 무거운 편이다. UUID 36자 멤버 기준 원소당 약 100~150B.
- `used_memory`는 Redis가 할당한 바이트, `used_memory_rss`는 OS가 보는 실제 점유. 비율이 단편화이고 예산은 RSS 기준으로 잡는다.
- `maxmemory-policy`가 캐시용(allkeys-lru 등)이면 상한에서 키를 지운다. 대기열은 `noeviction`이어야 하고, 상한에 닿으면 쓰기가 실패하는 것이 의도된 동작이다.

| 명령 | 용도 |
|---|---|
| `MEMORY USAGE key SAMPLES 0` | 키 하나의 바이트. SAMPLES 0은 전수 계산 |
| `OBJECT ENCODING key` | 내부 인코딩. ZSet은 listpack 또는 skiplist |
| `INFO memory` | used_memory, rss, mem_fragmentation_ratio |

### ZSet 내부: dict + skiplist

같은 데이터를 두 구조로 동시에 든다. 역할이 다르다.

| 구조 | 역할 | 쓰는 명령 |
|---|---|---|
| dict (해시 테이블) | 멤버 → score, O(1) | ZSCORE, ZADD의 존재 확인 |
| skiplist | score 순 정렬, 순번, 범위, O(log N) | ZRANK, ZRANGE, ZPOPMIN, ZREMRANGEBYSCORE |

## 명령 시간

- 명령 실행은 단일 스레드. 처리량 = 1초 ÷ 요청당 명령 시간의 합. 요청 수가 아니라 명령 시간으로 예산을 센다.
- 복잡도가 시간을 정한다. O(log N)은 N이 150만이어도 µs, O(N)은 ms이고 그동안 다른 요청이 전부 멈춘다.
- 클라이언트가 보는 지연은 대부분 네트워크 왕복이다. 서버가 5µs에 끝내도 왕복이 200µs면 연결 하나로는 초당 5,000이 한계. 서버 상한에 닿으려면 동시 연결이나 파이프라이닝이 필요하다.

| 도구 | 알려 주는 것 |
|---|---|
| `redis-benchmark` | 앱 없이 잰 명령별 상한 |
| `INFO commandstats` | 운영 중 명령별 호출 수와 평균 µs |

상한과 실사용의 비율이 남은 여유다.

## 실측

```bash
redis-cli eval "for i=1,200000 do redis.call('ZADD','q',1700000000000000+i,'00000000-0000-0000-0000-'..string.format('%012d',i)) end return 1" 0
redis-cli MEMORY USAGE q SAMPLES 0     # ÷ 200000 = 원소당 바이트
redis-cli OBJECT ENCODING q            # skiplist
```

| 멤버 길이 | 원소당 바이트 |
|---|---|
| 36자 UUID | (실측) |
| 22자 | (실측) |

## 이 프로젝트에 적용

| 항목 | 값 |
|---|---|
| 개시 순간 유입 | 100만 / 1초 |
| 최대 대기 인원 | 150만 |

- 대기열 메모리 = 150만 × 원소당 바이트 × 키 종류 수
- 진입 샤드 수 = 100만 ÷ 노드당 진입 처리량
- 조회는 1인당 N회라 판매 창 전체로는 진입보다 훨씬 많다. 스파이크는 진입이 만들고 예산은 조회가 잡아먹는다.
- 활성 정원은 예약 서버 성능에 따르는 파라미터. 정원 ÷ 체류 시간이 줄이 빠지는 속도다.
