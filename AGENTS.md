# Albam Mate 작업 안내

이 파일은 저장소 작업의 진입 라우터다. 세부 명령이나 설정을 중복해서 적지 않고 아래 문서를 따른다.

## 문서 라우팅

| 작업 | 기준 문서 |
| --- | --- |
| 빌드, 실행, 테스트, 반복 확인 명령 | [docs/COMMANDS.md](docs/COMMANDS.md) |
| 코드 구조, 네이밍, 데이터베이스 변경, 시간 처리와 커밋 규칙 | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) |
| 아키텍처 결정 기록과 작성 규칙 | [docs/adr/README.md](docs/adr/README.md) |
| 전체 제품 목표와 단계별 범위 | [docs/PRD.md](docs/PRD.md) |
| P0 기능·API·데이터 명세 진입점 | [docs/P0-spec.md#관련-문서](docs/P0-spec.md#관련-문서) |

P0 구현 작업은 `docs/p0/`의 해당 기능 ID 절부터 읽고, 맥락이 부족할 때만 연결된 상위 문서로 올라간다.

## 작업 원칙

- 프로젝트 구현과 운영 규칙의 기준은 `albam-mate` 저장소다.
- Codex의 백엔드 기능 구현·버그 수정은 항상 프로젝트의 `backend-developer` 서브에이전트에 위임한다.
