# ADR-0010: H2와 PostgreSQL의 테스트 검증 경계를 분리

- 상태: 승인됨
- 작성일: 2026-07-24
- 결정일: 2026-07-24
- 관련: [ADR-0002](0002-postgresql-primary-database.md), [ADR-0005](../participation/0005-room-participation-optimistic-locking.md), [ADR-0008](0008-flyway-database-migrations.md), [테스트 작업 안내](../../../src/test/AGENTS.md), [프로젝트 명령](../../COMMANDS.md), [build.gradle](../../../build.gradle)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

Albam Mate는 PostgreSQL을 업무 데이터의 정본으로 선택했다. 현재 Gradle 테스트 런타임에는 H2만 선언돼 있으며, `test`와 `build`는 별도 PostgreSQL 없이 실행된다. 빠른 로컬 피드백에는 유리하지만, 데이터베이스 엔진이 다르므로 H2 통과만으로 PostgreSQL의 SQL, 타입, 제약, 트랜잭션과 잠금 동작까지 검증됐다고 볼 수 없다.

특히 Flyway 마이그레이션은 PostgreSQL SQL을 실행하고, 방 참가 불변식은 데이터베이스 제약과 동시 트랜잭션에 의존한다. 이 경로를 H2에서만 확인하면 애플리케이션 테스트는 통과해도 배포 시 마이그레이션이 실패하거나 동시 요청에서 정원·중복 규칙이 깨질 수 있다. 반대로 모든 데이터베이스 테스트를 PostgreSQL 컨테이너에서만 실행하면 단순한 애플리케이션 검증까지 컨테이너 시작 시간과 로컬 Docker 환경에 의존한다.

이번 결정의 기준은 다음과 같다.

- 일상적인 로컬 테스트는 외부 데이터베이스 없이 빠르게 실행할 수 있을 것
- PostgreSQL에 의존하는 계약은 실제 PostgreSQL 엔진에서 검증할 것
- 개발자 PC와 CI가 공유 데이터베이스의 상태나 자격증명에 의존하지 않을 것
- 어떤 테스트 결과가 운영 데이터베이스 동작의 근거가 되는지 명확할 것
- 운영 PostgreSQL 버전과 테스트 버전의 차이를 통제할 수 있을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 모든 데이터베이스 테스트를 H2로 실행 | 컨테이너 없이 빠르게 실행하고 테스트 설정을 단순하게 유지할 수 있다. | PostgreSQL 전용 SQL·타입·제약·인덱스와 실제 잠금·격리 동작을 재현하지 못한다. H2 호환을 위해 운영 SQL이나 모델을 왜곡할 위험도 있다. | 제외 |
| 모든 데이터베이스 통합 테스트를 Testcontainers PostgreSQL로 실행 | 테스트 엔진과 운영 엔진을 맞춰 데이터베이스 동작의 신뢰도를 높이고 하나의 통합 환경만 관리한다. | 모든 영속성 테스트가 컨테이너 런타임과 시작 시간에 의존한다. 단순한 JPA 매핑·애플리케이션 피드백까지 느려져 로컬 반복 비용이 커진다. | 제외 |
| H2 빠른 테스트와 Testcontainers PostgreSQL 검증을 계층화 | 일상적인 피드백 속도를 유지하면서 PostgreSQL 고유 계약은 격리된 실제 엔진에서 검증할 수 있다. 공유 데이터베이스와 자격증명도 필요 없다. | 두 테스트 경계와 fixture를 관리해야 하며, PostgreSQL 검증에는 컨테이너 런타임과 추가 CI 시간이 필요하다. | 선택 |

## 결정

H2 기반 빠른 테스트와 Testcontainers가 관리하는 PostgreSQL 검증 테스트를 분리한다.

기존 `test` 태스크는 H2를 사용하며 컨테이너 없이 실행한다. 단위 테스트, 애플리케이션 컨텍스트, 표준 JPA 범위의 매핑·조회와 서비스 흐름에 대한 빠른 피드백을 담당한다. 다만 H2 테스트의 통과는 PostgreSQL 동작이 검증됐다는 근거로 사용하지 않는다.

별도 `postgresTest` 태스크는 Testcontainers로 일회성 PostgreSQL을 시작하고 다음 계약을 검증한다.

- 빈 데이터베이스에 전체 Flyway 마이그레이션 적용과 Hibernate 스키마 검증
- PostgreSQL 전용 SQL, 타입, 함수, 인덱스, 자동 증가 키와 데이터베이스 제약
- 트랜잭션 격리, 행 잠금, 낙관 락 충돌과 실제 동시 요청에서 지켜야 하는 불변식
- H2와 PostgreSQL의 차이 때문에 발생했거나 발생 가능성이 확인된 회귀 경로

하나의 기능에 H2 테스트가 있더라도 결과가 데이터베이스 제약이나 동시성에 의존하면 PostgreSQL 테스트를 함께 둔다. PostgreSQL 호환 모드의 H2는 실제 PostgreSQL 검증을 대신하지 않으며, H2를 통과시키기 위해 운영 마이그레이션이나 쿼리를 별도로 단순화하지 않는다.

