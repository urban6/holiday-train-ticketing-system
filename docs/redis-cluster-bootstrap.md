> [← README](../README.md)

# 로컬 Redis Cluster 부트스트랩

`application-loadtest.yml`이 기대하는 마스터 3 + 레플리카 3 구성을 Homebrew 네이티브
`redis-server` 6개로 로컬에 세우는 절차입니다. Docker가 아니라 네이티브인 이유는 기존
실측 관행과 같습니다 — 포트 포워딩이 처리량의 상당 부분을 먹어 측정을 왜곡합니다.

이 문서의 검증·failover 드릴은 `./gradlew test`에 들어 있지 않습니다. 손으로 확인하는
단계입니다 — Redis Cluster를 자동으로 세워 주는 Testcontainers 모듈이 마땅치 않고,
이 프로젝트의 실측은 원래 네이티브 인스턴스에서 손으로 확인하는 관행이기 때문입니다
([Redis 싱글 스레드](redis-single-thread.md), [Redis 구성 방식](redis-topology.md) 참고).

## 1. 6개 프로세스 기동

포트 6380~6382를 마스터 후보로, 6383~6385를 레플리카로 씁니다(어느 쪽이 실제 마스터가
되는지는 2단계에서 정해집니다 — `--cluster create`가 나열 순서대로 마스터를 고릅니다).

```bash
for port in 6380 6381 6382 6383 6384 6385; do
  redis-server infra/redis/redis-cluster.conf --port $port --bind 127.0.0.1 \
    --cluster-config-file infra/redis/nodes-$port.conf &
done
```

`--cluster-config-file`을 프로세스마다 다르게 주지 않으면 서로 덮어써 클러스터 상태가
깨집니다. 이 파일들은 `.gitignore`에 이미 등록되어 있습니다.

## 2. 해시 태그가 세 마스터에 고르게 떨어지는지 확인

이 프로젝트는 샤드 0/1/2를 해시 태그 `{a}`/`{b}`/`{c}`로 표현합니다(`QueueKeys.SHARD_TAGS`).
`redis-cli --cluster create`는 나열한 순서대로 16384개 슬롯을 3등분해 마스터에 배정하는데,
**샤드 번호를 그대로 태그로 쓰면(`{0}`/`{1}`/`{2}`) 세 슬롯 중 둘이 같은 구간에 몰릴 수
있습니다** — 로컬 확인 결과 `{0}`=13907, `{1}`=9842, `{2}`=5649로 나왔고, 3등분 구간
(0~5460 / 5461~10922 / 10923~16383)에 넣으면 `{1}`과 `{2}`가 같은 구간(가운데)에 겹쳐
마스터 하나는 그 샤드들의 트래픽을 아예 못 받습니다. `SHARD_TAGS`의 `a`/`b`/`c`는 같은
방법으로 세 구간에 하나씩 떨어지는 것을 미리 확인해 코드에 고정해 둔 값입니다
(`CLUSTER KEYSLOT`으로 재확인 가능 — 클러스터가 형성되기 전에도 클러스터 모드 프로세스라면
바로 계산됩니다).

```bash
redis-cli -p 6380 cluster keyslot "{a}"   # → 15495, 세 번째 구간
redis-cli -p 6380 cluster keyslot "{b}"   # →  3300, 첫 번째 구간
redis-cli -p 6380 cluster keyslot "{c}"   # →  7365, 두 번째 구간
```

세 값이 서로 다른 구간에 있으면 그대로 3단계를 진행합니다. **마스터 포트의 나열 순서는
상관없습니다** — `SHARD_TAGS`가 이미 세 구간에 하나씩 떨어지므로, 어느 포트가 어느 구간을
맡든 세 샤드는 항상 서로 다른 마스터에 떨어집니다. `QueueKeys.SHARD_TAGS`를 늘리거나
바꾼다면(예: 샤드 수를 늘릴 때) 이 확인을 다시 해야 합니다.

이 확인은 `QueueKeysTest`가 클러스터 없이도 같은 값(15495 / 3300 / 7365)과 마스터 분리를
단언하므로, `SHARD_TAGS`를 고치면 `./gradlew test`에서 먼저 걸립니다. 그래도 여기 손 절차를
남겨 두는 이유는, 실제 클러스터의 슬롯 배정이 `--cluster create` 직후의 3등분과 다를 수 있기
때문입니다(`--cluster reshard`나 노드 추가로 구간이 움직인 경우). 테스트가 보는 것은 슬롯
번호까지고, 그 슬롯을 누가 갖는지는 클러스터에 물어야 압니다.

## 3. 클러스터 형성

```bash
redis-cli --cluster create \
  127.0.0.1:6380 127.0.0.1:6381 127.0.0.1:6382 \
  127.0.0.1:6383 127.0.0.1:6384 127.0.0.1:6385 \
  --cluster-replicas 1 --cluster-yes
```

