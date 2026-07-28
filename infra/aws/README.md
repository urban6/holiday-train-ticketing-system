# AWS 측정 환경

> [← README](../../README.md)

로컬(단일 Mac)에서는 더 쪼갤 수 없는 것이 하나 남아 있습니다. 앱 지연 35ms 중 Redis는 1~3ms이고
나머지 90% 이상이 Tomcat·가상 스레드·루프백 TCP, 그리고 **같은 머신에서 도는 k6와의 CPU 경합**입니다.
그래서 여기서 하는 일의 절반은 부하 생성기를 떼어내는 것입니다.

## 두 단계로 나눕니다

| | 구성 | 여기서 얻는 것 |
| --- | --- | --- |
| **1단계** | k6 · WAS 1대 · Redis · RDS (**NLB 없음**) | 로컬 34k를 대체하는 기준선. vCPU당 처리량 |
| **2단계** | + NLB · WAS N대 | NLB가 얹는 비용, 수평 확장 효율 |

1단계에 NLB를 넣지 않는 이유는 두 가지입니다. WAS 1대의 천장을 모르는 상태에서는 N대의 이득을
말할 수 없고, **NLB가 얹는 비용을 아는 것 자체가 측정 결과**라 기준선에 섞으면 분리해 낼 수 없습니다.
k6는 WAS의 프라이빗 IP로 직접 붙습니다.

## 인스턴스

**WAS는 작게, k6는 크게.** 부하 생성기가 먼저 포화되면 측정 대상이 아니라 생성기를 재게 됩니다.

| 역할 | 인스턴스 | 근거 |
| --- | --- | --- |
| k6 | `c8g.2xlarge` (8 vCPU) | WAS의 4배. 포화 여부는 `dropped_iterations`와 자체 CPU로 확인 |
| WAS | `c8g.large` (2 vCPU · 4 GiB) → 키우며 스윕 | 작게 시작해야 포화됩니다. `large → xlarge → 2xlarge` 스윕이 곧 vCPU당 처리량 측정이고, 그게 2단계 계획의 근거가 됩니다 |
| Redis | `c8g.large` (2 vCPU · 4 GiB) | 단일 스레드·단일 핫 키라 코어를 늘려도 안 늡니다. 필요한 건 단일코어 클럭과 PPS |
| RDS | `db.t4g.micro` | 대기열 hot path에 걸리지 않습니다. Hikari `maximum-pool-size: 10`이 이미 상한입니다 |

Graviton(ARM)을 고른 이유는 개발 머신이 Apple Silicon이라 **아키텍처가 같아 비교가 깨끗**해서입니다.
Redis가 실제로 천장이 되면 그때 `c8i`(x86)와 나란히 재 보는 것 자체가 측정거리입니다.

OS는 **Ubuntu 26.04 LTS (arm64)**입니다. 아래 설치 명령이 전부 `apt` 기준인 이유입니다.

**Redis `maxmemory`는 1gb로 올립니다.** 목표가 200만 명인데 대기자 한 명이 `waiting`과 `poll`
두 곳을 차지합니다([`enqueue.lua`](../../waiting-server/src/main/resources/redis/enqueue.lua)가
진입 시점에 둘 다 씁니다). 로컬의 512mb로는 Redis 작업 메모리 여유가 남지 않습니다.
`noeviction`이라 넘으면 쓰기가 거부되고, 그건 버그가 아니라 설계된 동작이라 조용히 진행되지
않습니다 — 다만 측정 도중에 만나면 그 런은 버려야 합니다.

**1인당 크기는 규모에 따라 줄어듭니다.**
처음 잡았던 230바이트는 실측보다 작았습니다.

| 규모 | `used_memory` | 1인당 |
| --- | --- | --- |
| 30만 | 89.38 MB | 312 B |
| 100만 | 271.28 MB | **284 B** |
| 200만 (추정) | 약 **568 MB** | — `maxmemory 1gb`의 55% |

30만 한 점만으로 외삽하면 624MB로 과대추정됩니다. **두 점을 찍어야 기울기를 압니다.**

## 배치 — 전부 같은 AZ

**같은 VPC · 같은 AZ · 같은 서브넷에 두고, WAS와 Redis는 클러스터 배치 그룹에 넣습니다.**

로컬에서 Docker 왕복 0.31ms가 "Redis가 실제로 일하는 8.37μs의 약 37배"였습니다.
**크로스 AZ가 그 교훈의 AWS 판본입니다** —
왕복 지연이 그대로 돌아오고 데이터 전송 비용까지 붙습니다.

## 보안 그룹이 유일한 방어선입니다

