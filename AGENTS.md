# Albam Mate 작업 안내

이 파일은 저장소 작업의 진입 라우터이며, 세부 명령·설정은 아래 문서를 따른다.

## 문서 라우팅

작업에 필요한 기준 문서만 읽고, 맥락이 부족할 때만 연결 문서를 확인한다.

| 작업 | 기준 문서 |
| --- | --- |
| 빌드, 실행, 테스트, 반복 확인 명령 | [docs/COMMANDS.md](docs/COMMANDS.md) |
| 백엔드 구조, 모듈 책임과 의존 흐름 | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 코드 배치, 네이밍, 시간 처리와 커밋 규칙 | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) |
| 프론트엔드 구현, API 호출과 빌드 산출물 | [frontend/AGENTS.md](frontend/AGENTS.md) |
| PostgreSQL 전용 통합 테스트 규약 | [src/postgresTest/AGENTS.md](src/postgresTest/AGENTS.md) (공통 규약은 [src/test/AGENTS.md](src/test/AGENTS.md)와 함께 본다) |
| Flyway 마이그레이션 파일 작업 | [src/main/resources/db/migration/AGENTS.md](src/main/resources/db/migration/AGENTS.md) |
| 아키텍처 결정 기록과 작성 규칙 | [docs/adr/README.md](docs/adr/README.md) |
| 전체 제품 목표와 단계별 범위 | [docs/PRD.md](docs/PRD.md) |
| P2 3차 MVP 범위와 기능별 구현 기준 | [docs/P2-spec.md](docs/P2-spec.md), [docs/p2/README.md](docs/p2/README.md) |
| 운영·설정·데이터 적재·복구 가이드 찾기 | [docs/guides/README.md](docs/guides/README.md) |
| 완료된 P0·P1 문서 아카이브 | [docs/archive/README.md](docs/archive/README.md) |

백엔드 구조·모듈 관계는 `docs/ARCHITECTURE.md`, 경로별 규약은 해당 위치의 `AGENTS.md`, 생산 코드·협업 공통 규약은 `docs/CONVENTIONS.md`에 두고 중복하지 않는다.

P0·P1 문서는 각 단계 종료 시점의 기록으로 [archive](docs/archive/README.md)에 동결했다. P2 구현 작업은 [P2 공통 명세](docs/P2-spec.md)와 해당 [기능별 명세](docs/p2/README.md)를 진입점으로 사용하며, 과거 단계 기능 ID와 아카이브 문서를 새 구현 범위의 정본으로 사용하지 않는다.

P2 담당자는 구현 전에 자신의 상세 기능 명세를 `docs/p2/`에 작성하고 기능 ID·완료 기준·데이터·권한·실패·복구·검증·배포·실측 경계를 [P2 기능 상태](docs/p2/README.md#기능별-현재-상태)에 등록한다. 되돌리기 어렵거나 기능 둘 이상에 영향을 주는 기술 선택은 [ADR 작성 규칙](docs/adr/README.md)에 따라 대안·장단점·배포·rollback·검증·재검토 조건까지 구체적으로 기록한 뒤 구현한다.

## 작업 원칙

- 현재 `albam-mate` 저장소의 코드, 정본 문서와 승인 ADR이 구현·운영 규칙의 정본이다.
- 구현 변경으로 정본 문서가 부정확해지면 함께 갱신하고, 정책·범위·ADR 결정 변경은 사용자 확인 후 진행한다.
- 범위와 관련 정본이 확정된 백엔드 기능 구현·버그 수정은 메인 에이전트만 [backend-delivery](.agents/skills/backend-delivery/SKILL.md) 절차로 `backend-developer`에 위임한다. 절차 정본은 `.agents/skills/**`, 구현자 지시 정본은 `.codex/agents/backend-developer.toml`이며 `.claude/**`의 같은 이름 파일은 정본을 가리키는 실행 어댑터다.
