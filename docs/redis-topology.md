# Redis 구성 방식 — Standalone · Replication · Sentinel · Cluster

네 가지 구성이 각각 **어떻게 동작하고, 무엇을 주고, 무엇을 못 주는지** 정리한 문서입니다.
이 프로젝트가 무엇을 골랐는지와 측정값은 [README](../README.md)에 있고, 여기서는 링크만 겁니다.

## 요약

| 구성 | 노드 | 얻는 것 | 못 얻는 것 | 클라이언트가 달라지는 점 |
| --- | --- | --- | --- | --- |
| **Standalone** | 1 | 가장 단순함, 모든 명령이 한 순서 | 가용성 · 확장성 전부 | 없음 (host:port 하나) |
| **Replication** | master 1 + replica N | 읽기 분산, 백업 대상 | 자동 복구, 쓰기 · 메모리 확장 | 읽기를 replica로 보낼지 지정 |
| **Sentinel** | 위 + Sentinel 3+ | 자동 failover | 쓰기 · 메모리 확장 | master 주소를 Sentinel에 물어봄 |
| **Cluster** | master N (+각 replica) | 쓰기 · 메모리 수평 확장, 자동 failover | 슬롯이 다른 키를 한 명령으로 다루기 | 슬롯 맵 캐시, `MOVED` 추적 |

아래 순서는 그대로 **얹어 가는 순서**입니다. Sentinel은 Replication 위에 감시만 얹은 것이고,
Cluster는 그 전부를 샤드마다 갖습니다.

## Standalone

