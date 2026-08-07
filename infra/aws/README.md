# AWS 측정 환경

> [← README](../../README.md)

로컬(단일 Mac)에서는 더 쪼갤 수 없는 것이 하나 남아 있습니다. 앱 지연의 90% 이상이 Tomcat·가상 스레드·
루프백 TCP, 그리고 **같은 머신에서 도는 k6와의 CPU 경합**입니다. 여기서 하는 일의 절반은 부하 생성기를
떼어내는 것입니다.

| | 구성 | 여기서 얻는 것 |
| --- | --- | --- |
| **1단계** | k6 · WAS 1대 · Redis · RDS (**NLB 없음**) | 기준선. vCPU당 처리량 |
| **2단계** | + NLB · WAS N대 | NLB가 얹는 비용, 수평 확장 효율 |

1단계에 NLB를 넣지 않는 이유는 WAS 1대가 어디서 포화되는지 모르는 상태에서는 N대의 이득을 말할 수 없고,
**NLB가 얹는 비용을 아는 것 자체가 측정 결과**라 기준선에 섞으면 분리해 낼 수 없기 때문입니다.
k6는 WAS의 프라이빗 IP로 직접 붙습니다.

## 인스턴스

**WAS는 작게, k6는 크게.** 부하 생성기가 먼저 포화되면 측정 대상이 아니라 생성기를 재게 됩니다.

| 역할 | 인스턴스 | 근거 |
| --- | --- | --- |
| k6 | `c8g.2xlarge` (8 vCPU) → 8대 이상 재려면 `c8g.8xlarge` | WAS의 4배. 포화 여부는 `dropped_iterations`와 자체 CPU로 확인. 2xlarge는 44,000 req/s에서 자체 CPU 99.3%로 막혔습니다 |
| WAS | `c8g.large` (2 vCPU · 4 GiB) → 키우며 스윕 | 작게 시작해야 포화됩니다. `large → xlarge → 2xlarge` 스윕이 곧 vCPU당 처리량 측정이고, 그게 2단계 계획의 근거가 됩니다 |
| Redis | `c8g.large` (2 vCPU · 4 GiB) × 6 | 단일 스레드라 노드 하나에 코어를 늘려도 안 늡니다. 늘리는 방법이 노드를 늘리는 것이고, 그게 이 구성의 이유입니다 — 마스터 3 + 레플리카 3 |
| RDS | `db.t4g.micro` | 대기열 hot path에 걸리지 않습니다. Hikari `maximum-pool-size: 10`이 이미 상한입니다 |

**Redis 노드는 마스터 하나에 인스턴스 하나씩 줍니다.** 한 인스턴스에 여러 노드를 올리면 그 노드들이
같은 코어를 다투게 되어, 재려던 것(샤딩이 단일 노드의 한계를 걷어내는가)이 그대로 사라집니다.
로컬에서 6프로세스를 한 대에 띄우는 것은 정합성 확인용이고 처리량 측정용이 아닙니다
([docs/redis-cluster-bootstrap.md](../../docs/redis-cluster-bootstrap.md)).

**레플리카를 빼고 재지 않습니다.** 슬롯을 갖지 않으니 처리량에 기여하지 않지만, 비동기 복제도 공짜가
아니라 빼고 잰 수치는 README가 적어 둔 구성(마스터 3 + 레플리카 3)의 수치가 아니게 됩니다.

Graviton(ARM)을 고른 이유는 개발 머신이 Apple Silicon이라 **아키텍처가 같아 비교가 깨끗**해서입니다.
OS는 **Ubuntu 26.04 LTS (arm64)**이고, 아래 설치 명령이 전부 `apt` 기준인 이유입니다.

**Redis `maxmemory`는 [`redis-cluster-aws.conf`](../redis/redis-cluster-aws.conf)의 3gb를 씁니다.**
대기자 한 명이 `waiting`과 `poll` 두 곳을 차지해
([`enqueue.lua`](../../server/src/main/resources/redis/enqueue.lua)가 진입 시점에 둘 다 씁니다)
실측 1인당 **276바이트**이고, 200만 명이면 약 **552 MiB**입니다. `noeviction`이라 넘으면 쓰기가
거부되고, 그건 버그가 아니라 설계된 동작이지만 측정 도중에 만나면 그 런은 버려야 합니다.

> **워밍업까지 계산에 넣습니다.** `measure.sh`의 워밍업은 본 측정 전에 초기화되지만, 그 사이에는
> 워밍업 인원이 통째로 올라가 있습니다. 대당 워밍업량을 유지하려고 WAS 대수에 비례해 늘리면
> (8대면 480만 건) **1.32 GB로 1gb를 넘깁니다.** 8대 측정에서는 3gb로 올렸습니다.
>
> 샤딩 뒤로는 그 몫이 세 마스터에 갈리므로 노드당 필요량은 1/3입니다. 그래도 3gb를 그대로 두는
> 이유는 한 샤드로 쏠린 런에서 그 노드만 먼저 `noeviction`에 닿는 것을 피하기 위해서입니다.