앞의 세 노드가 마스터, 뒤의 세 노드가 레플리카로 하나씩 자동 배정됩니다.

**알아 둘 한계**: 6개 프로세스가 전부 `127.0.0.1`이라 "레플리카를 마스터와 다른 호스트에
둔다"는 Redis의 배치 휴리스틱이 할 일이 없습니다 — 그냥 순서대로 돌아가며 배정합니다.
Cluster의 failover *메커니즘* 자체를 확인하는 데는 충분하지만, 실제 호스트 단위 고가용성의
대역은 아닙니다.

## 4. 검증

```bash
redis-cli --cluster check 127.0.0.1:6380
```

마스터 3개, 각각 레플리카 1개, 16384개 슬롯이 빈틈없이 커버되는지 확인합니다. 이어서
샤드 0/1/2의 해시 태그가 서로 다른 마스터에 있는지 교차 확인합니다.

```bash
redis-cli -p 6380 cluster shards
```

앱을 `--spring.profiles.active=loadtest`로 띄우고 진입·조회·승격·이탈·회수를 실제로
실행한 뒤 로그에 `CROSSSLOT`이 전혀 없는지 확인합니다 — 있다면 한 샤드의 네 키 중 하나가
해시 태그를 빠뜨린 것입니다.

## 5. Failover 드릴

```bash
# 부하를 걸어 둔 상태에서 마스터 하나를 강제 종료
redis-cli -p 6380 shutdown nosave

# 레플리카가 승격됐는지 확인
redis-cli --cluster check 127.0.0.1:6381
```

앱 로그에서 짧은 `QueueException.Unavailable` 구간이 보이다가, 재시작 없이 스스로
회복하는지 확인합니다. 회복 지연의 이론적 상한은 `cluster-node-timeout`(2초, 장애 판단) +
`spring.data.redis.lettuce.cluster.refresh.period`(5초, 앱 쪽 토폴로지 지도 갱신)입니다 —
개별 명령의 `MOVED` 재시도는 이 값과 무관하게 그전에도 이미 새 마스터를 따라갑니다.

## 마스터 3대만 쓰는 구성 — 분배 확인용

대기열이 세 노드에 실제로 갈리는지만 볼 때는 레플리카가 필요 없습니다. 레플리카는 슬롯을
받지 않으므로 3등분 구간도, `{a}`/`{b}`/`{c}`가 세 마스터에 하나씩 떨어지는 것도 위와
같습니다. 1단계에서 6380~6382 세 개만 띄우고 3단계를 이렇게 바꿉니다.

```bash
redis-cli --cluster create \
  127.0.0.1:6380 127.0.0.1:6381 127.0.0.1:6382 \
  --cluster-replicas 0 --cluster-yes
```

`application-loadtest.yml`의 시드는 그대로 둡니다 — 6380~6382만 적혀 있어 두 구성 모두에서
맞습니다.

부하를 걸고 세 노드에 실제로 갈렸는지 봅니다. 셋의 키 개수가 비슷해야 합니다.

```bash
for p in 6380 6381 6382; do redis-cli -p $p dbsize; done
```

한 노드가 0이면 태그가 겹친 것이고, 셋이 크게 벌어지면 `QueueKeys.shardOf`의 분배가 깨진
것입니다. 원인이 다르므로 어느 쪽인지부터 가릅니다 — 앞은 `QueueKeysTest`, 뒤는
`QueueShardingTest.shardOfSpreadsTokensEvenly`가 보는 자리입니다.

**이 구성에서 알아 둘 것 두 가지.**

- 승격할 레플리카가 없으므로 5단계 failover 드릴은 할 수 없습니다. 처리량을 재는 것이
  목적이라면 레플리카를 붙이는 쪽이 맞습니다. 비동기 복제도 공짜가 아니라, 없이 잰 수치는
  README가 적어 둔 구성(마스터 3 + 레플리카 3)의 수치가 아닙니다.
- `redis-cluster.conf`의 `cluster-node-timeout 2000`은 failover를 눈으로 보려고 낮춘
  값입니다. 승격할 대상이 없는 상태에서 부하 중 마스터 하나가 2초 멈추면
  `cluster-require-full-coverage`(기본 yes) 때문에 **클러스터 전체가 쓰기를 거부**합니다.
  측정 중 원인 모를 전면 실패가 나면 여기가 첫 번째 용의자입니다.

## 정리

```bash
redis-cli -p 6380 shutdown nosave 2>/dev/null
redis-cli -p 6381 shutdown nosave 2>/dev/null
redis-cli -p 6382 shutdown nosave 2>/dev/null
redis-cli -p 6383 shutdown nosave 2>/dev/null
redis-cli -p 6384 shutdown nosave 2>/dev/null
redis-cli -p 6385 shutdown nosave 2>/dev/null
rm -f infra/redis/nodes-*.conf
```
