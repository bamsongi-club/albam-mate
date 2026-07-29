# Flyway 마이그레이션 작업 안내

이 파일은 `src/main/resources/db/migration/**` 작업 규약의 정본이다.

- 테이블, 컬럼, 인덱스, 제약조건 또는 기준 데이터를 변경하는 PR은 Flyway 마이그레이션을 포함한다.
- 파일은 이 디렉터리에 `V<version>__<description>.sql` 형식으로 둔다.
- 초기 스키마 이후의 버전은 최신 기본 브랜치를 반영한 뒤 기존 버전보다 크게 부여한다. 열린 PR끼리 중복되거나 순서가 뒤집히면 나중에 병합하는 PR이 병합 전에 다시 부여한다.
- 공유 환경에 한 번이라도 적용된 버전 파일은 수정하지 않고, 보정은 새 버전으로 추가한다.
- P0의 기존 V1~V3를 폐기하고 재생성하는 일회성 예외는 [ADR-0023](../../../../../docs/adr/platform/0023-p0-flyway-baseline-reset-player-count-stages.md)을 따른다. 새 기준선부터는 위 불변 원칙을 다시 적용한다.
- 스키마가 바뀌면 마이그레이션, JPA Entity와 [ERD](../../../../../docs/ERD.md)를 같은 변경에서 일치시킨다. JPA Entity 변경은 마이그레이션을 대신하지 않는다.
- 공유 개발·검증·운영 환경에서 Hibernate `ddl-auto=create` 또는 `update`로 스키마를 변경하지 않는다.
- PostgreSQL 전용 SQL, 제약과 Flyway 실행 결과는 H2 테스트만으로 검증됐다고 보지 않는다. 변경 후 [PostgreSQL 마이그레이션 검증](../../../../../docs/COMMANDS.md#postgresql-마이그레이션-검증)을 실행한다.

세부 기준은 [ADR-0008](../../../../../docs/adr/platform/0008-flyway-database-migrations.md)을 따른다.
