#!/usr/bin/env python3
"""1,000번의 enqueue 호출이 실제로 몇 명을 저장하는지 확인하는 학습 실험.

실행: python3 k6/check-benchmark-members.py
기록: python3 k6/check-benchmark-members.py --output /tmp/benchmark-members.json

redis-server, redis-cli, redis-benchmark가 PATH에 있어야 한다.
TCP 포트 없이 임시 Unix 소켓으로 Redis를 띄우고 종료한다. 기존 Redis에는 접속하지 않는다.
처리량 비교용 실험이 아니다. 애플리케이션의 enqueue.lua를 그대로 사용한다.
"""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from datetime import datetime, timezone


REQUESTS = 1000
CLIENTS = 10
ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=30).strip()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def experiment(socket):
    cli = ["redis-cli", "-s", str(socket), "--raw"]
    lua = (ROOT / "server/src/main/resources/redis/enqueue.lua").read_bytes()
    sha = run(*cli, "SCRIPT", "LOAD", lua.decode())
    require(sha == hashlib.sha1(lua).hexdigest(), "Lua 스크립트 로드 실패")
    deadline = str(int(time.time() * 1000) + 600_000)
    results = []

    for label, keyspace in [("without-r", None), ("r-1000", 1000), ("r-1000000", 1_000_000)]:
        # 케이스마다 별도 키를 사용하므로 앞선 데이터가 섞이지 않는다.
        waiting, seq, poll = [f"experiment:{label}:{kind}" for kind in ("waiting", "seq", "poll")]
        command = ["redis-benchmark", "-s", str(socket), "-n", str(REQUESTS),
                   "-c", str(CLIENTS), "-P", "1", "-q"]
        if keyspace is not None:
            command += ["-r", str(keyspace)]
        command += ["evalsha", sha, "3", waiting, seq, poll,
                    "m:__rand_int__", deadline, deadline, deadline]
        raw_output = run(*command)

        issued = int(run(*cli, "GET", seq))
        waiting_count = int(run(*cli, "ZCARD", waiting))
        poll_count = int(run(*cli, "ZCARD", poll))
        members = run(*cli, "ZRANGE", waiting, "0", "-1").splitlines()
        poll_members = run(*cli, "ZRANGE", poll, "0", "-1").splitlines()
        require(issued == REQUESTS, f"{label}: seq가 요청 수와 다릅니다")
        require(waiting_count == poll_count, f"{label}: waiting/poll 개수가 다릅니다")
        require(set(members) == set(poll_members), f"{label}: waiting/poll 멤버가 다릅니다")
        if keyspace is None:
            require(members == ["m:__rand_int__"], "-r 없이 실행한 결과가 예상과 다릅니다")
            require(float(run(*cli, "ZSCORE", waiting, members[0])) == REQUESTS,
                    "같은 멤버의 score가 마지막 seq로 갱신되지 않았습니다")
        else:
            require(0 < waiting_count <= min(REQUESTS, keyspace), "멤버 수 범위 오류")
            require(all(m.startswith("m:") and m[2:].isdigit() for m in members),
                    "난수 치환이 이루어지지 않았습니다")

        results.append({
            "case": label,
            "random_keyspace": keyspace,
            "requests": REQUESTS,
            "seq": issued,
            "waiting_count": waiting_count,
            "poll_count": poll_count,
            "repeated_member_updates": issued - waiting_count,
            "expected_distinct_if_uniform": round(
                keyspace * (1 - (1 - 1 / keyspace) ** REQUESTS), 3) if keyspace else 1,
            "sample_members_with_scores": run(*cli, "ZRANGE", waiting, "0", "2", "WITHSCORES").splitlines(),
            "command": command,
            "raw_benchmark_output": raw_output,
        })
        print(f"{label:14} requests={REQUESTS} seq={issued} "
              f"waiting={waiting_count} poll={poll_count} updates={issued - waiting_count}")
    return {"recorded_at_utc": datetime.now(timezone.utc).isoformat(),
            "redis_server": run("redis-server", "--version"),
            "redis_benchmark": run("redis-benchmark", "--version"),
            "lua_sha1": sha, "clients": CLIENTS, "pipeline": 1,
            "scope": "member cardinality verification, not a throughput comparison",
            "cases": results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="실측 결과를 JSON으로 저장할 경로")
    args = parser.parse_args()
    for command in ("redis-server", "redis-cli", "redis-benchmark"):
        require(shutil.which(command), f"PATH에 {command}가 없습니다")

    # 짧은 경로로 Unix 소켓의 경로 길이 제한을 피한다.
    with tempfile.TemporaryDirectory(prefix="queue-members-", dir="/tmp") as directory:
        socket = Path(directory) / "redis.sock"
        with (Path(directory) / "redis.log").open("w+") as log:
            server = subprocess.Popen(
                ["redis-server", "--port", "0", "--unixsocket", str(socket),
                 "--unixsocketperm", "700", "--save", "", "--appendonly", "no", "--dir", directory],
                stdout=log, stderr=subprocess.STDOUT)
            try:
                for _ in range(100):
                    if server.poll() is not None:
                        log.seek(0)
                        raise RuntimeError(log.read())
                    if socket.exists():
                        break
                    time.sleep(0.05)
                require(socket.exists(), "임시 Redis 시작 시간 초과")
                result = experiment(socket)
            finally:
                if server.poll() is None:
                    server.terminate()
                try:
                    server.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait(timeout=5)
    if args.output:
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
        print(f"실측 기록: {args.output}")


if __name__ == "__main__":
    main()
