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
| k6 | `c8g.2xlarge` (8 vCPU) | WAS의 4배. 포화 여부는 `dropped_iterations`와 자체 CPU로 확인 |
| WAS | `c8g.large` (2 vCPU · 4 GiB) → 키우며 스윕 | 작게 시작해야 포화됩니다. `large → xlarge → 2xlarge` 스윕이 곧 vCPU당 처리량 측정이고, 그게 2단계 계획의 근거가 됩니다 |
| Redis | `c8g.large` (2 vCPU · 4 GiB) | 단일 스레드·단일 핫 키라 코어를 늘려도 안 늡니다. 필요한 건 단일코어 클럭과 PPS |
| RDS | `db.t4g.micro` | 대기열 hot path에 걸리지 않습니다. Hikari `maximum-pool-size: 10`이 이미 상한입니다 |
| Kafka | `c8g.xlarge` (4 vCPU) | Kafka 경유 진입을 잴 때만 띄웁니다. WAS·Redis보다 큰 이유는 맨 위 원칙과 같습니다 — 브로커는 측정 대상이 아니라 측정 장비 쪽입니다 |

**Kafka 브로커에 `large`를 쓰면 안 됩니다.** 브로커가 받는 초당 메시지 수는 진입 요청 수와 같아서
8대 포화(**138,548 req/s**)를 그대로 맞습니다. 그 지점에서 브로커가 먼저 막히면 재려던 Redis 대신
브로커를 재게 되고, 유입률이 안 오르는 이유를 `ingest-per-second` 상한 탓으로 잘못 읽게 됩니다.
런마다 브로커 CPU를 함께 확인하고 **70%를 넘으면 그 런은 버립니다.**

Graviton(ARM)을 고른 이유는 개발 머신이 Apple Silicon이라 **아키텍처가 같아 비교가 깨끗**해서입니다.
OS는 **Ubuntu 26.04 LTS (arm64)**이고, 아래 설치 명령이 전부 `apt` 기준인 이유입니다.

**Redis `maxmemory`는 프로젝트 `redis.conf`의 값을 그대로 씁니다.** 대기자 한 명이 `waiting`과 `poll`
두 곳을 차지해([`enqueue.lua`](../../waiting-server/src/main/resources/redis/enqueue.lua)가 진입
시점에 둘 다 씁니다) 실측 1인당 **276바이트**이고, 200만 명이면 약 **552 MiB**입니다.
`noeviction`이라 넘으면 쓰기가 거부되고, 그건 버그가 아니라 설계된 동작이지만 측정 도중에 만나면
그 런은 버려야 합니다.

> **워밍업까지 계산에 넣습니다.** `measure.sh`의 워밍업은 본 측정 전에 초기화되지만, 그 사이에는
> 워밍업 인원이 통째로 올라가 있습니다. 대당 워밍업량을 유지하려고 WAS 대수에 비례해 늘리면
> (8대면 480만 건) **1.32 GB로 1gb를 넘깁니다.** 8대 측정에서는 3gb로 올렸습니다.

## 배치 — 전부 같은 AZ

**같은 VPC · 같은 AZ · 같은 서브넷에 둡니다.**

로컬에서 Docker 왕복 0.31ms가 "Redis가 실제로 일하는 8.37μs의 약 37배"였습니다.
**크로스 AZ가 그 교훈의 AWS 판본입니다** — 왕복 지연이 그대로 돌아오고 데이터 전송 비용까지 붙습니다.

**클러스터 배치 그룹은 쓰지 않았습니다.** 같은 AZ·같은 서브넷으로 충분했고, 배치 그룹은 용량 확보
실패로 인스턴스가 아예 안 뜨는 실패 모드를 하나 더 얹습니다. 위 수치는 배치 그룹 없이 나온 값입니다.

## 보안 그룹이 유일한 방어선입니다

Spring Security를 쓰지 않고, actuator `/metrics`에 인증이 없고,
[redis.conf](../redis/redis.conf)의 Redis는 `protected-mode no`에 비밀번호도 없습니다.
네트워크 경계가 전부입니다.

| 대상 | 포트 | 인바운드 허용 |
| --- | --- | --- |
| WAS | 8080 | k6 보안 그룹 + 내 IP |
| Redis | 6379 | WAS 보안 그룹 + **k6 보안 그룹** |
| RDS | 5432 | **WAS 보안 그룹만** |
| Kafka | 9092 | **WAS 보안 그룹만** (프로듀서·컨슈머 둘 다 WAS 안에 있습니다) |
| 전부 | 22 | 내 IP (또는 SSM Session Manager로 대체) |