노드 하나가 전부입니다. 모든 키가 한 메모리에 있고, 모든 명령이 **단일 스레드 하나**를 지납니다.
그래서 그 스레드가 원자성의 근거이자 처리량의 천장입니다 — 명령 사이에 다른 요청이 끼어들 수 없어
Lua 스크립트가 그대로 임계 구역이 되지만, 반대로 스크립트가 길어지면 그 시간이 다른 모든 요청의
지연이 됩니다 (→ [배치 승격 상한 설정](../README.md#배치-승격-상한-설정)).

- **얻는 것**: 다룰 것이 없다는 것. 키가 어디 있는지, 어느 노드가 master인지 아무도 묻지 않습니다.
- **못 얻는 것**: 노드가 죽으면 전부 멈추고, 데이터 상한은 그 한 대의 메모리입니다.
- **설정**: `replicaof`도 `cluster-enabled`도 쓰지 않은 상태가 곧 Standalone입니다.

## Replication (`replicaof`)

replica가 master에 붙어 데이터를 따라오는 구성입니다. replica가 `REPLICAOF <host> <port>`로
master를 가리키면 스냅샷(RDB)을 받아 **전체 동기화**를 하고, 그 뒤로는 master가 받은 쓰기 명령을
스트림으로 흘려보냅니다.

- **비동기입니다.** master는 클라이언트에 OK를 준 **뒤에** replica로 전파하므로, 그 순간 master가
  죽으면 성공으로 받은 쓰기가 사라질 수 있습니다.
- **replica는 쓰기를 거부합니다** (`replica-read-only yes`). 읽기 전용 트래픽만 보낼 수 있고,
  복제 지연만큼 replica는 과거를 보여 줍니다.
- **자동 복구가 없습니다.** master가 죽으면 사람이 승격시켜야 하고(`REPLICAOF NO ONE`),
  쓰기 처리량과 메모리 상한은 여전히 master 한 대입니다.

Spring Boot에서 host 하나만 주면 replica를 쓰지 않습니다. Lettuce에 `ReadFrom`(예:
`REPLICA_PREFERRED`)을 지정해야 읽기가 갈라지고, 그때 "방금 쓴 값을 못 읽을 수 있다"를
애플리케이션이 받아들여야 합니다.

이 시스템의 조회 경로가 왜 replica로 갈 수 없는지는
[README](../README.md#redis는-이-워크로드에서-옆으로-늘어나지-않습니다)에 있습니다.

## Sentinel

**Sentinel은 데이터를 들지 않습니다.** master·replica를 감시하다 master가 죽었다고 판단하면
replica 하나를 승격시키는 별도 프로세스입니다. 정족수(`quorum`)만큼의 Sentinel이 같은 판단이면
Sentinel들이 리더를 뽑고, 그 리더가 replica 하나를 승격시킨 뒤 나머지를 새 master로 재지정합니다.
복제 구성 위에 **감시와 조정만** 얹는 것이라 데이터 배치는 그대로입니다.

- **클라이언트가 달라집니다.** master 주소를 설정에 박으면 승격 뒤에 틀린 곳을 가리키므로,
  Sentinel에게 "지금 master가 누구냐"를 묻습니다
  (Spring: `spring.data.redis.sentinel.master` · `.nodes`).
- **얻는 것은 자동 failover 하나입니다.** 쓰기도 메모리도 Replication과 똑같이 master 한 대입니다.
- **대가**: 판단(`down-after-milliseconds`)과 승격에 걸리는 **수 초 동안 쓰기가 멈추고**,
  복제가 비동기라 그 사이 확인된 쓰기가 유실될 수 있습니다. 다수결이 필요해 Sentinel은
  3대 이상(홀수)을 권장합니다.

이 시스템이 Redis 장애를 어떻게 다루기로 했는지는 [README](../README.md#redis-장애-대응)에 있습니다.

## Cluster

데이터 자체를 여러 master에 나눠 담는 유일한 구성입니다. 키 공간을 **16384개의 해시 슬롯**으로
쪼개 `CRC16(key) mod 16384`로 키의 슬롯을 정하고, 슬롯을 노드에 나눠 배정합니다.
각 샤드가 자기 replica를 갖고 스스로 failover하므로 Sentinel이 필요 없습니다.

- **클라이언트가 달라집니다.** 아무 노드에 물어도 되지만 그 슬롯이 자기 것이 아니면 `MOVED`로
  돌려보내므로, 클러스터 인지 클라이언트가 슬롯 맵을 캐시합니다
  (Spring: `spring.data.redis.cluster.nodes`).
- **다중 키 제약이 여기서 걸립니다.** 한 명령이 다루는 키들(그리고 Lua 스크립트의 `KEYS` 전부)이
  **같은 슬롯**에 있어야 하고, 아니면 `CROSSSLOT` 오류로 실행 자체가 거부됩니다.
- **못 얻는 것**: 트래픽이 한 키에 몰려 있으면 아무 것도. 슬롯이 나뉘어도 그 키는 노드 하나에
  있으므로 그 노드만 바쁩니다(핫 키).
- **설정**: `cluster-enabled yes`, `cluster-node-timeout`.

탈출구는 **해시 태그**입니다. 키에 `{...}`가 있으면 중괄호 **안쪽만** 해싱합니다.

```
waiting:holiday:20260728     ┐ 키 전체를 해싱      → 접두사가 달라 다른 슬롯
active:holiday:20260728      ┘
waiting:{holiday:20260728}   ┐ 중괄호 안쪽만 해싱  → 같은 슬롯
active:{holiday:20260728}    ┘
```

즉 **어떤 키들을 한 스크립트로 묶을지를 키 이름 단계에서 미리 정해 둬야 합니다.**
이 시스템의 키가 지금 어떤 모양인지는 `QueueKeys.java`의 주석과
[README](../README.md#redis는-이-워크로드에서-옆으로-늘어나지-않습니다)에 있습니다.

## 선택 기준

| 아쉬운 것 | 가는 곳 |
| --- | --- |
| 노드가 죽으면 전부 멈춘다 | **Sentinel** |
| 읽기가 많다 | **Replication** + `ReadFrom` |
| 데이터가 한 대 메모리를 넘는다 | **Cluster** |
| **쓰기 처리량이 모자란다** | **어느 구성도 아님** |

**마지막 줄이 가장 자주 오해되는 자리입니다.** 쓰기가 한 키에 몰리는 워크로드는 Cluster로도
나뉘지 않습니다. 노드를 늘리기 전에 **트래픽이 실제로 갈라지는 축**을 먼저 찾아야 하고,
그 축이 없으면 노드를 늘려도 바쁜 노드는 계속 하나입니다.

여기 적힌 것은 전부 원리에서 따라오는 성질이지, 재서 얻은 값이 아닙니다. 이 시스템의 수치는
README의 측정 결과와 `k6/` 스크립트의 주석에 있습니다.