**`cluster-node-timeout`은 로컬(2000)과 다른 15000입니다.** 로컬 값은 failover를 눈으로 보려고 낮춘
것이라 그대로 가져오면 안 됩니다 — 포화 지점에서 마스터가 Lua 실행으로 2초 넘게 막히면 멀쩡한 노드가
실패로 찍히고, `cluster-require-full-coverage`(기본 yes) 때문에 클러스터 전체가 쓰기를 거부합니다.
측정 런을 통째로 버리게 되는 실패 모드이고, 원인 모를 전면 실패가 나면 여기가 첫 번째 용의자입니다.

## 배치 — 전부 같은 AZ

**같은 VPC · 같은 AZ · 같은 서브넷에 둡니다.**

로컬에서 Docker 왕복 0.31ms가 "Redis가 실제로 일하는 8.37μs의 약 37배"였습니다.
**크로스 AZ가 그 교훈의 AWS 판본입니다** — 왕복 지연이 그대로 돌아오고 데이터 전송 비용까지 붙습니다.

**클러스터 배치 그룹은 쓰지 않았습니다.** 같은 AZ·같은 서브넷으로 충분했고, 배치 그룹은 용량 확보
실패로 인스턴스가 아예 안 뜨는 실패 모드를 하나 더 얹습니다. 위 수치는 배치 그룹 없이 나온 값입니다.

## 보안 그룹이 유일한 방어선입니다

Spring Security를 쓰지 않고, actuator `/metrics`에 인증이 없고,
[redis-cluster-aws.conf](../redis/redis-cluster-aws.conf)의 Redis는 `protected-mode no`에
비밀번호도 없습니다. 네트워크 경계가 전부입니다.

| 대상 | 포트 | 인바운드 허용 |
| --- | --- | --- |
| WAS | 8080 | k6 보안 그룹 + 내 IP |
| Redis | 6379 | WAS 보안 그룹 + **k6 보안 그룹** |
| Redis 클러스터 버스 | **16379** | **Redis 보안 그룹** (노드끼리만) |
| RDS | 5432 | **WAS 보안 그룹만** |
| 전부 | 22 | 내 IP (또는 SSM Session Manager로 대체) |

**16379를 빠뜨리면 클러스터가 형성되지 않습니다.** Redis Cluster는 데이터 포트와 별개로
`포트 + 10000`을 노드 간 gossip에 씁니다. 6379만 열어 두면 `redis-cli --cluster create`가
노드를 하나씩은 잘 보면서 합의 단계에서 멈추는데, 증상만 봐서는 방화벽 문제로 보이지 않습니다.

**Redis 6379에 k6도 넣어야 합니다.** [measure.sh](measure.sh)가 k6 인스턴스에서 `redis-cli`로
초기화와 검증을 합니다. WAS만 열면 측정이 첫 줄에서 멈춥니다. 마찬가지로 **WAS 8080을 내 IP에만 열면
k6가 붙지 못합니다** — 자기 참조 규칙이 따로 필요하고, 증상이 타임아웃이라 앱 문제로 보이기 쉽습니다.

지금은 셋이 **보안 그룹 하나를 공유**하고 6379·16379·5432를 자기 참조로 열어 두었습니다.
역할별로 나누는 것보다 규칙이 적고, 1단계는 어차피 인스턴스가 짧게 살기 때문입니다.

```bash
SG=<security-group-id>
aws ec2 authorize-security-group-ingress --group-id $SG --ip-permissions \
  "IpProtocol=tcp,FromPort=8080,ToPort=8080,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=6379,ToPort=6379,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=16379,ToPort=16379,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=5432,ToPort=5432,UserIdGroupPairs=[{GroupId=$SG}]"
aws ec2 authorize-security-group-ingress --group-id $SG \
  --protocol tcp --port 8080 --cidr <내 IP>/32     # 브라우저·actuator 확인용
```

인증 없는 Redis를 `0.0.0.0/0`에 열면 스캐너가 붙는 데 몇 분 걸리지 않습니다.
**기본 보안 그룹을 그대로 쓰지 마십시오** — 계정에 따라 넓게 열려 있고,
`run-instances`에서 `--security-group-ids`를 빠뜨리면 그게 붙습니다.
RDS도 만들 때 붙은 보안 그룹이 다른 프로젝트 것이면 옮겨야 합니다.

```bash
aws rds modify-db-instance --db-instance-identifier <id> \
    --vpc-security-group-ids $SG --no-publicly-accessible --apply-immediately
```

