# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

---

# 프로젝트 특화 지침 — Holiday Train Ticketing System

명절 승차권 예매를 가정한 대규모 예약 시스템 **학습** 프로젝트. 목적은 기능 완성이 아니라
**대규모 트래픽에서 병목이 어디서 어떤 모양으로 나타나는지 측정하고, 변경 전후를 비교**하는 것.
설계 결정과 트레이드오프의 진실 원천은 항상 `README.md`와 `waiting-server/src/main/resources/application.yml`의
주석이다 — 코드를 고치기 전에 먼저 읽는다.

## 스택 · 구조
- Spring Boot 4.1 · Java 21 **가상 스레드** · Redis 8(Docker, 1코어) · PostgreSQL 18(Docker, 호스트 포트 **5433**) · Flyway · Thymeleaf · k6.
- 애플리케이션은 `waiting-server/`(Gradle), 패키지 `com.urban6.waiting` — `auth` / `queue` / `demo` / `member` / `presentation`.
- 인프라(Redis·Postgres)는 `infra/docker-compose.yml`, 부하 스크립트는 `k6/`.

## 명령어
```bash
# 인프라
docker compose -f infra/docker-compose.yml up -d

# 빌드 · 테스트 (waiting-server 안에서)
cd waiting-server && ./gradlew bootJar
./gradlew test          # Testcontainers가 테스트용 Postgres를 띄운다

# 측정용 실행 — IDE Run 버튼이 아니라 반드시 jar + loadtest 프로파일
java -jar waiting-server/build/libs/waiting-0.0.1.jar --spring.profiles.active=loadtest
```

## 반드시 지킬 규칙 (어기면 조용히 깨진다)
- **Redis가 진실 원천이다.** 정원·만료·순번은 모두 `active`/`waiting` ZSet에서 나온다. 만료 판정용 인터셉터나 항목별 TTL 키를 새로 만들지 말고, 기존 `active` ZSet의 score 하나를 진실 원천으로 유지한다.
- **동시성이 걸린 다중 Redis 명령은 Lua로 원자적으로 묶는다.** 개별 명령을 나열하면 그 사이에 다른 요청이 끼어들어 "앞 + 뒤 + 1 = 전체"가 어긋난다. 새 원자 연산은 `src/main/resources/redis/*.lua`에 추가한다(기존: `enqueue`·`status`·`promote`·`restamp`).
- **인증이 필요한 새 화면·엔드포인트를 추가하면 반드시 `auth/WebConfig`의 게이트 화이트리스트에 등록한다.** 게이트(`AdmissionGuard`/`LoginGuard`)는 `/**`가 아니라 명시적 경로만 탄다 — 빠뜨리면 제한 시간이 지나도 그 경로는 그대로 열린다.
- **설정 기본값을 코드에 두지 않는다.** `application.yml`의 `queue` 블록이 유일한 진실 원천이라 블록이 없으면 기동에 실패한다. 튜닝값(`capacity`·`max-batch`·`reservation-ttl` 등)을 바꿀 곳은 그곳 하나뿐이고, 화면 문구도 이 값을 그대로 읽는다.
- **부하를 측정할 땐 요청당 로그를 켜지 않는다.** `System.out` 락이 가상 스레드 pinning 지점이라, 켜면 대기열이 아니라 로거를 측정한다. `loadtest` 프로파일이 이걸 끄고 있다. 절대 수치는 같은 머신·같은 세션 안에서만 비교 유효(README「측정」참고).
- **`queue.demo.*`(시연용 시드)는 인증이 없다.** 외부에 노출되는 환경에서는 `queue.demo.enabled: false` 필수. `loadtest`에서는 이미 꺼져 있다.

## 알아 둘 맥락
- **Spring Security 미도입** — `spring-security-crypto`로 BCrypt만 쓴다. 대기열 경로에 요청당 비용을 얹지 않기 위한 결정이므로, 새 의존성·필터를 넣기 전에 측정에 미칠 영향을 먼저 따진다. CSRF 토큰이 없어 `SameSite=Lax`로 대신하고 있다(완전한 대체 아님).
- **미완성 항목**: 대기열 이탈 처리(유령 항목), 회원가입, 좌석·결제, WAS 다중화. 좌석·결제 엔드포인트를 만들 땐 위의 화이트리스트 규칙이 특히 중요하다.
- Flyway 마이그레이션은 `db/migration`에 두고 기동 시점에 적용된다(`docker-entrypoint-initdb.d` 아님). 스키마를 고쳤는데 반영되지 않으면 이 차이를 먼저 의심한다.
