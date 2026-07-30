# P0 기반 작업 명세

이 문서는 기능 구현에 앞서 필요한 기반 작업을 독립적으로 착수·검증할 단위로 정의한다. 승인된 ADR과 계약 문서의 후속 작업만 모으며 새 결정을 만들지 않는다.

정본은 다음과 같다.

- 결정 근거: 각 항목이 연결한 ADR
- HTTP 계약: [API 명세](../API.md)
- 저장 계약: [ERD](../ERD.md)
- 구현 방식: [컨벤션](../CONVENTIONS.md)

기반 작업은 대부분 [공유 파일](../CONVENTIONS.md#협업-개발)을 변경한다. 공유 파일만 변경하는 PR을 먼저 머지하고 기능 PR은 그 뒤에 rebase한다.

완료 기준 ID 규칙은 [P0 명세](../P0-spec.md#완료-기준-id)를 따른다. 기능 ID와 달리 기반 작업은 제품 동작을 추가하지 않으므로 완료 기준은 산출물과 검증 근거로 적는다.

## 의존 순서와 영향 범위

| ID | 기반 작업 | 선행 | 이 작업을 기다리는 범위 |
| --- | --- | --- | --- |
| [FND-01](#fnd-01-공통-응답과-오류-계약) | 공통 응답과 오류 계약 | 없음 | 17개 API 전체 |
| [FND-02](#fnd-02-시간-기준-구성) | 시간 기준 구성 | 없음 | 시각을 다루는 모든 기능 |
| [FND-03](#fnd-03-스키마와-엔티티-골격) | 스키마와 엔티티 골격 | FND-02 | 저장을 다루는 모든 기능 |
| [FND-04](#fnd-04-인증-기반-구성) | 인증 기반 구성 | FND-01 | 인증이 필요한 API 전체 |
| [FND-05](#fnd-05-비밀번호-저장과-인증-요청-제한) | 비밀번호 저장과 인증 요청 제한 | FND-04 | AUTH-02, AUTH-03 |
| [FND-06](#fnd-06-postgresql-검증-환경) | PostgreSQL 검증 환경 | FND-03 | PostgreSQL 검증이 필수인 완료 기준 |
| [FND-07](#fnd-07-모듈-구조-검증) | 모듈 구조 검증 | FND-03, 업무 모듈 2개 이상, 기존 공개 계약의 `contract` 재배치 | 없음. 경계 회귀를 막는다 |
| [FND-08](#fnd-08-로컬-개발-postgresql-환경) | 로컬 개발 PostgreSQL 환경 | FND-03, [FND-06-AC5 운영 지원 버전 계약](#운영-지원-버전-계약) | 로컬 애플리케이션 실행과 수동 API 검증 |

추가 순서는 다음과 같다.

- FND-01~FND-03은 기능 이슈보다 먼저 머지한다.
- FND-04·FND-05는 인증·프로필 기능과, FND-06은 PostgreSQL 검증이 필요한 완료 기준과 함께 착수할 수 있다.
- FND-07 전에 `user`·`game` 공개 계약을 `contract`로 옮기고 공개 구체 서비스를 인터페이스 계약으로 바꾼다. P0는 업무 모듈 2개 이상 조건을 충족하므로 이 정리와 FND-07을 1차 MVP 배포 전에 완료한다.
- FND-06의 운영 지원 버전과 이미지 일치 기준은 [운영 지원 버전 계약](#운영-지원-버전-계약)을 따른다.
- FND-08은 FND-03·`FND-06-AC5` 뒤 시작하되 FND-06 전체 완료는 기다리지 않는다. `FND-08-AC7` 검증에는 FND-06의 `postgresTest`·CI 산출물이 필요하다.

FND-04·FND-05 분리는 인증 책임을 이중화하지 않는다. 인증 담당자가 관련 AUTH 기능과 함께 맡을 수 있고 `global/**` 기반은 한 번만 구현한다. FND 완료 기준은 기반 산출물·독립 검증, AUTH 완료 기준은 그 기반을 쓰는 엔드포인트의 업무 흐름·통합 검증을 다룬다.

## FND-01 공통 응답과 오류 계약

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| HTTP 계약 | [공통 응답](../API.md#13-공통-응답), [오류 코드](../API.md#9-오류-코드), [엔드포인트별 오류 매트릭스](../API.md#10-부록-엔드포인트별-오류-매트릭스) |
| 구현 규칙 | [예외 처리](../CONVENTIONS.md#예외-처리), [API 응답](../CONVENTIONS.md#api-응답) |
| 소유 경로 | `global/**` (main/test 포함) |

### 산출물

- 성공·실패 공통 응답 표현과 응답 `status`를 실제 HTTP 상태와 일치시키는 조립 지점
- API 명세 9절의 오류 코드 목록과 한국어 기본 메시지를 담은 코드 표현
- 도메인 실패를 오류 코드로 변환하는 `@RestControllerAdvice`와 공통 `BusinessException` 계층
- Bean Validation 실패와 프레임워크 예외를 공통 실패 응답으로 변환하는 경로

### 완료 기준

- `FND-01-AC1` 성공·실패 응답의 필드 구성과 `status` 값이 공통 응답 계약과 일치한다.
- `FND-01-AC2` API 명세 9절의 모든 오류 코드를 코드에서 참조할 수 있고, 예외 클래스명이나 로그 메시지로 코드를 즉석에서 만들지 않는다.
- `FND-01-AC3` 검증 실패, 인증·권한 실패와 처리하지 않은 예외가 각각 계약된 오류 코드로 변환된다.
- `FND-01-AC4` 실패 응답에 비밀번호, 세션 식별자, 인증정보와 사용자 ID가 포함되지 않는다.
- `FND-01-AC5` 위 변환 경로의 성공·실패 테스트가 있고 CI를 통과한다.

### 제외 범위

- 기능별 오류 판정 순서 구현. 각 기능 ID에서 다룬다.
- 오류 응답의 다국어 메시지

## FND-02 시간 기준 구성

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0009 시스템 기준 시각을 UTC로 통일](../adr/platform/0009-utc-time-standard.md) |
| HTTP 계약 | [HTTP와 데이터 형식](../API.md#11-http와-데이터-형식) |
| 공통 규칙 | [시간 경계](../P0-spec.md#시간-경계) |
| 구현 규칙 | [시간 처리](../CONVENTIONS.md#시간-처리) |
| 소유 경로 | `global/**`, `src/main/resources/application.yml` |

### 산출물

- 주입 가능한 `Clock` Bean. 운영은 `Clock.systemUTC()`를 사용한다.
- JVM, 실행 환경과 데이터베이스 연결의 시간대를 UTC로 명시하는 설정
- 오프셋이 포함된 ISO 8601 요청 값의 역직렬화와 P0 응답의 `Asia/Seoul` 변환 구성

### 완료 기준

- `FND-02-AC1` 애플리케이션 코드가 시스템 기본 시간대에 의존하지 않고 주입받은 `Clock`으로 현재 시각을 얻는다.
- `FND-02-AC2` 오프셋이 다른 같은 순간의 요청 값이 같은 시각으로 저장·비교된다.
- `FND-02-AC3` 응답의 시각 필드가 `+09:00` 오프셋으로 반환된다.
- `FND-02-AC4` `Clock.fixed(...)`로 고정 시각을 주입하는 테스트가 있고 CI를 통과한다.

### 제외 범위

- 사용자별 시간대 설정
- 기능별 시간 경계 판정 구현. 각 기능 ID에서 다룬다.

## FND-03 스키마와 엔티티 골격

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0008 Flyway SQL 마이그레이션](../adr/platform/0008-flyway-database-migrations.md), [ADR-0007 도메인 중심 모듈러 모놀리스](../adr/platform/0007-domain-centered-modular-monolith.md), [ADR-0006 BIGINT 자동 증가 키](../adr/platform/0006-p0-bigint-identity-ids.md) |
| 데이터 모델 | [테이블 명세](../ERD.md#테이블-명세), [DB 제약](../ERD.md#db-제약), [필수 제약과 계산 규칙](../ERD.md#필수-제약과-계산-규칙) |
| 구현 규칙 | [패키지와 모듈](../CONVENTIONS.md#패키지와-모듈), [마이그레이션 작업 안내](../../src/main/resources/db/migration/AGENTS.md), [Entity와 DTO](../CONVENTIONS.md#entity와-dto) |
| 선행 | [FND-02](#fnd-02-시간-기준-구성) |
| 소유 경로 | `build.gradle`, `src/main/resources/db/migration/**`, `src/main/resources/application.yml`, `user/**`, `game/**`, `room/**` |

### 산출물

- Flyway starter와 PostgreSQL용 Flyway 모듈 의존성
- ERD의 네 테이블과 제약을 만드는 `V1__` 초기 마이그레이션
- `User`, `Game`, `Room`, `Participation` 엔티티와 이를 담는 도메인 패키지. 참가 관계는 [컨벤션의 소유 규칙](../CONVENTIONS.md#패키지와-모듈)에 따라 `room`이 소유한다.
- Hibernate를 스키마 검증 용도로 구성하는 설정

### 완료 기준

- `FND-03-AC1` 빈 데이터베이스에 초기 마이그레이션이 적용되고 재실행에서 이력·체크섬이 유지된다.
- `FND-03-AC2` 엔티티의 식별자 타입·생성 전략과 시각 필드 타입이 연결한 ADR과 ERD 제약에 일치한다.
- `FND-03-AC3` 도메인 패키지가 컨벤션의 참조 방향을 위반하는 import 없이 컴파일된다.
- `FND-03-AC4` 컨벤션의 패키지 트리와 실제 생성한 패키지 목록이 일치한다. 어긋나면 같은 PR에서 문서를 맞춘다.
- `FND-03-AC5` 공유 개발·검증·운영 환경에서 Hibernate가 스키마를 생성·변경하지 않는다.

### 제외 범위

- 빈 `controller`, `service`, `repository` 계층 패키지의 선행 생성
- 게임 목록 데이터 적재. [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)에서 다룬다.
- 기능별 Repository 조회 메서드와 Entity 상태 변경 메서드. 각 기능 ID에서 다룬다.
- PostgreSQL 기반 마이그레이션 검증. [FND-06](#fnd-06-postgresql-검증-환경)에서 다룬다.

## FND-04 인증 기반 구성

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0003 서버 세션과 Spring Security](../adr/auth/0003-p0-server-session-spring-security.md) |
| HTTP 계약 | [인증·세션·CSRF](../API.md#12-인증세션csrf) |
| 공통 규칙 | [권한과 공개 범위](../P0-spec.md#권한과-공개-범위) |
| 구현 규칙 | [패키지와 모듈](../CONVENTIONS.md#패키지와-모듈), [설정과 비밀정보](../CONVENTIONS.md#설정과-비밀정보) |
| 선행 | [FND-01](#fnd-01-공통-응답과-오류-계약) |
| 소유 경로 | `build.gradle`, `global/**` |

### 산출물

- Spring Security 의존성과 공개·보호 경로 설정
- 세션 쿠키와 CSRF 토큰 처리 구성
- 여러 모듈이 사용하는 인증 사용자 식별 기술 계약. 업무 모듈이 아니라 `global`에 둔다.
- 인증·권한 실패를 FND-01의 공통 오류 응답으로 변환하는 연결

### 완료 기준

- `FND-04-AC1` 공개 경로는 비로그인 요청을 허용하고 보호 경로는 인증 없는 요청을 계약된 오류로 거절한다.
- `FND-04-AC2` 세션 쿠키와 CSRF 토큰의 속성·전달 방식이 API 계약과 일치한다.
- `FND-04-AC3` 업무 모듈이 인증 사용자 식별을 `global`이 공개한 계약으로만 얻는다.
- `FND-04-AC4` 인증·권한 실패 응답에 세션 식별자와 인증정보가 포함되지 않는다.
- `FND-04-AC5` 공개·보호 경로와 CSRF 처리의 성공·실패 테스트가 있고 CI를 통과한다.

### 제외 범위

- 회원가입·로그인·로그아웃 유스케이스 구현. [AUTH-02](auth-profile.md#auth-02-회원가입), [AUTH-03](auth-profile.md#auth-03-로그인로그아웃)에서 다룬다.
- 비밀번호 저장과 요청 제한. [FND-05](#fnd-05-비밀번호-저장과-인증-요청-제한)에서 다룬다.
- JWT와 외부 신원 연동

## FND-05 비밀번호 저장과 인증 요청 제한

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0013 비밀번호 저장과 인증 요청 제한](../adr/auth/0013-p0-password-storage-auth-request-protection.md) |
| HTTP 계약 | [인증 요청 남용 제한](../API.md#인증-요청-남용-제한) |
| 구현 규칙 | [설정과 비밀정보](../CONVENTIONS.md#설정과-비밀정보), [Logging](../CONVENTIONS.md#logging) |
| 선행 | [FND-04](#fnd-04-인증-기반-구성) |
| 소유 경로 | `build.gradle`, `global/**` |

### 산출물

- ADR-0013의 저장 계약을 따르는 `PasswordEncoder` 구성
- 인증 요청 허용량 제한기와 동시 비밀번호 해시 작업 한도
- 제한 초과 응답의 `Retry-After` 전달 경로

### 완료 기준

- `FND-05-AC1` 비밀번호는 `PasswordEncoder` 계약으로만 저장·검증되고 원문이나 빠른 단일 해시를 저장하지 않는다.
- `FND-05-AC2` 같은 원문의 저장 값이 요청마다 다르고 알고리즘 식별자를 포함한다.
- `FND-05-AC3` 허용량과 동시 작업 한도를 넘는 요청은 해시 작업 없이 계약된 오류와 `Retry-After`를 반환한다.
- `FND-05-AC4` 제한 경계, 만료와 초기화 동작의 테스트가 있고 CI를 통과한다.
- `FND-05-AC5` work factor와 동시 작업 한도를 운영 유사 환경에서 측정한 근거를 남긴다. 측정하지 않은 값을 검증 완료로 표현하지 않는다.

### 제외 범위

- 계정 단위 잠금
- 비밀번호 재설정과 계정 복구
- 회원가입·로그인 유스케이스의 입력 검증. 각 기능 ID에서 다룬다.

## FND-06 PostgreSQL 검증 환경

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0002 PostgreSQL 주 데이터베이스](../adr/platform/0002-postgresql-primary-database.md), [ADR-0010 H2와 PostgreSQL 테스트 경계](../adr/platform/0010-h2-postgresql-test-boundary.md) |
| 구현 규칙 | [테스트 작업 안내](../../src/test/AGENTS.md), [마이그레이션 작업 안내](../../src/main/resources/db/migration/AGENTS.md) |
| 실행 명령 | [프로젝트 명령](../COMMANDS.md) |
| 선행 | [FND-03](#fnd-03-스키마와-엔티티-골격) |
| 소유 경로 | `build.gradle`, `.github/workflows/ci.yml`, `docs/COMMANDS.md`, `src/postgresTest/**` |

### 운영 지원 버전 계약

이 절이 운영 지원 PostgreSQL 메이저 버전과 호환성 근거의 단일 정본이며, 현재 선택은 PostgreSQL 18이다.

#### 선택 버전: PostgreSQL 18

- 운영 지원 메이저 버전은 **PostgreSQL 18**로 고정한다. PostgreSQL 공식
  버전 정책에 따라 메이저 버전은 5년 동안 지원되며, 18의 지원 종료일은
  **2030-11-14**다. ([PostgreSQL versioning policy](https://www.postgresql.org/support/versioning/))
- 검증 컨테이너 이미지는 운영 메이저 버전과 같은 `postgres:18.4`로
  고정한다. ([PostgreSQL 18.4 release notes](https://www.postgresql.org/docs/release/18.4/))
- Spring Boot 4.1의 공식 Testcontainers 지원에서
  `PostgreSQLContainer`를 `@ServiceConnection`으로 등록하는 방식을 사용한다.
  ([Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html))
- PostgreSQL JDBC 드라이버는 공식 다운로드 페이지의 PostgreSQL 8.4 이상 지원
  범위에 PostgreSQL 18을 포함한다. ([pgJDBC downloads](https://jdbc.postgresql.org/download/))
- PostgreSQL 컨테이너와 JUnit 5 연동은 Testcontainers 공식 PostgreSQL 모듈과
  JUnit 5 통합 모듈을 사용한다. ([Testcontainers PostgreSQL module](https://java.testcontainers.org/modules/databases/postgres/),
  [Testcontainers JUnit 5 integration](https://java.testcontainers.org/test_framework_integration/junit_5/))

### 산출물

- 이 절의 운영 지원 PostgreSQL 18과 호환성 검토 결과
- Testcontainers 의존성과 PostgreSQL 테스트를 분리 실행하는 Gradle 태스크
- 운영 지원 메이저 버전 계약과 같은 메이저 버전으로 고정한 Testcontainers 이미지 태그
- 초기 마이그레이션 적용과 스키마 검증을 확인하는 PostgreSQL 통합 테스트
- CI에서 이 검증을 실행하는 구성과 실행 명령 문서 갱신

운영 지원 메이저 버전을 바꾸면 이 절의 버전·호환성 근거와 Testcontainers·FND-08 Docker Compose 이미지 태그를 같은 변경에서 갱신한다. `postgresTest`와 FND-08의 로컬 실행·마이그레이션 검증도 모두 통과해야 한다.

### 완료 기준

- `FND-06-AC1` PostgreSQL 컨테이너에서 초기 마이그레이션이 적용되고 스키마 검증이 통과한다.
- `FND-06-AC2` PostgreSQL 검증을 H2 테스트와 분리해 실행할 수 있고 실행 명령이 문서에 있다.
- `FND-06-AC3` CI가 이 검증을 실행하고 실패를 드러낸다.
- `FND-06-AC4` PostgreSQL 전용 SQL·제약과 동시성 동작을 H2 테스트 결과로 대체하지 않는다.
- `FND-06-AC5` 운영 지원 버전 계약의 PostgreSQL 18과 Testcontainers 이미지 `postgres:18.4`가 일치한다.

### 제외 범위

- 기능별 동시성 시나리오 구현. [PART-01](participation.md#part-01-방-참가재참가), [ROOM-06](room.md#room-06-방-상태-정합화)에서 다룬다.
- 운영 데이터베이스 배포와 마이그레이션 실행 위치 변경
- 성능 목표 측정

## FND-07 모듈 구조 검증

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0007 도메인 중심 모듈러 모놀리스](../adr/platform/0007-domain-centered-modular-monolith.md) |
| 구현 규칙 | [패키지와 모듈](../CONVENTIONS.md#패키지와-모듈), [테스트 작업 안내](../../src/test/AGENTS.md) |
| 선행 | [FND-03](#fnd-03-스키마와-엔티티-골격), 업무 모듈 2개 이상 구현, 기존 공개 계약의 `contract` 재배치 |
| 소유 경로 | `build.gradle`, `src/test/**` |
| 정본 변경 | 구조 검사 기준과 현재 정본이 어긋나면 이 작업에서 문서를 함께 바꾸지 않고 결정·문서 변경을 먼저 머지한다. |

### 산출물

- 순환 의존과 다른 업무 모듈의 `contract` 외 패키지 접근을 운영 코드에서 검사하는 구조 테스트
- 컨벤션이 고정한 참조 방향을 그대로 옮긴 허용 의존 관계 정의

### 완료 기준

- `FND-07-AC1` 모듈 간 순환 의존이 있으면 구조 테스트가 실패한다.
- `FND-07-AC2` 업무 모듈의 운영 코드가 다른 업무 모듈의 `contract` 외 패키지를 참조하면 구조 테스트가 실패한다.
- `FND-07-AC3` 허용 의존 관계가 컨벤션의 참조 방향과 일치한다. 어긋나면 구조 테스트 구현을 중단하고 결정·문서 변경을 먼저 반영한다.
- `FND-07-AC4` 구조 테스트가 CI에서 실행된다.

### 제외 범위

- Gradle 멀티모듈 전환과 Spring Modulith 런타임 의존성 도입
- `global`, `infra` 참조 방향 규칙 추가. 컨벤션의 예외 규정을 그대로 따른다.

## FND-08 로컬 개발 PostgreSQL 환경

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 필수 ADR | [ADR-0002 PostgreSQL 주 데이터베이스](../adr/platform/0002-postgresql-primary-database.md), [ADR-0008 Flyway SQL 마이그레이션](../adr/platform/0008-flyway-database-migrations.md), [ADR-0010 H2와 PostgreSQL 테스트 경계](../adr/platform/0010-h2-postgresql-test-boundary.md) |
| 구현 규칙 | [마이그레이션 작업 안내](../../src/main/resources/db/migration/AGENTS.md), [설정과 비밀정보](../CONVENTIONS.md#설정과-비밀정보), [테스트 작업 안내](../../src/test/AGENTS.md) |
| 실행 명령 | [프로젝트 명령](../COMMANDS.md) |
| 선행 | [FND-03](#fnd-03-스키마와-엔티티-골격), [FND-06-AC5 운영 지원 버전 계약](#운영-지원-버전-계약) |
| 소유 경로 | `compose.local.yml`, `.env.example`, `.gitignore`, `src/main/resources/application-local.yml`, `docs/COMMANDS.md` |

운영 지원 버전 계약·Testcontainers·Docker Compose의 PostgreSQL 메이저 버전이 다르면 추측하지 않는다. 외부 변경에 대한 명시적 승인이 있으면 사용자가 승인한 특정 이슈에 `DECISION_NEEDED` 라벨과 중단 사유 코멘트를 남기고, 구현 요청만 있거나 게시 승인이 없으면 확인된 사실과 필요한 결정을 사용자에게 보고한다.

### 산출물

- `FND-06-AC5` 운영 지원 버전 계약과 같은 PostgreSQL 메이저 버전을 사용하는 로컬 개발용 Docker Compose 서비스
- 준비 상태를 확인하는 health check와 일반적인 재시작에서 데이터를 유지하는 named volume
- 환경변수로 데이터소스 연결값을 받는 `local` 프로필과 실제 `.env`를 제외하는 Git 설정
- 로컬 데이터베이스 실행·상태 확인·애플리케이션 실행·종료·명시적 데이터 초기화 명령

### 완료 기준

- `FND-08-AC1` `docker compose -f compose.local.yml up -d`로 운영 지원 PostgreSQL 메이저 버전과 같은 버전을 실행하고 health check로 준비 상태를 확인할 수 있다.
- `FND-08-AC2` `local` 프로필에서 환경변수로 주입한 데이터소스 설정을 사용해 애플리케이션이 시작된다.
- `FND-08-AC3` 빈 로컬 데이터베이스에 전체 Flyway 마이그레이션이 적용되고 Hibernate 스키마 검증이 통과한다.
- `FND-08-AC4` Docker named volume을 사용해 일반적인 컨테이너 재시작 뒤에도 개발 데이터가 유지된다.
- `FND-08-AC5` `.env.example`에는 필요한 키와 로컬 예시값만 있고, 실제 `.env`와 비밀정보는 Git 추적 대상에서 제외된다.
- `FND-08-AC6` 로컬 데이터베이스 실행·상태 확인·애플리케이션 실행·종료·명시적 데이터 초기화 명령이 문서화되고 그대로 재현된다.
- `FND-08-AC7` H2 기반 `test`와 FND-06의 `postgresTest`가 로컬 프로필이나 외부 데이터소스 환경변수에 오염되지 않고 CI를 통과한다.

### 제외 범위

- Testcontainers, `postgresTest`와 CI PostgreSQL 검증 구성. [FND-06](#fnd-06-postgresql-검증-환경)에서 다룬다.
- 운영 데이터베이스 배포·백업·복구·모니터링과 운영 비밀정보
- 애플리케이션 컨테이너 이미지와 전체 서비스 Docker Compose 구성
- 팀이 공유하는 장기 실행 개발 데이터베이스