Spring Security를 쓰지 않고, actuator `/metrics`에 인증이 없고,
[redis.conf](../redis/redis.conf)의 Redis는 `protected-mode no`에 비밀번호도 없습니다.
네트워크 경계가 전부입니다.

| 대상 | 포트 | 인바운드 허용 |
| --- | --- | --- |
| WAS | 8080 | k6 보안 그룹 + 내 IP |
| Redis | 6379 | WAS 보안 그룹 + **k6 보안 그룹** |
| RDS | 5432 | **WAS 보안 그룹만** |
| 전부 | 22 | 내 IP (또는 SSM Session Manager로 대체) |

**Redis 6379에 k6도 넣어야 합니다.** [measure.sh](measure.sh)가 k6 인스턴스에서 `redis-cli -h $REDIS_HOST`로
초기화(`--scan | xargs UNLINK`)와 검증(`ZCARD`·`INFO memory`)을 합니다. WAS만 열면 측정이 첫 줄에서 멈춥니다.

지금은 셋이 **보안 그룹 하나를 공유**하고 6379·5432를 자기 참조로 열어 두었습니다.
역할별로 나누는 것보다 규칙이 적고, 1단계는 어차피 인스턴스가 짧게 살기 때문입니다.
2단계에서 WAS가 N대가 되면 그때 역할별로 가릅니다.

```bash
SG=<security-group-id>
aws ec2 authorize-security-group-ingress --group-id $SG --ip-permissions \
  "IpProtocol=tcp,FromPort=8080,ToPort=8080,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=6379,ToPort=6379,UserIdGroupPairs=[{GroupId=$SG}]" \
  "IpProtocol=tcp,FromPort=5432,ToPort=5432,UserIdGroupPairs=[{GroupId=$SG}]"
aws ec2 authorize-security-group-ingress --group-id $SG \
  --protocol tcp --port 8080 --cidr <내 IP>/32     # 브라우저·actuator 확인용
```

> **8080을 내 IP에만 열면 k6가 WAS에 붙지 못합니다.** 자기 참조 규칙이 따로 필요합니다 —
> 위 표의 "k6 보안 그룹"이 그것인데, 실제로 이걸 빠뜨려 `curl`이 그대로 매달렸습니다.
> 증상이 타임아웃이라 앱 문제로 보이기 쉽습니다.

인증 없는 Redis를 `0.0.0.0/0`에 열면 스캐너가 붙는 데 몇 분 걸리지 않습니다.
실수하기 가장 쉬운 지점입니다. **기본 보안 그룹을 그대로 쓰지 마십시오** — 계정에 따라
`0.0.0.0/0`에 넓게 열려 있고, `run-instances`에서 `--security-group-ids`를 빠뜨리면 그게 붙습니다.
RDS도 만들 때 붙은 보안 그룹이 다른 프로젝트 것이면 옮겨야 합니다.

```bash
aws rds modify-db-instance --db-instance-identifier <id> \
    --vpc-security-group-ids $SG --no-publicly-accessible --apply-immediately
```

## 순서

### 1. 인스턴스마다 부트스트랩

커널 한도를 맞추지 않으면 **부하 도구가 먼저 터지고 앱을 측정했다고 착각**하게 됩니다.
로컬에서 `kern.ipc.somaxconn=1024`가 필요했던 것과 같은 부류입니다.

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
| k6 | **APT 저장소를 쓸 수 없습니다**(아래) — 릴리스 tarball + `sudo apt install -y redis-tools sysstat` (`redis-cli`만 씁니다 — `measure.sh`가 초기화와 검증에 필요로 합니다). 이 저장소도 클론해 둘 것 |

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
같은 코드·같은 부하인데 1런과 3런이 1.8배 차이 나는 워밍업 민감도 때문입니다.
JIT 구현이 다른 JVM을 섞으면 그 차이가 어디서 왔는지 갈라낼 수 없습니다.

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

프로젝트 파일을 base로 두고 AWS 델타 하나(`maxmemory`)를 얹은 뒤, 그 세 줄만 덧붙입니다.

```bash
# 원본은 한 번만 백업한다. 두 번째 실행에서 덮으면 이미 고친 것을 원본으로 남기게 된다.
[ -f /etc/redis/redis.conf.debian-orig ] || sudo cp /etc/redis/redis.conf /etc/redis/redis.conf.debian-orig

# 아래 redis.conf는 이 저장소의 infra/redis/redis.conf를 scp로 올려 둔 것이다.
sed 's/^maxmemory 512mb$/maxmemory 1gb/' redis.conf > /tmp/redis.conf.new
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
redis-cli CONFIG GET maxmemory     # 1073741824
redis-cli CONFIG GET save          # 빈 문자열 (RDB 꺼짐)
sudo ss -lntp | grep 6379          # 0.0.0.0:6379
```