## 순서

**빠른 경로:** 인스턴스를 새로 띄울 때마다 아래 1단계(부트스트랩 + 설치)를 손으로 반복하지
않아도 됩니다. [provision.sh](provision.sh)가 user-data(cloud-init)로 같은 일을 부팅
시점에 끝냅니다.

```bash
cp provision.env.example provision.env   # 최초 1회 — AMI_ID·서브넷·보안 그룹·키 페어
vi provision.env
./provision.sh redis 6 --wait            # Redis 6대 (마스터 3 + 레플리카 3)
./provision.sh was 8                     # WAS 8대
./provision.sh k6 --wait                 # 뜰 때까지 기다렸다 프라이빗 IP까지 출력
```

**Redis를 먼저 띄웁니다.** WAS의 `/etc/waiting/env`에 마스터 세 대의 IP가 들어가야 하는데,
그 IP는 Redis가 떠 봐야 나옵니다.

JAR 배포와 DB 시크릿(2~3단계), 그리고 클러스터 형성(1-2단계)은 여전히 손으로 합니다 —
앞의 둘은 실행마다 값이 바뀌거나 비밀번호를 다뤄서 user-data에 구우면 오히려 위험해지고,
클러스터 형성은 6대의 IP가 다 나온 뒤에야 할 수 있어 부팅 시점에는 알 수 없습니다.
아래 1단계는 삭제하지 않고 남겨 뒀습니다 — `provision.sh`가 내부적으로 정확히 이 순서를
user-data로 실행합니다. "왜 이 값이어야 하는가"는 이 절의 설명이 유일한 근거입니다.

> **미리 구워 둔 WAS AMI를 쓸 때.** `provision.env`의 `WAS_AMI_ID`가 그것이고, `was` 역할만
> 그 이미지로 뜹니다. **`AMI_ID`(맨 Ubuntu)에 그 값을 넣으면 안 됩니다** — 그 이미지는
> `waiting.service`가 enable된 채라 Redis·k6 노드에서도 WAS가 같이 떠서, 재려던 노드에
> 측정 대상이 아닌 JVM이 붙습니다. 이미지 안의 옛 JAR과 옛 `/etc/waiting/env`는
> `provision.sh`의 `was` 블록이 부팅 시점에 지웁니다.

### 1. 인스턴스마다 부트스트랩

커널·리소스 한도를 맞춥니다. 왜 필요한지는 [bootstrap.sh](bootstrap.sh) 헤더에 있습니다.

```bash
sudo ./bootstrap.sh was      # WAS 인스턴스
sudo ./bootstrap.sh k6       # 부하 생성기
sudo ./bootstrap.sh redis    # Redis 인스턴스
```

설치는 따로 합니다(AMI마다 다릅니다). Ubuntu 26.04 기준:

| 역할 | 설치 |
| --- | --- |
| WAS | Corretto 저장소 등록 후 `sudo apt install -y java-21-amazon-corretto-jdk postgresql-client` (아래) |
| Redis | `sudo apt install -y redis-server` — 설정은 다음 절 |
| k6 | **APT 저장소를 쓸 수 없습니다**(아래) — 릴리스 tarball + `sudo apt install -y redis-tools sysstat`. 이 저장소도 클론해 둘 것 |

> **`dl.k6.io/deb`에는 arm64가 없습니다.** `Release`의 `Architectures`가 `amd64 i386`이라
> Graviton에서는 `apt install k6`가 "Unable to locate package"로 끝납니다.
> 릴리스 tarball을 쓰고, **로컬과 버전을 맞춥니다.**
>
> ```bash
> V=v2.1.0                       # k6 version 으로 로컬 값을 확인해 맞출 것
> curl -fsSL -o /tmp/k6.tar.gz \
>     "https://github.com/grafana/k6/releases/download/${V}/k6-${V}-linux-arm64.tar.gz"
> tar xzf /tmp/k6.tar.gz -C /tmp
> sudo install -m 755 "/tmp/k6-${V}-linux-arm64/k6" /usr/local/bin/k6
> ```

**JVM은 벤더를 로컬과 맞춥니다.** 배포판 기본 openjdk가 아니라 Corretto를 쓰는 이유는,
JIT 구현이 다른 JVM을 섞으면 워밍업으로 갈리는 차이가 어디서 왔는지 갈라낼 수 없기 때문입니다.

```bash
wget -qO - https://apt.corretto.aws/corretto.key | sudo gpg --dearmor -o /usr/share/keyrings/corretto.gpg
echo "deb [signed-by=/usr/share/keyrings/corretto.gpg] https://apt.corretto.aws stable main" \
    | sudo tee /etc/apt/sources.list.d/corretto.list
sudo apt update && sudo apt install -y java-21-amazon-corretto-jdk postgresql-client
```

