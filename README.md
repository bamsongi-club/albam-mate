# 알밤메이트

> 원하는 게임과 모임 조건을 확인하고 함께할 사람을 찾아, 실제 보드게임 플레이까지 이어지도록 돕는 모임 매칭 서비스입니다.

[제품 목표](docs/PRD.md) · [P0 명세](docs/P0-spec.md) · [API 계약](docs/API.md)

> **현재 단계**
>
> P0의 제품·API·데이터·기술 계약을 정리 중입니다. 저장소에는 Spring Boot 애플리케이션의 기본 실행 구조가 있으며, 인증·게임·방·참가 기능과 배포·성능 검증은 아직 구현 전입니다.

## 해결하려는 문제

보드게임을 하고 싶어도 함께할 사람, 플레이할 게임, 시간과 장소, 규칙을 설명할 사람을 한 번에 맞추기 어렵습니다. 모집 정보가 여러 곳에 흩어지면 초보·라이트 사용자는 자신에게 맞는 모임인지 판단하기도 어렵습니다.

알밤메이트는 사용자가 게임 또는 사람을 기준으로 모임을 찾고, 참가에 필요한 조건을 확인한 뒤 실제 모임에 참여하는 과정을 연결합니다. P0에서는 홍대에서 열리는 오프라인 보드게임 모임이 안전하게 성립하는지를 먼저 검증합니다.

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

범위와 공통 규칙은 [P0 명세](docs/P0-spec.md), 기능별 완료 조건은 [P0 기능 문서](docs/P0-spec.md#관련-문서)를 따릅니다.

## 현재 개발 상태

문서가 존재하거나 기술 결정이 승인됐다는 사실을 구현·검증 완료와 같은 의미로 사용하지 않습니다.

| 영역 | 문서화 | 구현 | 검증 |
| --- | --- | --- | --- |
| 백엔드 기반 | 빌드 설정과 기술 기준 있음 | Spring Boot 진입점 구성 | 기본 컨텍스트 테스트 있음 |
| 인증·프로필 | [기능 명세](docs/p0/auth-profile.md) 있음 | 구현 전 | 검증 전 |
| 게임 카탈로그 | [기능 명세](docs/p0/game-catalog.md) 있음 | 구현 전 | 검증 전 |
| 방 | [기능 명세](docs/p0/room.md) 있음 | 구현 전 | 검증 전 |
| 참가·내 모임 | [기능 명세](docs/p0/participation.md) 있음 | 구현 전 | 검증 전 |

기능 구현 PR에서는 해당 행의 구현 상태와 테스트·측정 근거를 함께 갱신합니다.

## 기술 기준과 선택 근거

| 문제 | 선택 | 이유와 현재 근거 |
| --- | --- | --- |
| 백엔드 기준선 | Java 21, Spring Boot 4.1 | 현재 빌드와 지원 범위를 맞추고 가까운 시기의 기준선 재변경을 줄입니다. 빌드 설정과 기본 테스트에 반영됐습니다. [ADR-0001](docs/adr/platform/0001-java-21-spring-boot-4-baseline.md) |
| 업무 데이터 정합성 | PostgreSQL, Spring Data JPA | 관계와 트랜잭션, 데이터베이스 제약을 함께 사용합니다. 의존성만 구성됐으며 실제 PostgreSQL 통합 검증은 남아 있습니다. [ADR-0002](docs/adr/platform/0002-postgresql-primary-database.md) |
| 코드 구조 | 도메인 중심 모듈러 모놀리스 | 하나의 배포·트랜잭션 단위를 유지하면서 도메인별 책임과 의존 경계를 드러냅니다. 도메인 코드와 구조 검증 테스트는 아직 구현 전입니다. [ADR-0007](docs/adr/platform/0007-domain-centered-modular-monolith.md) |
| P0 인증 | 서버 세션, Spring Security | 현재 범위에 필요하지 않은 JWT 만료·갱신·폐기 정책을 먼저 만들지 않고 서버가 보호 경로를 통제합니다. 구현과 인증 테스트는 남아 있습니다. [ADR-0003](docs/adr/auth/0003-p0-server-session-spring-security.md) |
| 방 참가 동시성 | 낙관 락과 제한된 재시도 | 충돌이 드물다는 현재 가정 아래 평상시 잠금 대기를 피하고, 충돌 비용은 PostgreSQL 기반 테스트로 확인합니다. 구현과 측정은 남아 있습니다. [ADR-0005](docs/adr/participation/0005-room-participation-optimistic-locking.md) |

## 로컬에서 확인하기

애플리케이션과 테스트에는 Java 21이 필요합니다. 별도의 Gradle 설치 대신 저장소의 Wrapper를 사용합니다.

Windows PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat spotlessCheck
```

macOS·Linux:

```sh
./gradlew test
./gradlew spotlessCheck
```

현재 저장소에는 PostgreSQL 연결값이 포함되어 있지 않습니다. `bootRun` 전에 데이터소스 설정이 필요하며, 반복 명령과 환경 조건은 [프로젝트 명령](docs/COMMANDS.md)에서 확인할 수 있습니다.

## 문서 찾기

- 제품의 전체 목표와 후속 후보: [PRD](docs/PRD.md)
- P0 범위와 핵심 흐름: [P0 명세](docs/P0-spec.md)
- 개발 작업의 시작점: [AGENTS.md](AGENTS.md)
- 요청·응답과 오류 계약: [API 명세](docs/API.md)
- 테이블과 데이터 제약: [ERD](docs/ERD.md)
- 기술 선택과 트레이드오프: [ADR](docs/adr/README.md)
- 코드 구조와 구현 규칙: [컨벤션](docs/CONVENTIONS.md)
- 실행·테스트·포맷 명령: [프로젝트 명령](docs/COMMANDS.md)

구현 작업은 [AGENTS.md](AGENTS.md)의 라우팅에 따라 `docs/p0/`의 기능 ID에서 시작합니다. 상세 계약을 README에 복제하지 않고 각 정본 문서를 연결합니다.
