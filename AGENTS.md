# Albam Mate 작업 안내

이 파일은 저장소 작업의 진입 라우터이며, 세부 명령·설정은 아래 문서를 따른다.

## 문서 라우팅

작업에 필요한 기준 문서만 읽고, 맥락이 부족할 때만 연결 문서를 확인한다.

| 작업 | 기준 문서 |
| --- | --- |
| 빌드, 실행, 테스트, 반복 확인 명령 | [docs/COMMANDS.md](docs/COMMANDS.md) |
| 코드 구조, 네이밍, 시간 처리와 커밋 규칙 | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) |
| 프론트엔드 구현, API 호출과 빌드 산출물 | [frontend/AGENTS.md](frontend/AGENTS.md) |
| PostgreSQL 전용 통합 테스트 규약 | [src/postgresTest/AGENTS.md](src/postgresTest/AGENTS.md) (공통 규약은 [src/test/AGENTS.md](src/test/AGENTS.md)와 함께 본다) |
| Flyway 마이그레이션 파일 작업 | [src/main/resources/db/migration/AGENTS.md](src/main/resources/db/migration/AGENTS.md) |
| 아키텍처 결정 기록과 작성 규칙 | [docs/adr/README.md](docs/adr/README.md) |
| 전체 제품 목표와 단계별 범위 | [docs/PRD.md](docs/PRD.md) |
| P0 기능·API·데이터 명세 진입점 | [docs/P0-spec.md#관련-문서](docs/P0-spec.md#관련-문서) |

경로별 규약은 해당 위치의 `AGENTS.md`, 생산 코드·협업 공통 규약은 `docs/CONVENTIONS.md`에 두고 중복하지 않는다.

P0 구현은 `docs/p0/`의 해당 기능 ID 절부터 읽고 맥락이 부족할 때만 상위 문서를 확인한다. 필요한 공유 기반이 없으면 [기반 작업 명세](docs/p0/foundation.md)의 선행 항목부터 확인한다.

## 작업 원칙

- 현재 `albam-mate` 저장소의 코드, 정본 문서와 승인 ADR이 구현·운영 규칙의 정본이다.
- 범위와 관련 정본이 확정된 백엔드 기능 구현·버그 수정은 메인 Codex 에이전트만 [backend-delivery](.agents/skills/backend-delivery/SKILL.md) 절차로 `backend-developer`에 위임한다.