**Redis 6379에 k6도 넣어야 합니다.** [measure.sh](measure.sh)가 k6 인스턴스에서 `redis-cli -h $REDIS_HOST`로
초기화와 검증을 합니다. WAS만 열면 측정이 첫 줄에서 멈춥니다. 마찬가지로 **WAS 8080을 내 IP에만 열면
k6가 붙지 못합니다** — 자기 참조 규칙이 따로 필요하고, 증상이 타임아웃이라 앱 문제로 보이기 쉽습니다.

지금은 셋이 **보안 그룹 하나를 공유**하고 6379·5432를 자기 참조로 열어 두었습니다.
역할별로 나누는 것보다 규칙이 적고, 1단계는 어차피 인스턴스가 짧게 살기 때문입니다.

```bash
SG=<security-group-id>
aws ec2 authorize-security-group-ingress --group-id $SG --ip-permissions \
  "IpProtocol=tcp,FromPort=8080,ToPort=8080,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=6379,ToPort=6379,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=5432,ToPort=5432,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=9092,ToPort=9092,UserIdGroupPairs=[{GroupId=$SG}]"   # Kafka 경유 측정 때만
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
./provision.sh was 8                     # WAS 8대
./provision.sh redis
./provision.sh kafka --wait              # Kafka 경유 측정 때만
./provision.sh k6 --wait                 # 뜰 때까지 기다렸다 프라이빗 IP까지 출력
```

JAR 배포와 DB 시크릿(2~3단계)은 여전히 손으로 합니다 — 둘 다 실행마다 값이 바뀌거나
비밀번호를 다뤄서 user-data에 구우면 오히려 위험해집니다. 아래 1단계는 삭제하지 않고
남겨 뒀습니다 — `provision.sh`가 내부적으로 정확히 이 순서를 user-data로 실행합니다.
"왜 이 값이어야 하는가"는 이 절의 설명이 유일한 근거입니다.

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

[infra/redis/redis.conf](../redis/redis.conf)를 그대로 `/etc/redis/redis.conf`에 덮으면 안 됩니다.
Debian 패키징이 넣어 둔 `dir` · `logfile` · `pidfile`이 사라지는데, 유닛이
`ReadWritePaths=-/var/lib/redis`, `-/var/log/redis`로 경로를 못 박아 두어 어긋나면 기동이 깨집니다.

반대로 `supervised`·`daemonize`는 **손댈 필요가 없습니다** — 유닛의 `ExecStart`가
`--supervised systemd --daemonize no`를 커맨드라인으로 덮어씁니다.

프로젝트 파일을 base로 두고 그 세 줄만 덧붙입니다. `maxmemory`는 프로젝트 파일 값을 그대로 쓰되,
워밍업까지 담기지 않는 규모라면(위 참고) 여기서 `sed`로 올립니다.

```bash
# 원본은 한 번만 백업한다. 두 번째 실행에서 덮으면 이미 고친 것을 원본으로 남기게 된다.
[ -f /etc/redis/redis.conf.debian-orig ] || sudo cp /etc/redis/redis.conf /etc/redis/redis.conf.debian-orig

# 아래 redis.conf는 이 저장소의 infra/redis/redis.conf를 scp로 올려 둔 것이다.
# WAS 8대(워밍업 480만 건)에서는 3gb가 필요했다. 그 미만이면 그냥 cp 해도 된다.
sed 's/^maxmemory .*$/maxmemory 3gb/' redis.conf > /tmp/redis.conf.new
cat >> /tmp/redis.conf.new <<'EOF'

dir /var/lib/redis
logfile /var/log/redis/redis-server.log
pidfile /run/redis/redis-server.pid
EOF
sudo install -m 640 -o redis -g redis /tmp/redis.conf.new /etc/redis/redis.conf
sudo systemctl restart redis-server
```

`bind`는 프로젝트 파일이 이미 `0.0.0.0`입니다. 패키지 기본값은 `bind 127.0.0.1 -::1`이라
그대로 두면 **보안 그룹을 열어도 WAS가 붙지 못합니다** — 먼저 의심할 지점입니다.

확인:

