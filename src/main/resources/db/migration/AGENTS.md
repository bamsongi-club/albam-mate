# Flyway 마이그레이션 작업 안내

이 파일은 `src/main/resources/db/migration/**`와 `src/main/resources/db/vendor-migration/**` 작업 규약의 정본이다.

- 테이블, 컬럼, 인덱스, 제약조건 또는 기준 데이터를 변경하는 PR은 Flyway 마이그레이션을 포함한다.
- 여러 데이터베이스에서 실행할 파일은 `db/migration`에, PostgreSQL 전용 문법을 분리한 파일은 `db/vendor-migration/postgresql`에 `V<version>__<description>.sql` 형식으로 둔다. Flyway가 하위 경로를 재귀 탐색하므로 데이터베이스 전용 파일을 `db/migration` 아래에 두지 않는다.
- 초기 스키마 이후의 버전은 두 경로를 합쳐 최신 기본 브랜치와 열린 PR의 Flyway 버전을 확인한 뒤 그중 최대 번호보다 크게 부여한다. 특정 데이터베이스가 실행하지 않는 버전의 공백은 허용한다. 열린 PR끼리 충돌하면 PR 병합 담당자가 실제 병합 순서를 기준으로 판정하고, 뒤에 병합할 PR의 작성자가 병합 전에 번호를 다시 부여한다.
- 공유 환경에 한 번이라도 적용된 버전 파일은 수정하지 않고, 보정은 새 버전으로 추가한다.
- P0의 기존 V1~V3를 폐기하고 재생성하는 일회성 예외는 [ADR-0023](../../../../../docs/adr/platform/0023-p0-flyway-baseline-reset-player-count-stages.md)을 따른다. 새 기준선부터는 위 불변 원칙을 다시 적용한다.
- 스키마가 바뀌면 마이그레이션, JPA Entity와 [ERD](../../../../../docs/ERD.md)를 같은 변경에서 일치시킨다. JPA Entity 변경은 마이그레이션을 대신하지 않는다.
- 공유 개발·검증·운영 환경에서 Hibernate `ddl-auto=create` 또는 `update`로 스키마를 변경하지 않는다.
- PostgreSQL 전용 SQL, 제약과 Flyway 실행 결과는 H2 테스트만으로 검증됐다고 보지 않는다. 변경 후 [PostgreSQL 마이그레이션 검증](../../../../../docs/COMMANDS.md#postgresql-마이그레이션-검증)을 실행한다.

세부 기준은 [ADR-0008](../../../../../docs/adr/platform/0008-flyway-database-migrations.md)이다.
