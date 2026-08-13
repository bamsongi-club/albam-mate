# 알밤메이트

> 원하는 게임과 모임 조건을 확인하고 함께할 사람을 찾아, 실제 보드게임 플레이까지 이어지도록 돕는 모임 매칭 서비스입니다.

[현재 제공 상태](#현재-제공-상태) · [대표 검증과 실측](#대표-검증과-실측) · [10분 안에 첫 Green](#10분-안에-첫-green) · [아키텍처](docs/ARCHITECTURE.md) · [문서 지도](#문서-지도)

P0 1차 MVP의 백엔드 17개 API와 프론트엔드 연동을 완료했고, P1 2차 MVP는 필수 기능의 계약·구현·자동 검증을 마친 뒤 운영 제한사항과 함께 [아카이브](docs/archive/p1/README.md)했습니다. 현재 개발 단계는 P2 3차 MVP이며, 기능별 계약·구현·배포·실측 상태는 [P2 기능 상태 정본](docs/p2/README.md#기능별-현재-상태)에서 관리합니다.

## 해결하려는 문제

보드게임을 하고 싶어도 함께할 사람, 플레이할 게임, 시간과 장소, 규칙을 설명할 사람을 한 번에 맞추기 어렵습니다. 모집 정보가 여러 곳에 흩어지면 초보·라이트 사용자는 자신에게 맞는 모임인지 판단하기도 어렵습니다.

알밤메이트는 게임이나 사람을 기준으로 모임을 찾고, 조건을 확인한 뒤 실제 참여까지 이어지는 한 흐름을 제공합니다. P0에서는 홍대 오프라인 보드게임 모임이 안전하게 성립하는 경험을 먼저 검증했습니다.

## 핵심 경험

| 시작점 | 사용자 흐름 |
| --- | --- |
| 게임부터 찾기 | 게임 탐색 → 게임 상세 → 해당 게임의 방 → 참가 → 내 모임 |
| 사람부터 만나기 | 사람 중심 방 탐색 → 방 상세 → 참가 → 내 모임 |
| 방 만들기 | 게임 중심·사람 중심 선택 → 모임 정보 입력 → 방 생성 → 참가자 확인 → 종료 |

P0는 홍대 오프라인 모임을 대상으로 하며 완료 시점의 범위와 규칙은 [P0 아카이브](docs/archive/p0/P0-spec.md)에 보존합니다. P1 종료 상태는 [P1 아카이브](docs/archive/p1/README.md)에 보존하고, 현재 개발은 [P2 3차 MVP](docs/P2-spec.md)를 기준으로 진행합니다.

## 현재 제공 상태

문서가 존재하거나 기술 결정이 승인됐다는 사실을 구현·검증·배포 완료와 같은 의미로 사용하지 않습니다.

| 구분 | 현재 상태 | 상세 근거 |
| --- | --- | --- |
| P0 1차 MVP | `v0.1.0` 범위의 백엔드 17개 API와 React 연동 완료 | [P0 완료 명세](docs/archive/p0/P0-spec.md), [API 계약](docs/API.md) |
| P1 2차 MVP | 필수 기능 계약·생산 코드·자동 검증 완료, 상시 운영 배포와 일부 실측은 미완료 | [P1 기능별 종료 상태](docs/archive/p1/README.md#기능별-종료-상태) |
| P2 3차 MVP | 공통 명세와 운영 관측 정책을 정본으로 승격했으며, 팀원별 상세 기능 명세·ADR과 구현은 준비 중 | [P2 기능 상태 정본](docs/p2/README.md#기능별-현재-상태) |
| 로컬 검증 환경 | 프록시, Spring 2대, PostgreSQL과 Redis를 `compose.local.yml`로 실행 가능 | [로컬 개발 환경 실행](docs/guides/LOCAL_DEVELOPMENT.md) |
| 운영 배포 | 상시 운영 서비스 배포는 완료하지 않았습니다. 임시 AWS 검증 스택은 측정 뒤 철거했습니다. | [P1 기능별 종료 상태](docs/archive/p1/README.md#기능별-종료-상태) |
| 검증 실측 | 검색·알림은 임시 AWS의 제한된 실측, ROOM-09·10은 로컬 PostgreSQL 기준선을 보존했습니다. `INVALID` 실행과 미측정 기능은 분리합니다. | [k6 측정 문서 규칙](docs/measurements/k6/README.md), [인증·알림 측정](docs/measurements/k6/jiho/README.md), [ROOM-09 측정](docs/measurements/room-09-bounded-processing-baseline.md) |

## 설계에서 지키는 핵심 불변식

- 인증·인가·CSRF와 현재 참가 관계는 서버의 요청 경계에서 다시 확인합니다. [API 계약](docs/API.md), [ADR-0003](docs/adr/auth/0003-p0-server-session-spring-security.md)
- 참가 정원, 중복 참가, 대기열과 상태 전이는 애플리케이션 검사만이 아니라 트랜잭션과 PostgreSQL 제약으로 함께 방어합니다. [ERD](docs/ERD.md), [ADR-0005](docs/adr/participation/0005-room-participation-optimistic-locking.md)
- 모임 변경과 알림 전달은 같은 트랜잭션 안에서 Outbox 이벤트를 기록하고 commit 이후 relay가 전달합니다. [아키텍처](docs/ARCHITECTURE.md#알림-relay복구정리), [ADR-0029](docs/adr/notification/0029-room-integration-event-transactional-outbox.md)
- 저장·비교 시각은 UTC로 통일하고, 스키마 변경은 전진 Flyway 마이그레이션과 PostgreSQL 검증을 함께 둡니다. [ADR-0008](docs/adr/platform/0008-flyway-database-migrations.md), [ADR-0009](docs/adr/platform/0009-utc-time-standard.md)

## 기술적 선택

| 문제 | 선택 | 검증 방식 |
| --- | --- | --- |
| 코드 구조 | 도메인 중심 모듈러 모놀리스 | 모듈 책임과 의존 방향을 [아키텍처](docs/ARCHITECTURE.md)에 고정하고 `ModuleArchitectureTest`로 검사합니다. |
| 업무 데이터 | PostgreSQL, Spring Data JPA, Flyway | H2 빠른 테스트와 PostgreSQL 18 Testcontainers 검증의 책임을 분리합니다. |
| 인증 | Spring Security 서버 세션 | 세션 쿠키·CSRF·로그아웃과 다중 인스턴스 공유 경계를 HTTP·로컬 통합 테스트로 검증합니다. |
| 실시간·비동기 전달 | WebSocket, Redis Pub/Sub, Transactional Outbox | 영속 이력을 정본으로 두고 전달 실패·재연결·relay 복구 경계를 독립적으로 검증합니다. |

전체 선택 근거와 상태는 [ADR 인덱스](docs/adr/README.md)를 따릅니다.

## 대표 검증과 실측

아래 수치는 고정한 검증 릴리스와 fixture에서 얻은 결과입니다. 운영 SLO나 현재 서비스 전체의 용량으로 일반화하지 않습니다.

- 17만 행 게임 fixture의 검색 실험에서 임시 `pg_trgm` GIN 인덱스는 동일한 1 VU 조건의 p95를 `343.8ms`에서 `23.8ms`로 낮췄습니다. 이 수치는 후보 인덱스의 비교 근거이며 운영 스키마 반영 완료를 뜻하지 않습니다. [측정 보고서](docs/measurements/k6/yejin/keyword-search-capacity-2026-08-11.md)
- 시간 기반 방 상태 보정은 최대 10,000개 due ROOM fixture를 고정 시각·seed와 5회 실측으로 비교했고, 결과를 바탕으로 초기 후보 제한 100과 실행당 최대 batch 100을 정했습니다. 로컬 Testcontainers 기준선이며 운영 용량은 아닙니다. [ROOM-09 기준선](docs/measurements/room-09-bounded-processing-baseline.md)

## 팀 — 밤송이클럽

| 팀원 | 주요 역할 |
| --- | --- |
| [@vanilalatte03](https://github.com/vanilalatte03) | 인증·사용자 / 알림·Outbox / CI·AI 협업 체계 |
| [@beyejin](https://github.com/beyejin) | 게임 카탈로그·검색 / 소셜 로그인·계정 연결 / 인기 게임 랭킹 |
| [@gone09-sketch](https://github.com/gone09-sketch) | ROOM 생명주기·상태 보정 / 대기열·동시성 검증 |
| [@silverThunder09](https://github.com/silverThunder09) | 채팅·WebSocket / 방 참가 |

통합 테스트는 네 명이 함께 진행합니다.

## 10분 안에 첫 Green

Java 21과 저장소의 Gradle Wrapper만 사용합니다. 이 첫 테스트에는 Docker와 별도 PostgreSQL이 필요하지 않습니다.

Windows PowerShell:

```powershell
java --version
.\gradlew.bat test
```

macOS·Linux:

```sh
java --version
./gradlew test
```

`BUILD SUCCESSFUL` 또는 명령의 성공 종료를 확인하면 첫 검증이 끝납니다. 전체 화면을 실행하려면 [.env 준비와 로컬 Compose 실행](docs/guides/LOCAL_DEVELOPMENT.md)을, 반복 명령은 [프로젝트 명령](docs/COMMANDS.md)을 따릅니다.

## 문서 지도

| 알고 싶은 것 | 시작 문서 |
| --- | --- |
| 현재 구현·검증·배포·실측 상태 | [이 README의 현재 제공 상태](#현재-제공-상태), [P2 기능 상태 정본](docs/p2/README.md#기능별-현재-상태) |
| 제품 목표와 단계별 범위 | [PRD](docs/PRD.md), [P2 명세](docs/P2-spec.md) |
| 백엔드 구조와 코드 위치 | [아키텍처](docs/ARCHITECTURE.md) |
| 실행·테스트·포맷 명령 | [프로젝트 명령](docs/COMMANDS.md) |
| k6 부하테스트 시나리오와 실행 안내 | [Load Tests](load-tests/README.md) |
| HTTP·WebSocket과 저장 계약 | [API](docs/API.md), [ERD](docs/ERD.md) |
| 기술 선택과 변경 이유 | [ADR](docs/adr/README.md) |
| 최초 설정·운영·적재·문제 해결 | [프로젝트 가이드](docs/guides/README.md) |
| 코드 작성과 협업 방식 | [컨벤션](docs/CONVENTIONS.md) |

P0·P1 구현 기록은 [문서 아카이브](docs/archive/README.md)에 동결했습니다. 새 구현 작업은 [AGENTS.md](AGENTS.md)의 라우팅과 현재 P2 정본에서 시작합니다.

> 문서 관리: 소유자 `밤송이클럽` · 최종 검증일 `2026-08-13` · 폐기 조건 `저장소가 아카이브되거나 별도 공개 제품 페이지가 대표 진입점으로 대체될 때`
