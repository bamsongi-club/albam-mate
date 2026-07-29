# PostgreSQL 테스트 작업 안내

이 파일은 `src/postgresTest/**`에만 적용된다. source set 선택은 [일반 테스트 작업 안내의 배치 표](../test/AGENTS.md#source-set-배치), 공통 작성 규칙과 클래스 접미사는 [프로젝트 컨벤션의 테스트 절](../../docs/CONVENTIONS.md#테스트)을 따른다.

- Testcontainers가 관리하는 실제 PostgreSQL에서 검증한다. 클래스명은 프로젝트 컨벤션의 역할별 접미사를 따른다.
- 컨테이너 이미지의 버전 정책은 [ADR-0010의 결정](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md#결정)을 따른다.
- 고정 시각 fixture 규칙은 [프로젝트 컨벤션의 시간 처리 절](../../docs/CONVENTIONS.md#시간-처리)을 따른다.
- 실행 환경과 `postgresTest` 명령은 [PostgreSQL 마이그레이션 검증](../../docs/COMMANDS.md#postgresql-마이그레이션-검증)을 따른다.
- 세부 검증 경계와 근거도 [ADR-0010](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md)을 따른다.
