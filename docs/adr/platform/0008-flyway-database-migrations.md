# ADR-0008: Flyway SQL 마이그레이션으로 데이터베이스 스키마를 관리

- 상태: 승인됨
- 작성일: 2026-07-24
- 결정일: 2026-07-24
- 관련: [ADR-0002](0002-postgresql-primary-database.md), [ADR-0010](0010-h2-postgresql-test-boundary.md), [ADR-0083](0083-github-actions-develop-p1-continuous-deployment.md), [ERD](../../ERD.md), [build.gradle](../../../build.gradle), [프로젝트 컨벤션](../../CONVENTIONS.md)
- 대체 대상: 없음
- 후속 ADR: [ADR-0083](0083-github-actions-develop-p1-continuous-deployment.md) (P1 production App1·App2의 Flyway 실행 위치 범위)

## 맥락

Albam Mate는 PostgreSQL을 업무 데이터의 정본으로 선택했고, P0 ERD에는 테이블, 외래 키, 고유 제약과 검사 제약이 정의돼 있다. 그러나 현재 저장소에는 실제 스키마를 생성하는 SQL, 마이그레이션 도구와 운영 데이터베이스 초기화 절차가 없다.

JPA Entity만 변경하거나 개발자가 데이터베이스에 SQL을 수동 실행하면 각 환경에 어떤 변경이 언제 적용됐는지 코드 리뷰와 배포 기록만으로 재현하기 어렵다. 스키마와 애플리케이션 코드의 배포 순서가 어긋나면 애플리케이션 시작 또는 요청 처리 중에 오류가 발생할 수 있다. 따라서 스키마 변경을 버전 관리하고 모든 환경에 같은 순서로 적용하는 단일 절차가 필요하다.

이번 결정의 기준은 다음과 같다.

- PostgreSQL에 실행될 SQL을 코드 리뷰에서 직접 확인할 수 있을 것
- 새 데이터베이스를 처음부터 같은 상태로 재현할 수 있을 것
- 어떤 변경이 적용됐는지와 적용된 파일의 변경 여부를 추적할 수 있을 것
- Spring Boot 시작 과정과 단순하게 통합할 수 있을 것
- P0에 필요하지 않은 별도 스키마 선언 형식과 운영 도구를 추가하지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| Flyway 버전 SQL 마이그레이션 | PostgreSQL SQL을 그대로 리뷰할 수 있고, 버전 순서·적용 이력·체크섬을 관리한다. Spring Boot가 애플리케이션 시작 시 마이그레이션을 실행할 수 있다. | 변경 순서와 SQL의 전·후방 호환성을 개발자가 관리해야 한다. 적용된 파일을 수정하면 검증 오류가 발생하므로 보정 마이그레이션을 새로 작성해야 한다. | 선택 |
| Liquibase 변경 로그 | XML, YAML, JSON 또는 SQL 기반 변경 로그와 다양한 변경 관리 기능을 제공한다. | P0에서 별도 DSL과 더 넓은 기능을 학습·관리할 이유가 확인되지 않았다. PostgreSQL SQL을 직접 검토하려는 기준에는 Flyway가 더 단순하다. | 제외 |
| Hibernate `ddl-auto`로 스키마 생성·변경 | Entity에서 개발용 스키마를 빠르게 만들 수 있다. | 운영 변경 이력과 명시적인 SQL 검토가 남지 않고, 제약·인덱스·데이터 보정과 배포 순서를 안정적으로 관리하기 어렵다. | 제외 |
| SQL 파일을 사람이 수동 실행 | 별도 라이브러리를 추가하지 않아도 된다. | 적용 순서, 누락, 중복 실행과 환경별 차이를 자동으로 검증할 수 없다. 새 환경 재현과 장애 분석이 사람의 기록에 의존한다. | 제외 |

## 결정

Albam Mate의 PostgreSQL 스키마 변경은 Flyway의 버전 SQL 마이그레이션으로 관리한다. Spring Boot가 애플리케이션 시작 시 Flyway를 실행하며, 공유 개발·검증·운영 환경의 스키마를 변경하는 정식 경로는 저장소의 마이그레이션 파일로 통일한다.

공통 마이그레이션은 기본 경로인 `src/main/resources/db/migration`에 두고 `V<version>__<description>.sql` 형식을 사용한다. PostgreSQL 전용 문법을 공통 마이그레이션과 분리해야 할 때는 `src/main/resources/db/vendor-migration/postgresql`에 같은 형식으로 두며, Spring Boot의 Flyway 위치를 공통 경로와 `classpath:db/vendor-migration/{vendor}`로 구성한다. Flyway가 위치의 하위 디렉터리를 재귀 탐색하므로 데이터베이스 전용 경로를 공통 경로 아래에 두지 않는다.