`postgresql-client`는 다음 절의 [init-db.sh](init-db.sh)가 씁니다.

### 1-1. Redis 설정 — 통째로 갈아끼우지 않습니다

[infra/redis/redis-cluster-aws.conf](../redis/redis-cluster-aws.conf)를 그대로
`/etc/redis/redis.conf`에 덮으면 안 됩니다. Debian 패키징이 넣어 둔 `dir` · `logfile` · `pidfile`이
사라지는데, 유닛이 `ReadWritePaths=-/var/lib/redis`, `-/var/log/redis`로 경로를 못 박아 두어
어긋나면 기동이 깨집니다.

반대로 `supervised`·`daemonize`는 **손댈 필요가 없습니다** — 유닛의 `ExecStart`가
`--supervised systemd --daemonize no`를 커맨드라인으로 덮어씁니다.

프로젝트 파일을 base로 두고 그 세 줄과 `cluster-announce-ip`를 덧붙입니다.

```bash
# 원본은 한 번만 백업한다. 두 번째 실행에서 덮으면 이미 고친 것을 원본으로 남기게 된다.
[ -f /etc/redis/redis.conf.debian-orig ] || sudo cp /etc/redis/redis.conf /etc/redis/redis.conf.debian-orig

# 아래 redis-cluster-aws.conf는 이 저장소 파일을 scp로 올려 둔 것이다.
cp redis-cluster-aws.conf /tmp/redis.conf.new
cat >> /tmp/redis.conf.new <<'EOF'

dir /var/lib/redis
logfile /var/log/redis/redis-server.log
pidfile /run/redis/redis-server.pid
EOF

# 노드가 gossip에서 자기 주소로 알릴 값. bind가 0.0.0.0이라 스스로 고르게 두면
# 엉뚱한 주소를 고를 수 있고, 그러면 형성은 되고 그 뒤 failover가 조용히 어긋난다.
TOKEN=$(curl -sX PUT http://169.254.169.254/latest/api/token \
    -H 'X-aws-ec2-metadata-token-ttl-seconds: 300')
echo "cluster-announce-ip $(curl -s -H "X-aws-ec2-metadata-token: $TOKEN" \
    http://169.254.169.254/latest/meta-data/local-ipv4)" >> /tmp/redis.conf.new

sudo install -m 640 -o redis -g redis /tmp/redis.conf.new /etc/redis/redis.conf
sudo rm -f /var/lib/redis/nodes.conf     # 이전 클러스터의 기억이 남아 있으면 형성이 거부된다
sudo systemctl restart redis-server
```

`bind`는 프로젝트 파일이 이미 `0.0.0.0`입니다. 패키지 기본값은 `bind 127.0.0.1 -::1`이라
그대로 두면 **보안 그룹을 열어도 WAS가 붙지 못합니다** — 먼저 의심할 지점입니다.

확인:

```bash
redis-cli CONFIG GET maxmemory       # 3gb면 3221225472
redis-cli CONFIG GET save            # 빈 문자열 (RDB 꺼짐)
redis-cli CONFIG GET cluster-enabled # yes
sudo ss -lntp | grep -E '6379|16379' # 데이터 포트와 클러스터 버스 둘 다
```

`redis-benchmark`는 Redis 인스턴스에 설치할 필요가 없습니다. k6 인스턴스의 `redis-tools`에 들어 있어
거기서 원격으로 때립니다 — 앱이 가는 것과 같은 네트워크 경로라 그편이 맞습니다.

### 1-2. 클러스터 형성

6대가 다 뜬 뒤에 **k6 인스턴스에서** 한 번 합니다. `provision.sh --wait`가 찍어 준 프라이빗 IP를
씁니다. **앞의 셋이 마스터, 뒤의 셋이 레플리카**로 하나씩 자동 배정됩니다.

```bash
redis-cli --cluster create \
  <redis1>:6379 <redis2>:6379 <redis3>:6379 \
  <redis4>:6379 <redis5>:6379 <redis6>:6379 \
  --cluster-replicas 1 --cluster-yes
```

로컬(한 대에 6프로세스)과 달리 여기서는 노드마다 호스트가 달라, "레플리카를 마스터와 다른 호스트에
둔다"는 Redis의 배치 휴리스틱이 실제로 일합니다. 로컬 드릴이 확인하지 못한 대역이 이것입니다.

확인:

```bash
redis-cli --cluster check <redis1>:6379   # 마스터 3, 각 레플리카 1, 16384 슬롯 전부 커버
redis-cli -h <redis1> cluster shards
```

