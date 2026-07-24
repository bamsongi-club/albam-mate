# Albam Mate 작업 안내

이 파일은 저장소 작업의 진입 라우터다. 세부 명령이나 설정을 중복해서 적지 않고 아래 문서를 따른다.

## 문서 라우팅

| 작업 | 기준 문서 |
| --- | --- |
| 빌드, 실행, 테스트, 반복 확인 명령 | [docs/COMMANDS.md](docs/COMMANDS.md) |
| 코드 포맷과 Git hook 최초 설정 | [docs/guides/CODE_FORMATTING.md](docs/guides/CODE_FORMATTING.md) |
| 코드 구조, 네이밍, 데이터베이스 변경, 시간 처리와 커밋 규칙 | [docs/CONVENTIONS.md](docs/CONVENTIONS.md) |
| 아키텍처 결정 기록과 작성 규칙 | [docs/adr/README.md](docs/adr/README.md) |
| Codex·Claude Code 프롬프트 기록 최초 설정 | [docs/guides/PROMPT_LOGGING.md](docs/guides/PROMPT_LOGGING.md) |
| 프롬프트 훅의 동작 범위와 환경변수 규격 | [.bamsongi/README.md](.bamsongi/README.md) |
| 전체 제품 목표와 단계별 범위 | [docs/PRD.md](docs/PRD.md) |
| P0 1차 MVP 구현 범위와 완료 기준 | [docs/P0-spec.md](docs/P0-spec.md) |
| P0 API 요청·응답과 오류 계약 | [docs/API.md](docs/API.md) |
| P0 데이터 모델, 제약, ERD | [docs/ERD.md](docs/ERD.md) |

## 작업 원칙

- 프로젝트 구현과 운영 규칙의 기준은 `albam-mate` 저장소다.
- 프롬프트 원문은 형제 저장소 `bamsongi-brain`의 `prompts/`에만 저장한다.
- 프롬프트 훅은 파일 저장까지만 수행한다. `git add`, commit, push는 사용자가 명시적으로 요청할 때만 수행한다.
- 반복해서 사용하는 명령은 `docs/COMMANDS.md`에 추가하고, 일회성 설정이나 긴 문제 해결 절차는 `docs/guides/`에 작성한 뒤 링크한다.