공통 경로와 데이터베이스 전용 경로의 버전은 저장소 안에서 전역으로 유일하고 증가해야 한다. 특정 데이터베이스에 적용되지 않는 버전의 공백은 허용하되, H2용 대체 SQL로 PostgreSQL 운영 마이그레이션을 단순화하지 않고 PostgreSQL 전용 결과는 [ADR-0010](0010-h2-postgresql-test-boundary.md)의 `postgresTest`에서 검증한다. 테이블, 컬럼, 인덱스, 제약조건 또는 기준 데이터를 변경하는 PR은 관련 JPA·문서 변경과 함께 마이그레이션을 포함한다.

여기서 기준 데이터는 애플리케이션 버전과 함께 관리하는 소규모 고정값을 뜻한다. 출처·스냅샷·갱신 주기를 별도로 관리하는 대규모 외부 게임 카탈로그 데이터는 기준 데이터에 포함하지 않으며, 적재·갱신 방식은 별도 카탈로그 적재 ADR에서 결정한다.

한 번이라도 공유 환경에 적용된 버전 마이그레이션은 수정하거나 재사용하지 않는다. 잘못된 변경은 새 버전의 보정 마이그레이션으로 전진 수정한다. JPA Entity 변경과 Hibernate 자동 DDL은 마이그레이션을 대신하지 않으며, 운영 스키마를 자동 생성·갱신하는 용도로 `ddl-auto=create` 또는 `update`를 사용하지 않는다.

P0에서는 애플리케이션 시작 마이그레이션을 사용한다. 이후 여러 애플리케이션 인스턴스를 동시에 배포하거나 스키마 변경을 애플리케이션 배포와 분리해야 할 운영 요구가 생기면, 별도 마이그레이션 작업으로 실행 위치를 바꾸되 마이그레이션 파일과 이력의 정본은 Flyway로 유지한다.

## 결과

- 얻는 것: 데이터베이스 변경이 애플리케이션 코드와 함께 버전 관리되고, 새 환경을 같은 순서의 SQL로 재현할 수 있다. 적용 이력과 체크섬을 통해 누락·변조를 확인할 수 있다.
- 감수할 비용·위험: 모든 스키마 변경에 전진 마이그레이션을 작성해야 한다. 큰 테이블 변경이나 파괴적 변경은 잠금 시간과 이전 애플리케이션 버전의 호환성을 별도로 검토해야 한다.
- 후속 작업: Spring Boot Flyway starter와 PostgreSQL용 Flyway 모듈을 추가하고, ERD를 구현하는 최초 마이그레이션을 작성한다. PostgreSQL 환경에서 빈 데이터베이스 마이그레이션과 재실행을 검증하고, Hibernate는 스키마 검증 용도로 구성한다.

## 보류 및 재검토

- 지금 하지 않는 것: Liquibase 도입, Flyway Undo 마이그레이션, 별도 배포 파이프라인의 마이그레이션 작업, 자동 스키마 롤백
- 보류 이유: 현재 P0는 하나의 애플리케이션 배포 단위이며, 우선 필요한 것은 검토 가능한 SQL과 재현 가능한 적용 이력이다. 데이터 복구와 배포 롤백은 스키마의 전·후방 호환성과 백업 정책까지 포함한 별도 운영 문제다.
- 다시 검토할 조건: 다중 인스턴스 배포에서 시작 마이그레이션이 배포 흐름을 방해할 때, 무중단 스키마 변경 절차가 필요할 때, Flyway로 표현하기 어려운 데이터베이스 변경 요구가 반복될 때

## 참고 자료

- [Spring Boot 데이터베이스 초기화와 Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Flyway 버전 마이그레이션](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/versioned-migrations)
- [Flyway 스키마 이력 테이블](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/flyway-schema-history-table)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - `build.gradle`은 Flyway PostgreSQL 의존성을 포함하고, `src/main/resources/db/migration`은 V1~V3 SQL 마이그레이션을 관리한다.
        - 모든 프로필은 Hibernate `ddl-auto=validate`를 사용하고 자동 DDL 생성·갱신을 사용하지 않는다.
    - 계약:
        - 기존 기준선을 다시 만든 일회성 예외와 환경 재생성 조건은 [ADR-0023](0023-p0-flyway-baseline-reset-player-count-stages.md)에 기록한다.
    - 테스트:
        - `SchemaValidationPostgresTest`는 빈 PostgreSQL 18에서 Flyway 검증·V1~V3 성공 이력·ERD 테이블 생성과 단계별 인원 컬럼의 존재·NULL 정책·기존 값 보존을 확인한다.
    - CI:
        - `build` 뒤 `postgresTest`를 실행한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
