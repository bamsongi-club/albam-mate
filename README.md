# 알밤메이트

> 원하는 게임과 모임 조건을 확인하고 함께할 사람을 찾아, 실제 보드게임 플레이까지 이어지도록 돕는 모임 매칭 서비스입니다.

[제품 목표](docs/PRD.md) · [P0 완료 명세](docs/archive/p0/P0-spec.md) · [P1 명세](docs/P1-spec.md) · [API 계약](docs/API.md) · [아키텍처](docs/ARCHITECTURE.md)

P0 백엔드 17개 API와 프론트엔드 연동을 완료했고, P1 2차 MVP는 기능별 계약·구현·검증을 진행 중입니다. 운영 배포는 아직 시작하지 않았습니다. 상세 상태는 [현재 개발 상태](#현재-개발-상태)를 따릅니다.

## 해결하려는 문제

보드게임을 하고 싶어도 함께할 사람, 플레이할 게임, 시간과 장소, 규칙을 설명할 사람을 한 번에 맞추기 어렵습니다. 모집 정보가 여러 곳에 흩어지면 초보·라이트 사용자는 자신에게 맞는 모임인지 판단하기도 어렵습니다.

알밤메이트는 게임, 사람 기준 탐색부터 조건 확인과 실제 참여까지 연결합니다. P0에서는 홍대 오프라인 보드게임 모임의 안전한 성립을 먼저 검증합니다.

## P0 핵심 경험

### 게임부터 찾기

~~~text
게임 목록·검색 → 게임 상세 → 해당 게임의 방 탐색 → 방 상세 → 로그인 → 참가 → 내 모임 확인
~~~

### 사람부터 만나기

~~~text
사람 중심 방 탐색 → 방 상세 → 로그인 → 참가 → 내 모임 확인
~~~

### 방 만들기

~~~text
로그인 → 게임 중심·사람 중심 선택 → 모임 정보 입력 → 방 생성 → 참가자 확인 → 모임 종료
~~~

P0는 홍대 오프라인 모임만 다루며 운영 제재·결제·알림·대규모 동시 요청 성능 목표는 제외합니다. 범위·공통 규칙은 [P0 명세](docs/archive/p0/P0-spec.md), 기능별 완료 조건은 [P0 기능 문서](docs/archive/p0/P0-spec.md#관련-문서)를 따릅니다.

## 현재 개발 상태

문서가 존재하거나 기술 결정이 승인됐다는 사실을 구현·검증 완료와 같은 의미로 사용하지 않습니다.

기능 ID와 API 개수는 [P0 API 인벤토리](docs/archive/p0/P0-spec.md#기능별-문서와-api-목록)의 17개 API를 기준으로 셉니다.

| 영역 | 문서화 | 구현 | 검증 |
| --- | --- | --- | --- |
| 백엔드 기반 | [기반 작업](docs/archive/p0/foundation.md) 있음 | `FND-01`~`FND-08` 범위의 도메인별 패키지, Flyway `V1`~`V3`, 세션 보안, UTC 시각 기준, 로컬 모임 60개 초기 데이터 | H2 `test`와 PostgreSQL 18 `postgresTest`, 분기 커버리지 게이트를 CI에서 실행 |
| 인증·프로필 | [기능 명세](docs/archive/p0/auth-profile.md) 있음 | `AUTH-01`~`AUTH-04`의 6개 API | 단위·HTTP 통합 테스트와 PostgreSQL 가입 경합 테스트 |
| 게임 카탈로그 | [기능 명세](docs/archive/p0/game-catalog.md) 있음 | `GAME-01`·`GAME-02`의 2개 API, 예정 모임 필터와 2,000건 검수·적재 도구 | 목록·검색·예정 모임 필터·상세 테스트와 PostgreSQL 재적재·롤백 테스트 |
| 방 | [기능 명세](docs/archive/p0/room.md) 있음 | `ROOM-01`~`ROOM-05`의 6개 API와 전체 공개 방 필터, 상태 보정·스케줄러 | 목록·필터, 상태 경계·보정, 취소·종료와 권한 테스트 |
| 참가·내 모임 | [기능 명세](docs/archive/p0/participation.md) 있음 | `PART-01`~`PART-03`의 3개 API | PostgreSQL 낙관 락 동시성 테스트 |
| 프론트엔드 | [프론트엔드 README](frontend/README.md) 있음 | React 화면과 세션 쿠키·CSRF를 포함한 P0 API 연동, 게임·방 조건 필터와 웹 알림함 | Vitest API·알림 회귀 테스트와 Vite 운영 빌드를 CI에서 실행 |
| 운영 배포 | [운영 가이드](docs/guides/AWS_P0_INFRASTRUCTURE.md)와 [ADR-0021](docs/adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md) 있음 | 백엔드·웹 이미지, 로컬·운영 Compose, production 프로파일과 롤백 명령 구현. 실제 AWS 배포는 미수행 | production 설정 자동 테스트와 Docker 배포 계약 검증기 있음. 공개 HTTPS·RDS 복구·경보 수신 검증은 남음 |
| P1 2차 MVP | [P1 공통 명세](docs/P1-spec.md)와 [기능별 상태 정본](docs/p1/README.md#기능별-현재-상태) | [기능별 상태 정본](docs/p1/README.md#기능별-현재-상태)의 `생산 코드` 열 | 같은 표의 `자동 검증`·`운영 배포·실측` 열 |

P0 기능 행은 `v0.1.0` 완료 시점 기록을 요약하고, 프론트엔드·운영 배포·P1 행은 2026-08-04 기준입니다. P1 기능별 계약·구현·자동 검증·운영 상태는 [P1 기능별 상태 정본](docs/p1/README.md#기능별-현재-상태)을 따릅니다.

## 팀 — 밤송이클럽

| 팀원 | 담당 영역 |
| --- | --- |
| [@vanilalatte03](https://github.com/vanilalatte03) | 인증·사용자, AI 협업 기반 |
| [@beyejin](https://github.com/beyejin) | 스키마, 게임 카탈로그, 방 탐색, 프론트엔드 |
| [@gone09-sketch](https://github.com/gone09-sketch) | 방 생명주기·상세 |
| [@silverThunder09](https://github.com/silverThunder09) | 참가·정원과 동시성 검증 |

통합 테스트는 네 명이 함께 진행합니다.

## 기술 기준과 선택 근거

P0의 사용자 흐름과 저장·보안 계약에 직접 영향을 주는 결정만 싣습니다. 나머지 결정과 전체 목록, 각 결정의 검증 상태는 [ADR 인덱스](docs/adr/README.md)에서 확인합니다.

| 문제 | 선택 | 이유와 현재 근거 |
| --- | --- | --- |
| 백엔드 기준선 | Java 21, Spring Boot 4.1 | 현재 빌드와 지원 범위를 맞추고 가까운 시기의 기준선 재변경을 줄입니다. 빌드 설정과 기본 테스트에 반영됐습니다. [ADR-0001](docs/adr/platform/0001-java-21-spring-boot-4-baseline.md) |
| 업무 데이터 정합성 | PostgreSQL, Spring Data JPA | 관계와 트랜잭션, 데이터베이스 제약을 함께 사용합니다. Flyway 마이그레이션과 PostgreSQL 18 통합 테스트로 확인했습니다. [ADR-0002](docs/adr/platform/0002-postgresql-primary-database.md) |
| 코드 구조 | 도메인 중심 모듈러 모놀리스 | 하나의 배포·트랜잭션 단위를 유지하면서 도메인별 책임과 의존 경계를 드러냅니다. [아키텍처](docs/ARCHITECTURE.md)를 따르며, 선택 근거는 [ADR-0007](docs/adr/platform/0007-domain-centered-modular-monolith.md)에 기록합니다. |
| P0 인증 | 서버 세션, Spring Security | 현재 범위에 필요하지 않은 JWT 만료·갱신·폐기 정책을 먼저 만들지 않고 서버가 보호 경로를 통제합니다. 세션 쿠키·CSRF·로그아웃 계약을 HTTP 통합 테스트로 고정했습니다. [ADR-0003](docs/adr/auth/0003-p0-server-session-spring-security.md) |
| API 인가 경계 | 엔드포인트 정책 등록부 | 인증·CSRF 정책을 한 목록에 모으고 Spring MVC 매핑과 자동 대조해 등록 누락을 CI에서 막습니다. [ADR-0020](docs/adr/auth/0020-api-endpoint-authorization-policy-registry.md) |
| 방 참가 동시성 | 낙관 락과 제한된 재시도 | 충돌이 드물다는 현재 가정 아래 평상시 잠금 대기를 피합니다. 정원 초과·중복 참가 방지와 재시도 상한을 PostgreSQL 동시성 테스트로 확인했습니다. [ADR-0005](docs/adr/participation/0005-room-participation-optimistic-locking.md) |
| 게임 목록 데이터 | BGG 기준 스냅샷과 팀 수집 자료 | 외부 식별자를 보존하면서 서비스 표시 필드의 출처를 추적하고, 검증을 통과한 데이터셋만 하나의 트랜잭션으로 반영합니다. [ADR-0015](docs/adr/game/0015-bgg-baseline-team-collected-game-list.md) |
| P0 운영 배포 | EC2 `t4g.small`과 private RDS PostgreSQL | 애플리케이션과 운영 데이터를 분리하고, RDS는 EC2 애플리케이션만 접근하게 합니다. 운영 Docker 이미지·Compose·production 프로파일은 구현했지만, 실제 HTTPS·전용 DB 사용자·재시작·RDS 복구·경보 수신 검증이 남아 있습니다. [ADR-0021](docs/adr/platform/0021-p0-aws-ec2-rds-deployment-baseline.md) |

## 로컬에서 확인하기

애플리케이션과 백엔드 테스트에는 Java 21, 프론트엔드와 문서 링크 검사에는 Node.js 20 이상이 필요합니다. 별도의 Gradle 설치 대신 저장소의 Wrapper를 사용합니다.

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat conventionCheck
```

macOS·Linux:

```sh
./gradlew test
./gradlew conventionCheck
```

`test`는 H2 인메모리 데이터베이스를 사용하므로 Docker 없이 실행됩니다. Testcontainers로 PostgreSQL 18.4를 띄우는 `postgresTest`는 Docker가 필요하고, `bootRun`은 PostgreSQL 연결이 필요하며 로컬 표준 절차는 Docker Compose를 사용합니다.

```sh
./gradlew postgresTest
```

P0 흐름을 화면으로 확인할 때는 로컬 PostgreSQL을 띄운 뒤 백엔드와 프론트엔드를 각각 실행합니다.

Windows PowerShell:

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=local'
```

```powershell
Set-Location frontend
npm.cmd run dev
```

macOS·Linux:

```sh
./gradlew bootRun --args='--spring.profiles.active=local'
```

```sh
cd frontend && npm run dev
```

백엔드는 `http://localhost:8080`, 프론트엔드는 `http://localhost:5173`에서 열리며 `/api`는 기본적으로 로컬 백엔드에 프록시되어 같은 PostgreSQL 데이터를 사용합니다.

저장소에는 데이터소스 연결값을 두지 않습니다. `.env` 준비, Compose 기동과 운영체제별 실행 절차는 [로컬 개발 환경 실행](docs/guides/LOCAL_DEVELOPMENT.md), 짧은 반복 명령은 [프로젝트 명령](docs/COMMANDS.md)에서 확인할 수 있습니다.

## 문서 찾기

- 제품의 전체 목표와 후속 후보: [PRD](docs/PRD.md)
- P0 범위와 핵심 흐름: [P0 명세](docs/archive/p0/P0-spec.md)
- P1 2차 MVP 범위와 기능별 기준: [P1 명세](docs/P1-spec.md), [P1 기능 문서](docs/p1/README.md)
- 개발 작업의 시작점: [AGENTS.md](AGENTS.md)
- 백엔드 구조·모듈 책임과 의존 흐름: [아키텍처](docs/ARCHITECTURE.md)
- 요청·응답과 오류 계약: [API 명세](docs/API.md)
- 테이블과 데이터 제약: [ERD](docs/ERD.md)
- 기술 선택과 트레이드오프, 결정별 검증 상태: [ADR](docs/adr/README.md)
- 운영·설정·데이터 적재와 문제 해결 절차: [프로젝트 가이드](docs/guides/README.md)
- 코드 작성·구현 규칙: [컨벤션](docs/CONVENTIONS.md)
- 실행·테스트·포맷 명령: [프로젝트 명령](docs/COMMANDS.md)
- 프론트엔드 화면과 실행: [프론트엔드 README](frontend/README.md)

P0 구현 기록은 [문서 아카이브](docs/archive/README.md)에 동결했습니다. P1 문서는 계획·구현 기준이며, 실제 제공 상태는 현재 코드·API·ERD와 [현재 개발 상태](#현재-개발-상태)를 기준으로 판단합니다.
