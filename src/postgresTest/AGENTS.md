# PostgreSQL 테스트 작업 안내

이 파일은 `src/postgresTest/**` 전용 규약의 정본이며, source set 선택·공통 작성·클래스 접미사·전용 검증 대상은 [일반 테스트 작업 안내](../test/AGENTS.md)를 따른다.

- 검증은 Testcontainers가 관리하는 실제 PostgreSQL에서 실행한다.
- 컨테이너 이미지의 버전 정책은 [ADR-0010의 결정](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md#결정)을 따른다.
- 실행 환경과 `postgresTest` 절차는 [PostgreSQL 검증 실행](../../docs/guides/TESTING.md#postgresql-검증-실행)을 따르고, 짧은 반복 명령은 [프로젝트 명령](../../docs/COMMANDS.md#postgresql-마이그레이션-검증)을 사용한다.