이어서 **샤드 셋이 서로 다른 마스터에 떨어지는지** 봅니다. 이게 어긋나면 아무리 노드를 늘려도
한 노드만 일하게 됩니다.

```bash
for t in a b c; do redis-cli -h <redis1> cluster keyslot "{$t}"; done
# 15495 / 3300 / 7365 — 3등분 구간(0~5460 / 5461~10922 / 10923~16383)에 하나씩
```

근거와 실패 모드는 [docs/redis-cluster-bootstrap.md](../../docs/redis-cluster-bootstrap.md) 2절에
있습니다. 부하를 넣은 뒤 실제 분배는 `measure.sh`의 "샤드별 분배" 블록이 매 런 찍습니다.

### 2. DB 초기화와 환경 변수

[init-db.sh](init-db.sh)가 RDS에 애플리케이션 롤·데이터베이스를 만들고 `/etc/waiting/env`까지 씁니다.
로컬에는 없던 단계인 이유와 `must be able to SET ROLE` 함정은 그 스크립트 헤더에 있습니다.

```bash
RDS_HOST=<rds-endpoint> REDIS_CLUSTER_NODES=<m1>:6379,<m2>:6379,<m3>:6379 ./init-db.sh
```

손으로 채운다면 [env.example](env.example)을 복사합니다 — 무엇을 왜 채우는지는 그 파일의 주석에 있습니다.

```bash
sudo install -m 640 -o root -g waiting env.example /etc/waiting/env
sudo vi /etc/waiting/env
```

### 3. WAS 기동

```bash
sudo install -m 644 waiting.service /etc/systemd/system/waiting.service
sudo install -m 644 -o waiting -g waiting \
     server/build/libs/waiting-0.0.1.jar /opt/waiting/
sudo systemctl daemon-reload && sudo systemctl start waiting

journalctl -u waiting -f
ps aux | grep [j]ava          # 비밀번호가 보이지 않아야 한다
```

손으로 띄울 때도 같은 파일을 씁니다.

```bash
set -a; . /etc/waiting/env; set +a
java -Xms2g -Xmx2g -jar waiting-0.0.1.jar --spring.profiles.active=loadtest
```

`loadtest` 프로파일이어야 합니다. 요청당 `log.debug`가 켜져 있으면 `System.out`의 락이
가상 스레드 pinning 지점이라 큐가 아니라 로거를 재게 됩니다.

**힙은 고정합니다.** 기본값은 물리 메모리의 1/4이라 인스턴스를 `large → xlarge → 2xlarge`로 키우며
쓸면 힙도 함께 커집니다. 스윕의 목적이 vCPU당 처리량인데 힙이 같이 움직이면 무엇이 처리량을
바꿨는지 갈라낼 수 없습니다.

**톰캣 설정 둘은 이름이 비슷하지만 막는 것이 다릅니다.** 둘을 섞으면 원인을 못 짚습니다.

| 설정 | 겨루는 대상 | 넘치면 |
| --- | --- | --- |
| `accept-count`(1000) | **연결이 도착하는 속도** — 커널 accept 큐 크기. 실효 백로그는 `min(이 값, net.core.somaxconn)` | 커널이 연결을 버려 클라이언트에 connection reset. `nstat -az TcpExtListenOverflows`로 센다 |
| `processor-cache`(8192) | **동시에 열려 있는 연결 수** — 재사용할 `Http11Processor` 개수 | 조용히 느려진다. 오류가 안 나고 요청당 힙 할당만 늘어 GC가 CPU를 먹는다 |

