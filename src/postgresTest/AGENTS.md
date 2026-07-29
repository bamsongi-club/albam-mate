# PostgreSQL 테스트 작업 안내

이 파일은 `src/postgresTest/**`의 전용 규약 정본이다. source set 선택, 공통 작성 규칙과 클래스 접미사는 [일반 테스트 작업 안내](../test/AGENTS.md)를 따른다.

- Flyway, PostgreSQL 전용 SQL·타입·함수·인덱스·제약, 잠금·격리·동시성과 H2 차이의 회귀 경로를 Testcontainers가 관리하는 실제 PostgreSQL에서 검증한다.
- 컨테이너 이미지의 버전 정책은 [ADR-0010의 결정](../../docs/adr/platform/0010-h2-postgresql-test-boundary.md#결정)을 따른다.
- 실행 환경과 `postgresTest` 명령은 [PostgreSQL 마이그레이션 검증](../../docs/COMMANDS.md#postgresql-마이그레이션-검증)을 따른다.