### 2. DB 초기화와 환경 변수

환경마다 바뀌는 값은 넷뿐입니다 — Redis 좌표, DB 좌표·계정, DB 비밀번호.
`application.yml`에 `${REDIS_HOST:localhost}` 형태로 외부화 지점이 표시돼 있고,
기본값은 로컬 기준이라 그 파일만 봐도 무엇을 덮어야 하는지 알 수 있습니다.

**RDS는 애플리케이션 롤도 데이터베이스도 만들어 주지 않습니다.** 마스터 계정 하나만 줍니다.
로컬에는 없던 단계인데, `docker-compose.yml`의 `POSTGRES_USER`·`POSTGRES_DB`는 컨테이너 진입점이
롤과 데이터베이스를 함께 만들어 주기 때문입니다. Flyway는 **테이블**만 만듭니다.

[init-db.sh](init-db.sh)가 롤·데이터베이스 생성부터 `/etc/waiting/env` 작성까지 합니다.

```bash
RDS_HOST=<rds-endpoint> REDIS_HOST=<redis-private-ip> ./init-db.sh
```

> **`CREATE DATABASE ... OWNER ticketing`은 그냥은 막힙니다.**
> ```
> ERROR:  must be able to SET ROLE "ticketing"
> ```
> PG16부터 다른 롤을 소유자로 지정하려면 그 롤로 SET ROLE 할 수 있어야 하는데,
> RDS 마스터는 슈퍼유저가 아니라 `rds_superuser` 멤버라 자동으로 붙지 않습니다.
> `GRANT ticketing TO <마스터> WITH ADMIN OPTION`이 선행되어야 합니다 — 스크립트에 들어 있습니다.

손으로 채운다면 [env.example](env.example)을 복사합니다. `DB_USERNAME`에 넣는 것은
마스터가 아니라 애플리케이션 롤입니다 — 마스터를 넣으면 앱이 `rds_superuser` 권한으로 돕니다.

```bash
sudo install -m 640 -o root -g waiting env.example /etc/waiting/env
sudo vi /etc/waiting/env      # REDIS_HOST · DB_URL · DB_USERNAME · DB_PASSWORD
```

> **비밀번호를 명령행 인자로 넘기지 마십시오.** `--spring.datasource.password=...`는
> `ps aux`와 `/proc/*/cmdline`에 평문으로 그대로 보입니다. 같은 이유로 `EnvironmentFile`을 씁니다.

`queue.*` 튜닝값은 여기 두지 않습니다. `application.yml`이 유일한 진실 원천이고 화면 문구도 그 값을
읽습니다. 측정 중에 바꿀 때는 명령행 인자(`--queue.capacity=1000`)를 쓰며, 시크릿이 아니라
`ps`에 보여도 무방합니다.

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

> **시각 함정.** `QueueConfig.java`가 `ZoneId.of("Asia/Seoul")`을 하드코딩하므로 창 키
> (`waiting:holiday:20260728`)는 서버 TZ에 흔들리지 않습니다. 다만 **EC2 기본 TZ는 UTC**라
> 로그 타임스탬프가 9시간 어긋나 보입니다. `measure.sh`는 `TZ=Asia/Seoul date`로 명시해 안전합니다.

### 4. 측정

```bash
# k6 인스턴스에서
export WAS_HOST=10.0.1.20 REDIS_HOST=10.0.1.30

./measure.sh enqueue                 # 진입 처리량 (closed — 천장은 CPU)
USERS=1000000 VUS=1000 ./measure.sh enqueue
./measure.sh status                  # 조회 폴링
RATE=10000 DURATION=5s ./measure.sh burst   # 동시 도착 (open — 천장은 accept 큐)
```

**`enqueue`와 `burst`는 다른 천장을 잽니다. 같은 축에 두지 마십시오.**
`burst`를 쓸 때는 **WAS 인스턴스에서** 런 전후로 accept 큐 초과를 함께 세야 원인이 확정됩니다 —
클라이언트 쪽에는 connection reset으로만 보입니다.

```bash
nstat -az TcpExtListenOverflows TcpExtListenDrops TcpExtTCPReqQFullDrop
```

`TcpExtListenOverflows`만 오르고 `TcpExtTCPReqQFullDrop`이 0이면 SYN 큐가 아니라 accept 큐입니다.
그리고 k6의 `dropped_iterations`가 0이 아니면 그 런의 rate는 읽지 마십시오 —
서버가 아니라 **생성기의 한계**를 잰 것입니다.