```bash
redis-cli CONFIG GET maxmemory     # 위에서 지정한 값 (3gb면 3221225472)
redis-cli CONFIG GET save          # 빈 문자열 (RDB 꺼짐)
sudo ss -lntp | grep 6379          # 0.0.0.0:6379
```

`redis-benchmark`는 Redis 인스턴스에 설치할 필요가 없습니다. k6 인스턴스의 `redis-tools`에 들어 있어
거기서 `-h $REDIS_HOST`로 원격으로 때립니다 — 앱이 가는 것과 같은 네트워크 경로라 그편이 맞습니다.

### 2. DB 초기화와 환경 변수

[init-db.sh](init-db.sh)가 RDS에 애플리케이션 롤·데이터베이스를 만들고 `/etc/waiting/env`까지 씁니다.
로컬에는 없던 단계인 이유와 `must be able to SET ROLE` 함정은 그 스크립트 헤더에 있습니다.

```bash
RDS_HOST=<rds-endpoint> REDIS_HOST=<redis-private-ip> ./init-db.sh
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
     waiting-server/build/libs/waiting-0.0.1.jar /opt/waiting/
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
export WAS_HOST=10.0.1.20 REDIS_HOST=10.0.1.30

./measure.sh enqueue                 # 진입 처리량 (closed — 포화되는 곳은 CPU)
USERS=1500000 VUS=1000 ./measure.sh enqueue
NO_REUSE=1 USERS=1500000 ./measure.sh enqueue   # 연결당 요청 1회 — 실제 오픈 순간에 가장 가깝다
./measure.sh status                  # 조회 폴링
RATE=100000 DURATION=15s MAXVUS=10000 ./measure.sh burst   # 동시 도착 (open)
./measure.sh mixed                   # 폴링 중에 스파이크 — 아래 「Kafka 경유 진입 검증」
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
WAS_HOST=<nlb-dns> METRICS_HOST=<WAS1 프라이빗 IP> REDIS_HOST=<redis-private-ip> \
    ./measure.sh enqueue
```

## Kafka 경유 진입 검증

`queue.enqueue-via-kafka`는 처리량 장치가 아닙니다. `enqueue.lua`와 `status.lua`가 Redis 단일
스레드를 공유하기 때문에 **개시 스파이크가 이미 줄 서 있는 사람의 조회 지연으로 번지는 것**을
막는 장치입니다. 그래서 확인할 것도 "처리량이 올랐나"가 아니라 **"진입을 잘라 낸 만큼이 조회에
남는가"** 하나입니다.

### 대수로 나누는 두 값

**설정 전부가 WAS 1대 기준입니다.** 그대로 8대에 올리면 측정이 성립하지 않습니다.

| 값 | 어디에 | 8대일 때 | 안 바꾸면 |
| --- | --- | --- | --- |
| `QUEUE_KAFKA_INGEST_PER_SECOND` | 대마다 `/etc/waiting/env` | **7500** (60000 ÷ 8) | 실효 유입률이 480,000/s가 되어 Redis 한계(약 140,000)의 3.4배입니다. 상한이 한 번도 안 걸려서 **"켰는데 아무 차이가 없다"**가 나오는데, 그건 Kafka가 아니라 이 줄 탓입니다 |
| `queue.kafka.partitions` | `application.yml` (전 대 공통) | **32** (8대 × `consumers: 4`) | 컨슈머 32개 중 4개만 파티션을 받고 28개가 놉니다. `consumers <= partitions` 검증은 인스턴스 안에서만 보므로 **8대가 전부 멀쩡히 뜨고 경고도 없습니다.** 더 나쁜 건 재현성입니다 — 상한이 JVM별이라 그 4개가 몇 대에 흩어지느냐에 따라 유입률이 런마다 달라집니다 |

`QUEUE_SCHEDULER_ENABLED`와 같은 종류의 제약이고, 빠뜨렸을 때 조용한 것도 같습니다.

### 순서