PostgreSQL 컨테이너 이미지는 테스트 설정에 명시적으로 고정하고 운영에서 지원하는 메이저 버전과 맞춘다. 운영 버전이 바뀌면 같은 변경에서 테스트 이미지를 갱신하고 전체 PostgreSQL 검증을 실행한다.

구현 후 CI는 `test`와 `postgresTest`를 모두 실행하며, 둘 중 하나라도 실패하면 병합할 수 없게 한다. 로컬 `test`와 `build`는 빠른 반복을 위해 PostgreSQL을 요구하지 않는다. `postgresTest`가 추가된 뒤에는 데이터베이스 변경 작업을 제출하기 전에 해당 태스크로 확인한다.

## 결과

- 얻는 것: 일상적인 H2 테스트 속도를 유지하면서 Flyway, PostgreSQL 스키마·제약과 동시성 동작을 운영 엔진과 같은 종류의 데이터베이스에서 검증한다. 각 실행은 일회성 컨테이너를 사용하므로 공유 테스트 데이터베이스의 오염과 자격증명 관리를 피한다.
- 감수할 비용·위험: PostgreSQL 검증을 실행하려면 지원되는 컨테이너 런타임이 필요하고 이미지 내려받기와 시작만큼 CI 시간이 늘어난다. H2와 PostgreSQL 두 경계에 맞는 fixture와 테스트 분류도 관리해야 한다.
- 후속 작업: Spring Boot Testcontainers, Testcontainers JUnit 5·PostgreSQL 의존성과 `postgresTest` 태스크를 추가한다. Flyway 초기화·스키마 검증과 참가 동시성 시나리오를 PostgreSQL 테스트로 구현하고, CI 필수 검사 및 `docs/COMMANDS.md` 실행 명령을 연결한다.

## 보류 및 재검토

- 지금 하지 않는 것:
  - 모든 단위·서비스 테스트의 PostgreSQL 전환
  - 팀 공유 PostgreSQL 테스트 서버 운영
  - H2 호환 모드를 PostgreSQL 검증 근거로 사용
- 보류 이유: 현재 P0는 빠른 로컬 반복의 이점이 크고, 실제 PostgreSQL이 필요한 경로를 별도 테스트로 명시할 수 있다. Testcontainers로 격리 환경을 만들 수 있으므로 공유 서버를 운영할 이유도 없다.
- 다시 검토할 조건:
  - H2 전용 설정·fixture 유지 비용이 반복해서 발생하거나 H2 통과 후 PostgreSQL에서만 실패하는 결함이 계속 발견될 때
  - PostgreSQL 검증 시간이 개발·CI 피드백을 수용하기 어렵게 만들 때
  - 주 데이터베이스가 바뀔 때

## 참고 자료

- [Spring Boot의 Testcontainers 지원](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
- [Testcontainers PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/)
- [Testcontainers JUnit 5 통합](https://java.testcontainers.org/test_framework_integration/junit_5/)
- [ADR-0002: PostgreSQL을 주 데이터베이스로 채택](0002-postgresql-primary-database.md)
- [ADR-0008: Flyway SQL 마이그레이션으로 데이터베이스 스키마를 관리](0008-flyway-database-migrations.md)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - `build.gradle`에 Testcontainers·Flyway PostgreSQL 의존성을 둔다.
        - `build.gradle`에 분리된 `postgresTest` 태스크를 둔다.
        - 이전에 남아 있던 동시성 범위도 PostgreSQL로 옮겼다.
    - 계약:
        - H2 `test`는 이 ADR의 경계대로 업무 규칙만 담당한다.
        - H2 `test`는 이 ADR의 경계대로 매핑 회귀만 담당한다.
    - 테스트:
        - `SchemaValidationPostgresTest`는 PostgreSQL 18 메타데이터를 확인한다.
        - `SchemaValidationPostgresTest`는 Flyway 스키마 검증을 확인한다.
        - `SchemaValidationPostgresTest`는 Hibernate 스키마 검증을 확인한다.
        - `SchemaValidationPostgresTest`는 실제 CHECK 제약 위반의 SQLSTATE와 제약명을 확인한다.
        - `SchemaValidationPostgresTest`는 실제 FK 제약 위반의 SQLSTATE와 제약명을 확인한다.
        - `RoomParticipationConcurrencyPostgresTest`는 같은 방 버전을 읽은 두 요청의 마지막 좌석 경합을 확인한다.
        - `RoomParticipationConcurrencyPostgresTest`는 참가 취소와 새 참가의 재시도를 확인한다.
        - `RoomParticipationConcurrencyPostgresTest`는 정원 축소와 새 참가를 확인한다.
        - `RoomParticipationConcurrencyPostgresTest`는 취소된 기존 참가와 신규 참가를 확인한다.
        - `RoomParticipationConcurrencyPostgresTest`는 참가 저장 실패의 같은 트랜잭션 롤백을 확인한다.
        - `RoomParticipationConcurrencyPostgresTest`는 매 시나리오 뒤 저장 불변식을 검사한다.
        - `SchemaValidationPostgresTest`는 독립 트랜잭션의 같은 정규화 이메일 동시 가입이 한 건만 생성되는지 확인한다.
    - CI:
        - `build` 뒤 `postgresTest`를 실행한다.
- 미검증:
    - 없음

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