`measure.sh`는 **초기화 → 워밍업 → 초기화 → 본 측정 → 지표 수집** 순으로 돕니다.
워밍업이 들어 있는 이유는 로컬에서 확인한 워밍업 민감도 때문입니다 —
같은 코드·같은 부하인데 1런과 3런이 **1.8배** 차이 났고, 수백 건짜리 워밍업은 아무 효과가 없습니다.
JIT 램프는 인스턴스를 바꿔도 사라지지 않습니다.

재기동 없이 여러 조건을 이어서 잴 때는 `WARMUP=0`으로 끄고, 대신 **조건 순서를 바꿔 가며 번갈아**
재야 합니다. 뒤 조건일수록 JVM이 더 데워져 유리해지기 때문입니다.

## 2단계로 넘어가기 전에

README의 [인프라 아키텍처](../../README.md#인프라-아키텍처)가 짚어 둔 두 가지에 각각 갈래가 있습니다.

- **세션 외부화**(`spring-session-data-redis`)를 붙이면 세션이 대기열과 **같은 Redis**에 들어갑니다.
  측정 대상에 다른 워크로드를 섞는 셈이라, 별도 인스턴스나 최소한 다른 DB 인덱스로 분리하는 편이
  측정 위생에 낫습니다.
- **스케줄러 단일화**는 리더 선출보다 플래그가 낫습니다. 조건부 빈으로 N대 중 1대만 켜면 코드 변경이
  거의 없고, **측정 도중 리더가 바뀌는 변수 자체가 사라집니다.** Redis 락은 그 변수를 다시 들여옵니다.

시크릿도 이때 옮길 지점이 옵니다. 지금은 인스턴스가 한 대이고 짧게 살아서 `/etc/waiting/env`로
충분하지만, WAS가 N대가 되면 같은 파일을 N번 복사하게 되고 비밀번호를 바꿀 때도 N번 고쳐야 합니다.
그때는 SSM Parameter Store에 SecureString으로 두고 부트스트랩에서 파일을 만들면 됩니다.

```bash
aws ssm get-parameter --name /waiting/db-password --with-decryption \
    --query Parameter.Value --output text
```

**앱 코드도 의존성도 그대로입니다.** `spring-cloud-aws`를 붙이면 기동 경로에 스타터가 하나 늘고
자동 구성이 따라오는데, 지금 필요한 것은 기동 전에 파일 한 줄을 만드는 일뿐입니다
(CLAUDE.md의 "새 의존성 전에 측정에 미칠 영향부터" 규칙과 맞습니다).
인스턴스 프로파일에 `ssm:GetParameter`와 해당 KMS 키의 `kms:Decrypt`만 주면 됩니다.

## 로컬과 달라지는 변수

**AWS에서 잰 값을 로컬에서 잰 값과 나란히 두면 안 됩니다.**
바뀌는 것이 한둘이 아닙니다.

| 변수 | 로컬 | AWS |
| --- | --- | --- |
| Redis 경로 | Docker 포트 포워딩 (왕복 0.31ms) | 같은 AZ의 EC2 |
| 부하 생성기 | 같은 머신, CPU 경합 | 별도 인스턴스 |
| CPU | Apple Silicon, k6·앱·Redis가 공유 | Graviton4, 역할별 전용 |
| OS | macOS | Ubuntu 26.04 LTS (arm64) |
| Redis 버전 | 8.8.0 (Docker 이미지) | 8.0.5 (apt) |
| JVM | Corretto 21.0.2 | Corretto 21.0.12 |
| Redis CPU 상한 | 컨테이너 `cpus: "1.0"` | 인스턴스 2 vCPU (단일 스레드라 실제로는 1개) |
| `maxmemory` | 512mb | 1gb |
| DB | Docker PostgreSQL 18.4 | RDS PostgreSQL 18.3 |
| 커널 백로그 | macOS `kern.ipc.somaxconn` | Linux `net.core.somaxconn` |

Redis와 JVM은 **같은 메이저·같은 벤더 안의 차이**라 그대로 갑니다. 다만 워밍업 하나에 1.8배가
갈리는 프로젝트라 기록은 남깁니다 — 나중에 설명되지 않는 차이가 나오면 여기부터 봅니다.

## 비용

위 4대를 켜 두면 대략 **시간당 $0.5 안팎**입니다(리전마다 다르므로 확인이 필요합니다).
측정은 몰아서 하는 작업이라 **끝나면 stop**하면 EBS 요금만 남습니다.

스팟은 쓰지 않습니다. 런 도중에 회수되면 그 런이 통째로 날아가고, 워밍업 60만 건부터 다시입니다.
