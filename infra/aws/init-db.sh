#!/usr/bin/env bash
#
# RDS에 애플리케이션 롤·데이터베이스를 만들고 /etc/waiting/env를 채운다.
#
#   RDS_HOST=<rds-endpoint> REDIS_HOST=<redis-private-ip> ./init-db.sh
#
# WAS 인스턴스에서 돌린다. sudo 없이 실행하고, 필요한 곳에서만 sudo를 부른다.
# 먼저 bootstrap.sh was로 waiting 계정과 /etc/waiting이 만들어져 있어야 한다.
#
# 로컬에는 없는 단계다. docker-compose.yml의 POSTGRES_USER/POSTGRES_DB는 컨테이너
# 진입점이 롤과 데이터베이스를 함께 만들어 주지만, RDS는 마스터 계정만 준다.
# Flyway는 테이블만 만들지 롤도 데이터베이스도 만들지 않는다.

set -euo pipefail

: "${RDS_HOST:?RDS_HOST를 넘길 것 (콘솔의 '엔드포인트 및 포트')}"
: "${REDIS_HOST:?REDIS_HOST를 넘길 것 (Redis EC2의 프라이빗 IP)}"

MASTER_USER=${MASTER_USER:-postgres}

# 비밀번호를 인자로 받지 않는다. ps aux와 /proc/*/cmdline에 평문으로 보인다 —
# waiting.service가 EnvironmentFile을 쓰는 것과 같은 이유다.
read -rsp "RDS 마스터($MASTER_USER) 비밀번호: " MASTER; echo
[ -n "$MASTER" ] || { echo "비어 있다" >&2; exit 1; }

# 영숫자만 쓴다. psql 리터럴과 systemd EnvironmentFile 양쪽에서 이스케이프가 필요 없다 —
# EnvironmentFile은 따옴표 처리 규칙이 셸과 미묘하게 달라서 특수문자를 피하는 편이 안전하다.
TICKET_PW=$(openssl rand -hex 24)

export PGPASSWORD="$MASTER"
PSQL="psql -h $RDS_HOST -U $MASTER_USER -d postgres -v ON_ERROR_STOP=1 -q"

echo "== 롤 =="
$PSQL <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ticketing') THEN
    CREATE ROLE ticketing LOGIN PASSWORD '$TICKET_PW';
  ELSE
    ALTER ROLE ticketing LOGIN PASSWORD '$TICKET_PW';
  END IF;

  -- PG16부터 CREATE DATABASE ... OWNER 는 그 롤로 SET ROLE 할 수 있어야 통과한다.
  -- RDS 마스터는 슈퍼유저가 아니라 rds_superuser 멤버라 자동으로 붙지 않는다.
  -- 이 줄이 없으면 다음 단계가 must be able to SET ROLE "ticketing" 으로 막힌다.
  EXECUTE format('GRANT ticketing TO %I WITH ADMIN OPTION', current_user);
END
\$\$;
SQL

# CREATE DATABASE는 트랜잭션 블록 안에서 못 돈다. 위 DO 블록과 합칠 수 없는 이유다.
echo "== 데이터베이스 =="
if $PSQL -tAc "SELECT 1 FROM pg_database WHERE datname='ticketing'" | grep -q 1; then
    echo "-- 이미 있다"
else
    $PSQL -c "CREATE DATABASE ticketing OWNER ticketing"
    echo "-- 생성"
fi
unset PGPASSWORD

echo "== 접속 확인 =="
PGPASSWORD="$TICKET_PW" psql -h "$RDS_HOST" -U ticketing -d ticketing \
    -tAc "SELECT current_user || ' @ ' || current_database()"

# 권한은 640 root:waiting이다. waiting은 읽을 수 있고 쓸 수는 없어야 한다 —
# bootstrap.sh가 /etc/waiting을 0750으로 미리 만들어 두는 것과 같은 방향이다.
echo "== /etc/waiting/env =="
umask 077
TMP=$(mktemp)
cat > "$TMP" <<EOF
# init-db.sh가 생성함. 환경마다 바뀌는 값은 이 넷뿐이다(env.example 참고).
REDIS_HOST=$REDIS_HOST
DB_URL=jdbc:postgresql://$RDS_HOST:5432/ticketing
DB_USERNAME=ticketing
DB_PASSWORD=$TICKET_PW
EOF
sudo install -m 640 -o root -g waiting "$TMP" /etc/waiting/env
rm -f "$TMP"
sudo ls -l /etc/waiting/env

echo
echo "다음: sudo systemctl restart waiting && journalctl -u waiting -f"