```bash
# 1. 대마다 /etc/waiting/env 에 세 줄 (env.example 참고). 그 뒤 재기동.
#      KAFKA_BOOTSTRAP_SERVERS=<kafka 프라이빗 IP>:9092
#      QUEUE_ENQUEUE_VIA_KAFKA=true
#      QUEUE_KAFKA_INGEST_PER_SECOND=7500
sudo systemctl restart waiting

# 2. 토픽이 32 파티션으로 생겼는지 브로커에서 확인 — 이게 위 표의 두 번째 함정을 잡는 유일한 지점
/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic queue-enqueue

# 3. k6 인스턴스에서. METRICS_HOSTS에 8대를 전부 줍니다 —
#    queue.enqueue.dropped만 컨슈머가 있는 전 대에 흩어져 있습니다.
export WAS_HOST=<nlb-dns> REDIS_HOST=<redis-ip>
export METRICS_HOST=<WAS1 IP> METRICS_HOSTS=<WAS1 IP>,<WAS2 IP>,...,<WAS8 IP>

./measure.sh mixed        # Kafka 켠 채로 / 끈 채로 각각
```

`mixed`는 깊이를 먼저 쌓고(`PREFILL`, 기본 50만 — **워밍업과 달리 지우지 않습니다**) 폴링을
띄운 뒤 `SPIKE_AT`초에 진입 스파이크를 넣습니다. 빈 큐에서 시작하면 폴링 VU가 전부 큐 앞머리에
서서 아무것도 재지 못하기 때문입니다.

**깊이를 바꾸면 비교 대상도 같이 다시 재야 합니다.** 깊이가 곧 조회 비용이라, 50만과 103만을
같은 조건으로 잰 런에서 조회 p95가 quiet 1.08 → 1.46ms, spike 31.83 → 50.68ms로 벌어졌습니다.

### 읽는 법

k6 요약에서 조회 지연이 스파이크 전후로 갈려 나옵니다.

```
http_req_duration{phase:status,window:quiet}   스파이크 전 — 이 런의 기준선
http_req_duration{phase:status,window:spike}   스파이크 중 — 번짐의 크기
```

**보는 값은 두 절대치가 아니라 둘의 비입니다.** 기준선을 같은 런에서 뽑는 것이 핵심입니다 —
다른 런과 비교하면 JIT·페이지 캐시가 섞입니다. Kafka를 켜서 그 비가 1에 가까워지면 성공입니다.

`measure.sh`가 찍는 **실효 유입률**이 그 다음입니다. k6가 보는 req/s는 접수(202)까지의 속도라
상한과 무관하게 그대로 나오고, 상한이 실제로 걸렸는지는 이 값에서만 보입니다.

| 나온 값 | 뜻 |
| --- | --- |
| 약 60,000/s | 상한이 걸렸습니다. 위 표의 두 값이 맞습니다 |
| 약 480,000/s | `QUEUE_KAFKA_INGEST_PER_SECOND`를 대수로 안 나눴습니다 |
| 런마다 흔들림 | `partitions`가 전체 컨슈머 스레드 수보다 적습니다 |

검증 등식에는 항이 하나 붙습니다 — 접수(202)와 등록 사이에 컨슈머가 있고, 접수된 지
`stale-after`를 넘긴 메시지는 버리기 때문입니다.

```
seq == 접수 응답 수(k6 요약의 accepted) - queue.enqueue.dropped
```

`dropped`가 0이 아니면 접수와 등록 사이가 `stale-after`(60초)보다 벌어졌다는 뜻입니다.
보통 `ingest-per-second`를 **너무 작게** 나눈 경우입니다.

> **런을 중간에 끊었다면 토픽을 비우고 다시 시작합니다.** `measure.sh`의 초기화는 Redis 키만
> 지웁니다. 남은 메시지는 대부분 `stale-after`가 걸러 내지만 그만큼 `dropped`가 부풀어
> 위 등식이 깨집니다. 브로커에서:
> ```bash
> sudo systemctl stop kafka   # 컨슈머가 붙어 있으면 오프셋 리셋이 거부됩니다
> ```
> 보다 간단하게는 WAS를 멈춘 뒤 브로커에서 오프셋을 끝으로 밀어 둡니다:
> ```bash
> /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
>     --group queue-enqueue --topic queue-enqueue --reset-offsets --to-latest --execute
> ```

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

위 4대를 켜 두면 대략 **시간당 $0.5 안팎**입니다(리전마다 다르므로 확인이 필요합니다).
Kafka 브로커(`c8g.xlarge`)를 더하면 시간당 $0.15 안팎이 붙고, Kafka 경유를 재는 동안에만 필요합니다.
측정은 몰아서 하는 작업이라 **끝나면 stop**하면 EBS 요금만 남습니다.

스팟은 쓰지 않습니다. 런 도중에 회수되면 그 런이 통째로 날아가고, 워밍업 60만 건부터 다시입니다.