측정에서는 **`accept-count`는 한 번도 안 넘쳤고**(8대 전부 `ListenOverflows` 0), 대신
`processor-cache` 기본값(200)이 동시 연결 6,000대에서 처리량을 35% 깎고 있었습니다.
**증상이 오류가 아니라 지연이라 찾기 어렵습니다** — 근거는 [루트 README의 측정 결과](../../README.md#측정하면서-찾아낸-문제).

> **시각 함정.** `QueueConfig.java`가 `ZoneId.of("Asia/Seoul")`을 하드코딩하므로 창 키
> (`waiting:holiday:20260728`)는 서버 TZ에 흔들리지 않습니다. 다만 **EC2 기본 TZ는 UTC**라
> 로그 타임스탬프가 9시간 어긋나 보입니다. `measure.sh`는 `TZ=Asia/Seoul date`로 명시해 안전합니다.

### 4. 측정

```bash
# k6 인스턴스에서
export WAS_HOST=10.0.1.20 REDIS_HOSTS=10.0.1.30,10.0.1.31,10.0.1.32   # 마스터만

./measure.sh enqueue                 # 진입 처리량 (closed — 포화되는 곳은 CPU)
USERS=1500000 VUS=1000 ./measure.sh enqueue
NO_REUSE=1 USERS=1500000 ./measure.sh enqueue   # 연결당 요청 1회 — 실제 오픈 순간에 가장 가깝다
./measure.sh status                  # 조회 폴링
RATE=100000 DURATION=15s MAXVUS=10000 ./measure.sh burst   # 동시 도착 (open)
./measure.sh mixed                   # 폴링 중에 스파이크 — 아래 「샤딩 검증」
```

각 시나리오가 **서로 다른 것을 잰다는 것**, 워밍업이 왜 들어 있는지, `WARMUP=0`으로 이어 잴 때의
주의사항은 [measure.sh](measure.sh) 헤더에 있습니다. 읽고 시작하십시오 — 여기서 갈린 수치는
나란히 둘 수 없습니다.

**`NO_REUSE=1`을 빠뜨리면 처리량을 34% 후하게 잡습니다.** 기본값으로 재면 VU 하나가 연결 하나를
1,400번 넘게 우려먹는데, 실제 사용자는 각자 새 연결로 한 번 요청합니다.

**`burst`의 `MAXVUS`는 낮게 잡습니다.** 이건 서버가 아니라 **부하 생성기가 여는 동시 연결 수**입니다.
5만까지 올리면 처리량이 오히려 27% 떨어지는데, 그건 서버의 성질이 아니라 그 조건이 만든 부하입니다.
그리고 NLB(단일 IP) 대상이면 **임시 포트 55,536개가 동시 연결의 실질 상한**입니다.

## 2단계로 넘어가기 전에

README의 [인프라 아키텍처](../../README.md#인프라-아키텍처)가 짚어 둔 두 가지 — **세션 외부화**와
**스케줄러 단일화** — 는 이미 구현되어 있습니다. 배경은 `Member.java`와 `application.yml`의
`queue.scheduler-enabled` 주석에 있습니다. 아래는 이 둘이 준비됐다는 전제로 WAS 2대를 실제로
배치하는 방법입니다.

### 인스턴스

WAS 2대 모두 **`c8g.large`로 시작**합니다. 1단계 기준선과 인스턴스 타입을 맞춰야 "대수 효과"와
"코어 수 효과"가 섞이지 않습니다. `xlarge`로 스윕하는 건 대수 효과를 먼저 확인한 다음입니다.

### NLB + 타깃 그룹

내부(internal) NLB, TCP:8080 리스너, 타깃 타입 instance로 WAS1·WAS2를 등록합니다. NLB는 instance
타깃 타입에서 클라이언트 소스 IP를 보존하므로, 기존 "WAS 8080 ← k6 SG" 규칙은 k6 트래픽에 그대로
쓰입니다. 다만 **NLB 자체의 헬스체크**는 로드밸런서 노드의 사설 IP에서 오므로 k6 SG 규칙만으로는
안 잡힙니다 — WAS SG 8080에 VPC(또는 NLB가 뜬 서브넷) CIDR을 허용하는 규칙을 추가로 열어야 합니다.

```bash
aws ec2 authorize-security-group-ingress --group-id $SG \
    --protocol tcp --port 8080 --cidr <VPC CIDR>   # NLB 헬스체크용
```

### 시크릿 공유 — `init-db.sh`를 WAS2에서 다시 돌리면 안 됩니다

[init-db.sh](init-db.sh)는 실행할 때마다 `ALTER ROLE ... PASSWORD`로 **새 비밀번호를 생성**합니다.
WAS2에서 무심코 다시 돌리면 WAS1이 쓰던 비밀번호가 조용히 바뀌어 WAS1이 DB 인증 실패로 죽습니다.

1. **WAS1에서만** `init-db.sh`를 실행합니다.
2. 결과로 만들어진 `/etc/waiting/env`를 WAS2에 `scp`로 복사합니다(권한 640 root:waiting 유지).
3. WAS2의 env 파일에 한 줄만 고쳐 적습니다: `QUEUE_SCHEDULER_ENABLED=false`.

WAS1은 기본값(`true`, 또는 env 파일에서 줄 자체를 생략)을 그대로 둡니다 — 스케줄러는 WAS1에서만 돕니다.

### 측정

`WAS_HOST`에 NLB DNS를, `METRICS_HOST`에 스케줄러를 보유한 WAS1의 프라이빗 IP를 따로 줍니다
(이유는 [measure.sh](measure.sh) 헤더 참고).

```bash
WAS_HOST=<nlb-dns> METRICS_HOST=<WAS1 프라이빗 IP> REDIS_HOSTS=<m1>,<m2>,<m3> \
    ./measure.sh enqueue
```

## 샤딩 검증 — 단일 노드의 한계를 걷어냈는가

2026-07-29 측정의 결론은 **WAS 8대 · 138,548 req/s가 단일 Redis 노드 한계(redis-benchmark
139,912 rps)의 99.0%** 였습니다. 더 늘릴 곳이 Redis 한 대뿐이었다는 뜻이고, 해시 태그 3샤드는
정확히 그 한 대를 셋으로 나누려는 변경입니다.

확인할 것은 "처리량이 올랐나" 하나가 아닙니다. **포화가 어디로 옮겨갔는가**가 같이 나와야
그 상승을 샤딩 덕분이라고 말할 수 있습니다.

### 두 런을 나란히 잰다

| 런 | Redis | `queue.shard-count` | 기대 |
| --- | --- | --- | --- |
| **A (대조군)** | 1노드 | 1 | 7월 값 재현 — 약 138,500 req/s, 그 노드 CPU가 포화 부근 |
| **B (샤딩)** | 마스터 3 + 레플리카 3 | 3 | 처리량 상승, **마스터 3대 CPU가 각각 A의 1/3 수준으로 내려감** |

**A를 건너뛰지 않습니다.** 7월 이후 앱이 바뀌었으므로(단일 201 응답, Kafka 진입 제거) 그때 수치를
지금 코드의 대조군으로 쓸 수 없습니다. A가 7월 값을 재현하지 못하면 B의 차이가 샤딩에서 온 것인지
그 사이 변경에서 온 것인지 가를 수 없습니다.

A는 노드 하나만 띄우고 `REDIS_HOSTS`에 그 하나만 주면 그대로 돌아갑니다. 앱 쪽은
`--queue.shard-count=1`로 띄웁니다 — 샤드가 하나면 해시 태그도 하나라 키가 전부 그 노드에 모입니다.

### 순서

```bash
# k6 인스턴스에서
export WAS_HOST=<nlb-dns> METRICS_HOST=<WAS1 IP>

# 런 A — 단일 노드
export REDIS_HOSTS=<redis1>
USERS=1500000 VUS=1000 ./measure.sh enqueue

# 런 B — 마스터 3대. 앱을 shard-count=3으로 재기동한 뒤
export REDIS_HOSTS=<m1>,<m2>,<m3>
USERS=1500000 VUS=1000 ./measure.sh enqueue
```

`REDIS_HOSTS`에는 **마스터만** 적습니다. 레플리카는 슬롯을 갖지 않아 적으면 초기화가 아무 키도
못 찾고, CPU·ops 합계에는 복제로 들어온 몫이 얹혀 분모가 흐려집니다.

### 읽는 법

`measure.sh`가 찍는 세 블록을 순서대로 봅니다.

**1. 샤드별 분배** — 갈렸는지부터 봅니다. 여기가 어긋나면 아래 두 블록은 볼 필요가 없습니다.

```
샤드별 분배:
  {a}  waiting 333        active 334      seq 667
  {b}  waiting 345        active 333      seq 678
  {c}  waiting 322        active 333      seq 655
```

| 나온 모양 | 뜻 | 볼 곳 |
| --- | --- | --- |
| 셋이 비슷 | 정상 | — |
| 한 샤드가 0 | 해시 태그가 같은 노드에 몰렸습니다 | `CLUSTER KEYSLOT`(1-2절), `QueueKeysTest` |
| 셋이 크게 벌어짐 | `QueueKeys.shardOf`의 분배가 깨졌습니다 | `QueueShardingTest.shardOfSpreadsTokensEvenly` |

**2. 노드별 CPU** — 이번 측정의 결론이 나오는 자리입니다.

```
CPU 10.0.1.30   32.1%  <- 코어 1개 대비
CPU 10.0.1.31   31.4%  <- 코어 1개 대비
CPU 10.0.1.32   33.0%  <- 코어 1개 대비
```

A에서 100% 부근이던 값이 B에서 셋 다 그 1/3 근처로 내려가면 **포화가 Redis에서 WAS로 옮겨간
것**입니다. 한 노드만 여전히 높으면 부하가 안 갈린 것이라 1번으로 돌아갑니다.

**3. 실효 유입률** — 처리량 그 자체입니다. A에서 약 140,000/s에 붙어 멈췄다면 그게 단일 노드의
한계이고, B에서 그 위로 올라간 만큼이 샤딩이 걷어낸 몫입니다.

### 이 계정 한도로는 3샤드의 새 한계까지 못 갑니다

On-Demand Standard vCPU 한도가 **64**입니다.

| 역할 | 배분 |
| --- | --- |
| k6 `c8g.8xlarge` | 32 |
| Redis 6 × `c8g.large` | 12 |
| WAS 남은 몫 | 20 → `c8g.large` **10대** |

WAS 10대면 약 173,000 req/s가 이론값이고, `c8g.8xlarge` 부하 생성기도 그 부근(2xlarge가 44,000에서
포화했으므로 약 176,000)이 상한입니다. 3샤드의 이론 용량(약 420,000 rps)에는 한참 못 미칩니다.

즉 이 한도 안에서 말할 수 있는 것은 **"7월의 포화가 사라졌다"까지**이고, **"3샤드의 새 한계는
얼마인가"는 답할 수 없습니다.** 답하려면 `L-1216C47A` 상향이 먼저입니다(무료, 승인까지 보통 몇 시간).

검증 등식은 단순해졌습니다 — 진입이 동기 경로 하나뿐이라 항이 붙지 않습니다.

```
seq(샤드 3개 합) == k6 요약의 201 수
waiting + active (샤드 3개 합) == seq
active 합 <= queue.capacity
```

## 다음에 채울 측정

README「측정 결과」의 셋은 재지 못하고 계산·추론으로 대신했습니다. 결론을 뒤집을 값은 아니지만,
다시 잴 때 채워야 할 자리라 적어 둡니다.

| 못 잰 것 | 지금 어떻게 대신했나 | 다시 재려면 |
| --- | --- | --- |
| 최대 부하 런의 `ops/sec` 피크 | 명령 수 총량에서 **계산**했습니다(약 68만/초). 초당 표본의 실측 피크가 아닙니다 | `measure.sh`가 찍는 `ops/sec 피크`를 그대로 기록 |
| `mixed` 런의 Redis 포화 여부 | `measure.sh`가 런 전체 구간으로 CPU를 평균 내는데 스파이크는 그중 15초뿐이라, 나온 24.2%는 포화 여부를 말해 주지 않습니다 | 스파이크 구간만 잘라 CPU를 내도록 `measure.sh` 수정 |
| 3샤드의 새 한계 | 아직 재지 못했습니다. vCPU 한도 64 안에서는 부하 생성기가 먼저 막힙니다(위 「이 계정 한도로는」) | `L-1216C47A` 상향 뒤 WAS를 늘려 가며 스윕 |
| 호스트 단위 failover | 로컬 6프로세스 드릴은 메커니즘만 확인했습니다. 노드마다 호스트가 다른 대역은 아직입니다 | 부하 중에 마스터 인스턴스 하나를 stop하고 `--cluster check`로 승격 확인 |

## 로컬과 달라지는 변수

**AWS에서 잰 값을 로컬에서 잰 값과 나란히 두면 안 됩니다.** 바뀌는 것이 한둘이 아닙니다.

| 변수 | 로컬 | AWS |
| --- | --- | --- |
| Redis 경로 | Docker 포트 포워딩 (왕복 0.31ms) | 같은 AZ의 EC2 |
| 부하 생성기 | 같은 머신, CPU 경합 | 별도 인스턴스 |
| CPU | Apple Silicon, k6·앱·Redis가 공유 | Graviton4, 역할별 전용 |
| OS | macOS | Ubuntu 26.04 LTS (arm64) |
| Redis 버전 | 8.8.0 (Docker 이미지) | 8.0.5 (apt) |
| JVM | Corretto 21.0.2 | Corretto 21.0.12 |
| Redis CPU 상한 | 컨테이너 `cpus: "1.0"` | 인스턴스 2 vCPU (단일 스레드라 실제로는 1개) |
| DB | Docker PostgreSQL 18.4 | RDS PostgreSQL 18.3 |
| `max_connections` | Docker 기본 100 | RDS 기본 79 → **300** (파라미터 그룹). WAS 8대 × Hikari 10이면 기본값으로는 8대째가 못 뜬다 |
| 커널 백로그 | macOS `kern.ipc.somaxconn` | Linux `net.core.somaxconn` |

Redis와 JVM은 **같은 메이저·같은 벤더 안의 차이**라 그대로 갑니다. 다만 기록은 남깁니다 —
나중에 설명되지 않는 차이가 나오면 여기부터 봅니다.

## 비용

ap-northeast-2 온디맨드 기준(2026-08 조회값):

| 항목 | 시간당 |
| --- | --- |
| k6 `c8g.8xlarge` × 1 | $1.436 |
| WAS `c8g.large` × 8 | $0.718 |
| Redis `c8g.large` × 6 | $0.539 |
| RDS `db.t4g.micro` | $0.025 |
| NLB | 약 $0.03 |
| **합계** | **약 $2.75/시간** |

측정 세션 4시간이면 약 $11입니다. 측정은 몰아서 하는 작업이라 **끝나면 terminate**하면
AMI 스냅샷 보관료(월 $1 미만)만 남습니다. 켜 둔 채로 두면 하루 약 $66입니다.

스팟은 쓰지 않습니다. 런 도중에 회수되면 그 런이 통째로 날아가고, 워밍업 60만 건부터 다시입니다.
