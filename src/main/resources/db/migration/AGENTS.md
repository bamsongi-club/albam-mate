# Flyway 마이그레이션 작업 안내

이 파일은 `src/main/resources/db/migration/**`의 정본 라우터다. 파일명, 버전 순서, 적용된 파일의 불변성, JPA·ERD 동기화와 H2 검증 한계는 [프로젝트 컨벤션의 데이터베이스 변경 절](../../../../../docs/CONVENTIONS.md#데이터베이스-변경)을 따른다.

- 데이터 구조 계약은 [ERD](../../../../../docs/ERD.md), Flyway 선택 근거는 [ADR-0008](../../../../../docs/adr/platform/0008-flyway-database-migrations.md)을 확인한다.
- 변경 후 실제 PostgreSQL 검증은 [프로젝트 명령](../../../../../docs/COMMANDS.md#postgresql-마이그레이션-검증)을 따른다.
