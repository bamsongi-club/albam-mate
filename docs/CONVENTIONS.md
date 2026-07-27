# Albam Mate Conventions

이 문서는 Albam Mate 백엔드 코드를 작성하고 리뷰할 때 적용하는 기본 규칙이다. 제품 동작은 [P0 공통 명세](P0-spec.md)와 여기에서 연결한 [기능별 명세](P0-spec.md#관련-문서), 요청·응답은 [API 명세](API.md), 데이터 구조는 [ERD](ERD.md), 되돌리기 어렵거나 논쟁적인 기술 선택은 [ADR](adr/README.md)이 우선한다. 이 문서는 해당 정본을 반복하지 않고 구현 방식을 통일한다.

AI 에이전트는 코드 구현·리뷰·커밋 작업을 시작할 때 이 문서를 확인하며, 같은 작업 안에서는 변경 영역과 관련된 절을 우선 적용한다.

## 패키지와 모듈

코드는 전역 계층이 아니라 업무 도메인별로 묶는다.

~~~text
cloud.bamsongi.albammate
├─ auth
├─ user
├─ game
├─ room
├─ global
└─ infra
~~~

각 업무 패키지는 논리적 모듈이다. 필요한 구현만 만들며 빈 계층 패키지를 미리 생성하지 않는다.

~~~text
room
├─ controller
├─ service
├─ repository
├─ dto
└─ entity
~~~

- 모듈의 하위 패키지는 내부 구현이 기본이다.
- 다른 모듈의 `repository`, `entity`와 HTTP 요청·응답 DTO를 직접 참조하지 않는다.
- 모듈 간 호출이 필요하면 도메인 루트에 의도적으로 공개한 애플리케이션 서비스·인터페이스 또는 이벤트를 사용한다.
- 여러 도메인을 조합하는 유스케이스는 그 흐름을 소유한 도메인에 둔다. 모든 횡단 흐름을 공통 Facade 하나에 모으지 않는다.
- 모듈 간 순환 의존을 허용하지 않는다. 업무 모듈의 참조 방향은 `room → game`과 `room·auth → user`로 고정한다.
- 참조가 허용되지 않는 방향의 협력이 필요하면 참조할 수 없는 모듈이 인터페이스를 정의하고 참조할 수 있는 모듈이 구현한다.
- 참가 관계는 방의 정원·상태 불변식과 한 트랜잭션에서 함께 바뀌므로 `room`이 소유한다([ERD 서비스 규칙](ERD.md#서비스-규칙)). 별도 `participation` 모듈을 만들지 않는다.
- 업무 모듈은 `global`이 공개한 기술 계약을 참조할 수 있으며 이 참조는 방향 규칙에서 제외한다. 인증 사용자 식별처럼 여러 모듈이 쓰는 기술 계약은 업무 모듈이 아니라 `global`에 둔다.
- `global`에는 공통 설정, 예외 응답, 보안 기반 설정처럼 업무 의미가 없는 기술 요소만 둔다. 인증 업무 로직은 `auth`가 소유한다.
- 외부 시스템 연동과 기술 어댑터는 `infra`에 둔다. 한 도메인에서만 사용하는 어댑터는 해당 도메인 안에 둔다.
- `global`과 `infra`를 여러 도메인의 Entity, DTO 또는 업무 규칙을 공유하는 우회 경로로 사용하지 않는다.

세부 기준은 [ADR-0007](adr/platform/0007-domain-centered-modular-monolith.md)을 따른다.

## 네이밍

### 클래스

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| Controller | `{Domain}Controller` | `RoomController` |
| Service | `{Domain}Service` 또는 유스케이스를 드러내는 이름 | `RoomService`, `RoomParticipationService` |
| Repository | `{Entity}Repository` | `RoomRepository` |
| Entity | 단수 명사 | `Room`, `Participation` |
| Enum | `{Domain}Status` 또는 `{Domain}Type` | `RoomStatus`, `RoomType` |
| Request DTO | `{Action}{Domain}Request` | `CreateRoomRequest` |
| Response DTO | 응답 의미가 드러나는 이름 | `RoomDetailResponse` |
| Exception | `{Domain}Exception` 또는 구체적인 실패 이름 | `RoomException`, `CapacityExceededException` |

### 메서드

메서드명은 행위를 구체적으로 표현한다.

- 권장: `createRoom()`, `joinRoom()`, `cancelParticipation()`
- 피한다: `process()`, `handle()`, `doWork()`, `updateData()`, `check()`
- 프레임워크 콜백처럼 역할이 이미 분명한 경우에는 `handleWebhook()`처럼 관용적인 이름을 사용할 수 있다.

### 변수

의미를 잃는 축약어를 사용하지 않는다.

- 권장: `currentUserId`, `remainingRecruitmentSeats`
- 피한다: `uid`, `cnt`, `val`

## Controller

Controller는 HTTP 요청과 응답의 경계만 담당한다.

- Request DTO 검증
- 인증된 사용자 식별
- Service 호출
- API 명세에 맞는 Response DTO와 HTTP 상태 반환

Controller에는 다음 책임을 두지 않는다.

- 비즈니스 규칙과 상태 전이
- Entity 직접 조립 또는 반환
- Repository 직접 호출
- 트랜잭션 시작
- 외부 API 직접 호출

의존성은 Lombok의 `@RequiredArgsConstructor`와 `private final` 필드 또는 명시적인 생성자로 주입한다. `@Autowired` 필드 주입은 사용하지 않는다.

기존 리소스 표현의 일부를 수정하는 엔드포인트는 `@PatchMapping`을 사용한다. 클라이언트가 리소스 표현 전체를 결정해 교체하는 경우에만 `@PutMapping`을 사용하며, 세부 기준은 [ADR-0016](adr/platform/0016-p0-update-api-http-method.md)을 따른다.

## Service

- 의존성이 있는 Service는 Lombok의 `@RequiredArgsConstructor`와 `private final` 필드로 생성자 주입한다. 생성자에서 별도 검증이나 가공이 필요할 때만 생성자를 명시한다.
- public 메서드는 하나의 유스케이스를 표현한다.
- 트랜잭션 경계는 Service 계층에 둔다. 저장 상태를 변경하지 않는 조회는 `@Transactional(readOnly = true)`, 상태 변경은 `@Transactional`을 사용한다. 조회 전 상태 보정처럼 계약상 쓰기가 필요한 조회 유스케이스는 Transaction 절의 예외 규칙을 따른다.
- Service는 자기 모듈의 Repository만 직접 참조한다.
- 다른 모듈과 협력할 때는 그 모듈이 공개한 계약만 호출한다.
- 외부 시스템 호출은 Client 또는 Adapter로 분리한다.
- 상태 변경의 핵심 불변식은 Service와 Entity가 표현하고, 데이터베이스 제약으로도 방어할 수 있는 규칙은 함께 적용한다.

## Repository

- Spring Data JPA 인터페이스를 기본으로 사용한다.
- 조회 의도가 메서드명으로 명확하지 않으면 `@Query` 등으로 쿼리를 명시하고 테스트한다.
- Repository에 비즈니스 정책이나 HTTP 응답 변환을 넣지 않는다.
- 다른 모듈에 Repository를 공개하지 않는다.

## 데이터베이스 변경

- 테이블, 컬럼, 인덱스, 제약조건 또는 기준 데이터를 변경하는 PR은 Flyway 마이그레이션을 포함한다.
- 마이그레이션은 `src/main/resources/db/migration`에 `V<version>__<description>.sql` 형식으로 둔다.
- 초기 스키마 이후 추가하는 마이그레이션의 버전은 최신 기본 브랜치를 반영한 뒤 저장소의 기존 버전보다 크게 부여한다. 동시에 열린 PR 사이에 중복이나 순서 역전이 생기면 나중에 병합하는 PR이 병합 전에 버전을 다시 부여한다.
- 공유 환경에 한 번이라도 적용된 버전 마이그레이션은 수정하지 않는다. 보정이 필요하면 새 버전 파일을 추가한다.
- JPA Entity 변경은 마이그레이션을 대신하지 않는다. 두 표현이 함께 바뀌면 같은 변경에서 일치시킨다.
- 공유 개발·검증·운영 환경에서 Hibernate `ddl-auto=create` 또는 `update`로 스키마를 변경하지 않는다.
- PostgreSQL 전용 SQL, 제약과 Flyway 실행 결과는 H2 테스트만으로 검증됐다고 보지 않는다.

세부 기준은 [ADR-0008](adr/platform/0008-flyway-database-migrations.md)을 따른다.

## Entity와 DTO

- Entity와 API DTO를 분리하며 Entity를 HTTP 응답으로 직접 반환하지 않는다.
- Entity에 범용 setter를 늘리지 않고 `join()`, `cancel()`처럼 의미 있는 상태 변경 메서드를 둔다.
- Request와 Response DTO는 용도별로 분리한다.
- HTTP DTO와 Entity는 모듈 간 계약으로 사용하지 않는다.
- DTO 검증은 입력 경계에서, 도메인 불변식은 Service와 Entity에서 처리한다.

## 예외 처리

- 도메인 실패는 구체적인 도메인 예외 또는 공통 `BusinessException` 계층으로 표현한다.
- HTTP 오류 변환은 `@RestControllerAdvice`에서 일관되게 처리한다.
- `@Valid`나 도메인 검증이 이미 보장하는 실패를 의미 없는 `try-catch`로 다시 감싸지 않는다.
- 예외를 삼키지 않는다. 복구할 수 없다면 원인과 문맥을 보존해 상위 경계로 전달한다.

## API 응답

[API 명세](API.md)의 공통 계약을 따른다.

- 성공 응답은 `status`와 `data`를 반환한다.
- 실패 응답은 `status`, `code`, `message`와 `data: null`을 반환한다.
- 응답의 `status`는 실제 HTTP 상태 코드와 일치해야 한다.
- API 오류 코드를 예외 클래스명이나 로그 메시지로 즉석에서 만들지 않는다.
- API 계약을 변경하면 구현과 같은 변경에서 `docs/API.md`를 갱신한다.

## Validation

- Request DTO는 `@Valid`와 Bean Validation으로 형식·범위·필수값을 검증한다.
- 인증 사용자와 요청 리소스의 소유·참가 관계를 Service에서 검증한다.
- 정원 초과, 중복 참가와 참조 무결성처럼 동시 요청에서도 지켜야 하는 규칙은 애플리케이션 검증만으로 끝내지 않고 트랜잭션과 데이터베이스 제약을 함께 사용한다.
- 검증 순서와 오류 우선순위가 API 계약에 정의돼 있으면 그 순서를 테스트한다.

## Transaction

- 트랜잭션은 Service에서 시작한다.
- 저장 상태를 변경하지 않는 읽기 전용 유스케이스에는 `readOnly = true`를 사용한다.
- [ADR-0012](adr/room/0012-room-request-boundary-state-reconciliation.md)처럼 조회 전에 저장 상태를 보정해야 하는 유스케이스는 읽기 전용 트랜잭션의 더티 체킹에 의존하지 않는다. 트랜잭션 없는 재시도 조정자가 별도 상태 보정 실행 서비스를 호출하고, 실행 서비스는 시도마다 독립된 쓰기 트랜잭션에서 조건부 갱신한다. 보정이 커밋된 뒤 읽기 전용 조회 서비스가 최신 상태를 읽는다.
- 상태 변경 트랜잭션 안에서는 JPA 더티 체킹을 기본으로 사용한다.
- 즉시 반영이 필요한 이유가 없으면 `saveAndFlush()`를 반복 호출하지 않는다.
- 외부 API 호출을 데이터베이스 트랜잭션 안에 오래 포함하지 않는다. 외부 성공 후 내부 실패 또는 내부 성공 후 외부 실패를 어떻게 처리할지 먼저 정한다.

## 설정과 비밀정보

- 환경에 따라 바뀌는 값은 `application.yml`, 환경변수 또는 `@ConfigurationProperties`로 주입한다.
- 비밀번호, 세션 식별자, API 키와 secret은 저장소의 설정 파일에 넣지 않는다.
- 비밀번호는 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)의 `PasswordEncoder` 계약으로만 저장·검증하고 원문이나 빠른 단일 해시를 저장하지 않는다.
- P0 공통·기능별 명세에 고정된 업무 규칙을 운영 설정으로 조용히 바꾸지 않는다. 설정 가능하게 바꿀 필요가 있으면 명세와 기본값을 함께 갱신한다.

## Logging

- 비밀번호, 세션 식별자, 인증정보, secret과 불필요한 개인정보를 로그나 오류 응답에 남기지 않는다.
- 정상 흐름을 `ERROR`로 기록하지 않고, 장애 분석에 필요한 식별자와 문맥을 적절한 레벨로 남긴다.
- 같은 예외를 여러 계층에서 중복 기록하지 않는다.

## 테스트

- 단위 테스트는 JUnit 5와 Mockito를 사용하고 given-when-then 흐름이 드러나게 작성한다.
- Service 단위 테스트는 자기 모듈의 Repository와 외부 의존성을 목킹한다.
- Controller 테스트는 HTTP 상태, 요청 검증, 인증 경계와 응답 계약을 확인한다.
- `@SpringBootTest`는 전체 Spring 구성이 필요한 통합 경로에 사용한다.
- 새 Service와 Controller에는 성공 경로와 핵심 실패 경로 테스트를 함께 작성한다.
- 테스트 클래스명은 `대상클래스 + Test`로 짓고, 테스트 메서드명은 `방을_생성하면_모집중_상태가_된다()`처럼 행동과 결과를 드러낸다.
- PostgreSQL 전용 SQL·제약·동시성 동작과 Flyway 마이그레이션은 실제 PostgreSQL을 사용하는 통합 환경에서 확인한다.
- 모듈이 둘 이상 구현되면 순환 의존과 다른 모듈 내부 패키지 접근을 구조 테스트로 검사한다.

## 시간 처리

- 저장, 비교와 로그의 기준 시각은 UTC다.
- 타임라인 위의 한 순간은 Entity에서 `Instant`, PostgreSQL에서 `TIMESTAMPTZ`로 표현한다.
- `startsAt`, `createdAt`, `updatedAt`, `joinedAt`, `canceledAt`과 세션 만료 시각에 `LocalDateTime`을 사용하지 않는다.
- API는 오프셋이 포함된 ISO 8601 값을 받고, P0 응답은 `Asia/Seoul` 기준 `+09:00`으로 반환한다.
- 현재 시각은 주입받은 `Clock`으로 얻는다. 운영은 `Clock.systemUTC()`, 테스트는 `Clock.fixed(...)`를 사용한다.
- 테스트 fixture의 시각은 고정값을 사용한다.
- JVM, 컨테이너와 PostgreSQL 연결의 시간대를 UTC로 명시하고 시스템 기본 시간대에 의존하지 않는다.

세부 기준은 [ADR-0009](adr/platform/0009-utc-time-standard.md)을 따른다.

## Javadoc과 주석

코드를 그대로 번역하지 말고 이름만으로 드러나지 않는 이유와 계약을 기록한다.
Javadoc과 설명 주석은 한국어로 작성하며, 코드 식별자와 기술 용어는 코드와 공식 문서의 표기를 유지한다.

다음에는 Javadoc이나 설명 주석을 남긴다.

- 공개 Service·모듈 API의 유스케이스 의도와 주요 부수효과
- 도메인 규칙이 있는 Entity 변경 메서드
- 복잡한 상태 전이, 계산과 동시성 처리의 이유
- 외부 연동 Client의 실패 계약

getter, setter, 자명한 위임과 Controller 매핑에는 기계적인 Javadoc을 붙이지 않는다.

## 과한 설계 제한

현재 요구가 단순한 Spring Boot, JPA와 PostgreSQL 안에서 해결된다면 그 구조를 유지한다. 측정된 필요 없이 MSA, Kafka, Redis 분산 락, CQRS, Event Sourcing 또는 복잡한 DDD 전술 패턴을 먼저 도입하지 않는다. 새 기술이 필요하면 해결할 문제, 대안과 재검토 조건을 ADR에 기록한다.

## 협업 개발

여러 이슈를 동시에 진행할 때 적용한다. 각 이슈는 이슈에 선언한 소유 경로만 변경한다.

공유 파일은 공통 응답·오류 계약, 보안 설정, 빌드·환경 설정과 Flyway 마이그레이션처럼 여러 이슈가 함께 사용하는 파일이다.

- 공유 파일은 이슈에서 접촉을 선언한 항목만 편집한다.
- 공유 파일 편집은 추가만 한다. 기존 항목의 순서 변경, 정렬과 리네임을 함께 하지 않는다.
- 공유 파일만 변경하는 PR을 먼저 머지하고 도메인 PR은 그 뒤에 rebase한다.
- 한 도메인 패키지를 여러 기능 ID가 나눠 가질 때는 소유를 `<module>/**`가 아니라 담당 클래스 목록으로 선언한다. 참가 관계를 `room`이 소유하는 것처럼 한 모듈에 여러 기능이 들어가는 경우가 여기에 해당한다.
- 브랜치는 매일 최신 기본 브랜치로 rebase하고 PR을 3일 이상 열어두지 않는다.

다음에 해당하면 추측으로 진행하지 않고 이슈에 `DECISION_NEEDED` 라벨과 중단 사유 코멘트를 남긴 뒤 결정을 기다린다.

- 정본 문서 간 계약이 서로 충돌한다.
- 선행 이슈가 제공해야 할 공개 계약이 아직 없다.
- 이슈에서 선언하지 않은 공유 파일을 수정해야 한다.
- 다른 이슈와 구현 책임이 겹친다.

정본 변경이 필요하면 문서 변경을 먼저 반영하고 그 변경을 이슈에 연결한다.

## 브랜치

- 작업 브랜치 이름은 `<type>/issue-<이슈 번호>-<요약>` 형식을 사용한다.
- `<type>`은 `feature`, `fix`, `refactor`, `docs`, `test`, `chore`, `ci` 중에서 선택한다.
- 요약은 작업 결과를 나타내는 영문 소문자 kebab-case로 작성한다.
- PR을 생성할 브랜치에는 이슈 번호를 반드시 포함한다.
- 하나의 브랜치는 하나의 이슈와 하나의 PR에만 사용하며, 머지된 브랜치는 재사용하지 않는다.
- 작업자 이름이나 도구 이름을 브랜치명에 포함하지 않는다.

예시는 다음과 같다.

- `feature/issue-14-common-response`
- `fix/issue-27-expired-room-status`
- `docs/issue-31-auth-api-contract`
- `chore/issue-35-update-gradle-wrapper`

## 커밋

- 커밋은 리뷰 가능한 하나의 변경 목적만 담는다.
- 커밋 메시지는 `type: 한국어 subject` 형식으로 통일한다.
- `type`은 영문 소문자로 작성하며 아래 기준에서 선택한다.
- `scope`는 사용하지 않는다.
- `subject`는 변경 내용을 짧고 명확한 한국어로 작성하고 끝에 마침표를 붙이지 않는다.
- 문서만 변경한 커밋은 `docs`를 사용한다.
- 코드 동작에 영향을 주지 않는 설정, 패키지 관리와 파일 정리는 `chore`를 사용한다.
- 구현, 테스트, 문서와 리팩터링 목적이 서로 독립적이면 커밋도 분리한다.
- 최종 브랜치에 WIP 또는 임시 커밋을 남기지 않는다.

| type | 사용 기준 |
| --- | --- |
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | README, API 문서 등 문서만 수정 |
| `style` | 코드 동작에 영향을 주지 않는 포맷 수정 |
| `refactor` | 기능 변경이나 버그 수정이 없는 코드 구조 개선 |
| `test` | 테스트 코드 추가 또는 수정 |
| `ci` | CI/CD 설정 변경 |
| `chore` | 설정, 패키지 관리, 파일 정리 등 기타 작업 |

### 예시

~~~text
feat: 방 생성 기능 추가
fix: 세션 만료 시 로그인 화면으로 이동하지 않는 문제 수정
docs: 프로젝트 실행 방법 추가
refactor: 회원 조회 로직 분리
test: 방 생성 서비스 테스트 추가
chore: 사용하지 않는 의존성 제거
ci: GitHub Actions 검증 워크플로 추가
~~~
