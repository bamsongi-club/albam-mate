# Albam Mate Conventions

이 문서는 Albam Mate 백엔드 코드의 작성·리뷰 규칙이다. 다음 정본이 이 문서보다 우선한다.

- 백엔드 구조·모듈 책임·의존 흐름: [아키텍처 문서](ARCHITECTURE.md)
- 제품 동작: [P1 공통 명세](P1-spec.md)와 [P1 기능별 명세](p1/README.md). P0 완료 범위는 [P0 아카이브](archive/p0/README.md)
- 요청·응답: [API 명세](API.md)
- 데이터 구조: [ERD](ERD.md)
- 되돌리기 어렵거나 논쟁적인 기술 선택: [ADR](adr/README.md)

이 문서는 정본을 반복하지 않고 구현 방식을 통일한다.

AI 에이전트는 [루트 작업 안내](../AGENTS.md)의 라우팅에 따라 이 문서와 변경 위치의 `AGENTS.md`에서 필요한 규약만 확인한다.

## 패키지와 모듈

백엔드의 모듈, 패키지, 공개 계약과 의존 흐름은 [아키텍처 문서](ARCHITECTURE.md)를 따른다. 코드는 전역 계층이 아니라 업무 도메인별로 묶고, 각 업무 패키지를 논리적 모듈로 취급한다.

- 필요한 구현만 만들며 빈 계층 패키지를 미리 생성하지 않는다.
- 업무 모듈은 다른 모듈에 공개할 계약을 `<module>/contract`에 둔다. 모듈 간 호출 인터페이스와 그 입·출력으로 쓰는 값 타입·이벤트만 포함한다.
- `contract` 외의 하위 패키지와 도메인 루트의 클래스는 모듈 내부 구현이다. 다른 모듈은 이를 직접 참조하지 않는다.
- `<module>/dto`는 HTTP 요청·응답 전용이며 모듈 간 계약으로 사용하지 않는다.
- 다른 모듈이 호출하는 유스케이스는 `contract`의 인터페이스로 공개하고 구체 서비스는 내부 패키지에 둔다.
- 여러 도메인을 조합하는 유스케이스는 그 흐름을 소유한 도메인에 둔다. 모든 횡단 흐름을 공통 Facade 하나에 모으지 않는다.
- 모듈 간 순환 의존을 허용하지 않으며, 허용된 참조 방향은 아키텍처 문서에 명시한다.
- 참조가 허용되지 않는 방향의 협력이 필요하면 호출 측 모듈의 `contract`에 포트 인터페이스를 정의하고, 허용된 참조 방향의 모듈에 구현을 둔다.
- 업무 모듈은 `global`이 공개한 기술 계약을 참조할 수 있으며 이 참조는 방향 규칙에서 제외한다. 인증 사용자 식별처럼 여러 모듈이 쓰는 기술 계약은 업무 모듈이 아니라 `global`에 둔다.
- `global`에는 공통 설정, 예외 응답, 보안 기반 설정처럼 업무 의미가 없는 기술 요소만 둔다. 인증 업무 로직은 `auth`가 소유한다.
- `global`과 `infra`를 여러 도메인의 Entity, DTO 또는 업무 규칙을 공유하는 우회 경로로 사용하지 않는다.

구조 선택의 근거와 대안은 [ADR-0007](adr/platform/0007-domain-centered-modular-monolith.md)을 따른다.

## 네이밍

식별자의 대소문자 형식 같은 기계적 규칙은
[`config/checkstyle`](../config/checkstyle/README.md)이 검사한다. 이 절에는 도구가
판단할 수 없는 역할, 의도와 의미 규칙만 둔다.

### 클래스

| 대상 | 규칙 | 예시 |
| --- | --- | --- |
| Controller | `{Domain}Controller` | `RoomController` |
| Service | `{Domain}Service` 또는 유스케이스를 드러내는 이름 | `RoomService`, `RoomParticipationService` |
| 공개 조회 계약 | 조회 의도를 드러내는 `{Domain}Query` | `GameQuery`, `UserQuery` |
| 공개 변경 계약 | 유스케이스를 드러내는 `{UseCase}Service` | `UserAccountService` |
| Repository | `{Entity}Repository` | `RoomRepository` |
| Entity | 단수 명사 | `Room`, `Participation` |
| Enum | `{Domain}Status` 또는 `{Domain}Type` | `RoomStatus`, `RoomType` |
| Request DTO | `{Action}{Domain}Request` | `CreateRoomRequest` |
| Response DTO | 응답 의미가 드러나는 이름 | `RoomDetailResponse` |
| Exception | `{Domain}Exception` 또는 구체적인 실패 이름 | `RoomException`, `CapacityExceededException` |

공개 계약과 구현체 이름이 충돌하면 `Impl` 대신 구체적인 역할을 붙인다. 예: `contract.UserAccountService`의 기본 구현은 `service.UserAccountApplicationService`다.

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

Controller의 분류와 담당 API는 [아키텍처의 Controller Interface](ARCHITECTURE.md#controller-interface)를 따른다.

Controller는 도메인의 애플리케이션 진입 Service만 호출하고 내부 ReadService·Executor를 직접 참조하지 않는다.

Controller는 다음 책임만 담당한다.

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

생산 코드는 `@RequiredArgsConstructor`와 `private final` 필드 또는 명시적인 생성자로만 의존성을 주입한다. `@Autowired`는 사용하지 않으며, 테스트의 Spring TestContext fixture 주입에만 허용한다.

주입 형식은 다음 두 가지만 사용하고 한 클래스에서 섞지 않는다.

- 대입만 하는 생성자는 `@RequiredArgsConstructor`와 주입 필드별 `lombok.NonNull`의 `@NonNull`을 쓴다. 생성된 생성자가 null을 검사하므로 별도 방어 코드는 쓰지 않는다.
- 검증·파생값 계산이 필요하면 생성자를 명시하고 `Objects.requireNonNull`이나 인자 검증 메서드로 직접 검사한다. Lombok이 생성하지 않는 생성자에는 검사가 삽입되지 않으므로 필드에 `@NonNull`을 붙이지 않는다.

`@NonNull`은 런타임 검사이며 정적 분석용 nullness 어노테이션이나 Bean Validation의 `@NotNull`과 목적이 다르다. 임포트는 항상 `lombok.NonNull`을 사용한다.

기존 리소스 표현의 일부를 수정하는 엔드포인트는 `@PatchMapping`을 사용한다. 클라이언트가 리소스 표현 전체를 결정해 교체하거나, 경로와 메서드만으로 전체 목표 상태가 결정되는 관계 리소스의 존재를 멱등하게 설정할 때 `@PutMapping`을 사용한다. 세부 기준은 [ADR-0047](adr/platform/0047-http-method-and-target-state-idempotency.md)을 따른다.

## Service

`room`의 Service·Executor·Coordinator 책임과 가시성은 [아키텍처 문서](ARCHITECTURE.md#service와-내부-협력자)를 따른다.

- Service도 위의 생성자 주입 규칙을 따르며, 검증·가공이 필요할 때만 생성자를 명시한다.
- public 메서드는 하나의 유스케이스를 표현한다.
- 트랜잭션 경계는 Service 계층에 둔다. 저장 상태를 변경하지 않는 조회는 `@Transactional(readOnly = true)`, 상태 변경은 `@Transactional`을 사용한다. 조회 전 상태 보정처럼 계약상 쓰기가 필요한 조회 유스케이스는 Transaction 절의 예외 규칙을 따른다.
- Service는 자기 모듈의 Repository만 직접 참조한다.
- 외부 시스템 호출은 Client 또는 Adapter로 분리한다.
- 상태 변경의 핵심 불변식은 Service와 Entity가 표현하고, 데이터베이스 제약으로도 방어할 수 있는 규칙은 함께 적용한다.

## Repository

- Spring Data JPA 인터페이스를 기본으로 사용한다.
- 조회 의도가 메서드명으로 명확하지 않으면 `@Query` 등으로 쿼리를 명시하고 테스트한다.
- Repository에 비즈니스 정책이나 HTTP 응답 변환을 넣지 않는다.
- 다른 모듈에 Repository를 공개하지 않는다.

## 데이터베이스 변경

Flyway 마이그레이션 작업 규약의 정본은 [마이그레이션 작업 안내](../src/main/resources/db/migration/AGENTS.md)다.

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
- 현재 제공 항목과 목표 항목을 같은 상세 표에 두면 [도입 단계와 제공 상태](API.md#도입-단계와-제공-상태)를 행마다 구분한다. 구현과 계약 검증을 완료해 제공 상태를 바꿀 때도 도입 단계는 유지한다.

## Validation

- Request DTO는 `@Valid`와 Bean Validation으로 형식·범위·필수값을 검증한다.
- 인증 사용자와 요청 리소스의 소유·참가 관계를 Service에서 검증한다.
- 정원 초과, 중복 참가와 참조 무결성처럼 동시 요청에서도 지켜야 하는 규칙은 애플리케이션 검증만으로 끝내지 않고 트랜잭션과 데이터베이스 제약을 함께 사용한다.
- 검증 순서와 오류 우선순위가 API 계약에 정의돼 있으면 그 순서를 테스트한다.

## Transaction

- 트랜잭션은 Service에서 시작한다.
- 저장 상태를 변경하지 않는 읽기 전용 유스케이스에는 `readOnly = true`를 사용한다.
- 목록·내 모임 조회는 [ADR-0055](adr/room/0055-room-query-effective-status-and-persistence-correction.md)의 고정된 `requestTime` 유효 상태를 [ADR-0056](adr/room/0056-postgresql-room-query-snapshot-without-global-pre-correction.md)의 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ` snapshot에서 읽고 전역 저장 보정을 수행하지 않는다. 이 조회는 대상 ROOM 보정 충돌의 `ROOM_CONCURRENT_MODIFICATION`을 반환하지 않는다. 현재 구현·검증 상태는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)을 따른다. 대상 ROOM의 현재 저장 상태를 보정하는 상세·상태 의존 명령·대기·채팅 접근은 읽기 전용 트랜잭션의 더티 체킹에 의존하지 않고 [아키텍처의 방 조회 흐름](ARCHITECTURE.md#방-조회)과 ADR-0055를 따른다.
- 상태 변경 트랜잭션 안에서는 JPA 더티 체킹을 기본으로 사용한다.
- 즉시 반영이 필요한 이유가 없으면 `saveAndFlush()`를 반복 호출하지 않는다.
- 외부 API는 원칙적으로 데이터베이스 트랜잭션 밖에서 호출한다. 불가피하게 함께 조정해야 하면 트랜잭션 범위를 최소화하고 외부 성공 후 내부 실패 또는 내부 성공 후 외부 실패를 어떻게 처리할지 먼저 정한다.

## 설정과 비밀정보

- 환경에 따라 바뀌는 값은 `application.yml`, 환경변수 또는 `@ConfigurationProperties`로 주입한다.
- 비밀번호, 세션 식별자, API 키와 secret은 저장소의 설정 파일에 넣지 않는다.
- 비밀번호는 [ADR-0013](adr/auth/0013-p0-password-storage-auth-request-protection.md)의 `PasswordEncoder` 계약으로만 저장·검증하고 원문이나 빠른 단일 해시를 저장하지 않는다.
- 운영 설정은 명세 변경 없이 P0 공통·기능별 명세에 고정된 업무 규칙을 바꾸지 않는다. 설정 가능하게 바꿀 필요가 있으면 명세와 기본값을 같은 변경에서 갱신한다.

## Logging

- 비밀번호, 세션 식별자, 인증정보, secret과 불필요한 개인정보를 로그나 오류 응답에 남기지 않는다.

장애 분석에 필요한 식별자와 문맥은 다음 판정 기준에 맞는 레벨로 남긴다.

| 레벨 | 판정 기준 |
| --- | --- |
| `ERROR` | 예기치 않은 장애로 요청·작업을 완료하지 못해 운영자 조사가 필요하다. 정상 흐름과 계약된 업무 거절은 기록하지 않는다. |
| `WARN` | 처리는 계속할 수 있지만 재시도 소진, 대체 경로 사용, 외부 의존성 저하처럼 운영 확인이 필요한 비정상 상태다. |
| `INFO` | 기동·종료와 운영상 추적할 가치가 있는 정상 상태 변화다. 반복되는 정상 요청마다 남기지 않는다. |

- 같은 예외를 여러 계층에서 중복 기록하지 않는다.

## 테스트

공통 테스트 작성·배치 규약의 정본은 [일반 테스트 작업 안내](../src/test/AGENTS.md), PostgreSQL 전용 규약의 정본은 [PostgreSQL 테스트 작업 안내](../src/postgresTest/AGENTS.md)다.

## 시간 처리

- 저장, 비교와 로그의 기준 시각은 UTC다.
- 타임라인 위의 한 순간은 Entity에서 `Instant`, PostgreSQL에서 `TIMESTAMPTZ`로 표현한다.
- `startsAt`, `createdAt`, `updatedAt`, `joinedAt`, `canceledAt`과 세션 만료 시각에 `LocalDateTime`을 사용하지 않는다.
- API는 오프셋이 포함된 ISO 8601 값을 받고, P0 응답은 `Asia/Seoul` 기준 `+09:00`으로 반환한다.
- 현재 시각은 주입받은 `Clock`으로 얻고 운영은 `Clock.systemUTC()`를 사용한다.
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

### 백엔드 변경 전달 경로

백엔드 기능·버그 수정은 변경 줄 수나 리뷰 코멘트 심각도가 아니라 계약과 위험 경계로 다음 두 경로 중 하나를 선택한다. 두 경로는 순서대로 거치는 단계가 아니며, 작업 중 범위가 넓어지면 다시 분류한다. 에이전트가 따르는 세부 절차의 정본은 [backend-delivery](../.agents/skills/backend-delivery/SKILL.md)다.

| 경로 | 선택 기준 | 전달·검증 범위 |
| --- | --- | --- |
| [`review-fast`](../.agents/skills/backend-delivery/references/review-fast.md) | 승인된 이슈 또는 기존 PR 계약 안에서 한 동작 영역의 직접 관련 경로·테스트만 바꾸고, 관찰 가능한 동작과 테스트 의도를 확대하지 않는다. 인증·인가·CSRF·개인정보·공개 API, DB 스키마·데이터 보정, 트랜잭션·동시성, 모듈 경계, 의존성·빌드·운영 설정은 건드리지 않는다. | 승인된 테스트 ID를 유지한 좁은 Red/Green, 대상 test manifest와 변경 범위 검증을 수행하고 전체 회귀는 CI에 맡긴다. |
| [`full-delivery`](../.agents/skills/backend-delivery/references/full-delivery.md) | 새 계약이 필요하거나 `review-fast` 조건을 하나라도 만족하지 못한다. 위 고위험 경계를 바꾸거나 진행 중 미선언 공유 파일·정본 충돌·범위 확대가 확인된 경우도 포함한다. | 사람이 승인한 전체 테스트 ID와 파일 소유 경계를 고정하고 구현·테스트를 분리한다. PR 요청 시 Draft의 고정 head에서 대상 테스트, 일반 리뷰와 CI를 확인한 뒤 Ready 전환 여부를 판단한다. |

문서·프런트엔드 작업, 읽기 전용 리뷰와 답글·스레드 해결만 필요한 요청은 이 두 백엔드 구현 경로의 대상이 아니다.

공통 응답·오류 계약, 보안, 빌드·환경 설정과 Flyway 마이그레이션처럼 여러 이슈가 함께 쓰는 파일은 공유 파일이다.

- 공유 파일은 이슈에서 접촉을 선언한 항목만 최소 수정하며, 관련 없는 기존 항목을 재정렬하거나 리네임하지 않는다.
- 공유 파일만 변경하는 PR을 먼저 머지하고 도메인 PR은 그 뒤에 rebase한다.
- 한 도메인 패키지를 여러 기능 ID가 나눠 가질 때는 소유를 `<module>/**`가 아니라 담당 클래스 목록으로 선언한다. 참가 관계를 `room`이 소유하는 것처럼 한 모듈에 여러 기능이 들어가는 경우가 여기에 해당한다.
- 브랜치는 매일 최신 기본 브랜치로 rebase하고 PR을 3일 이상 열어두지 않는다.

다음에 해당하면 추측으로 진행하지 않고 작업을 중단한다. 외부 변경에 대한 명시적 승인이 있으면 사용자가 승인한 특정 이슈에 `DECISION_NEEDED` 라벨과 중단 사유 코멘트를 남기고, 구현 요청만 있거나 게시 승인이 없으면 확인된 사실과 필요한 결정을 사용자에게 보고한다.

- 정본 문서 간 계약이 서로 충돌한다.
- 선행 이슈가 제공해야 할 공개 계약이 아직 없다.
- 이슈에서 선언하지 않은 공유 파일을 수정해야 한다.
- 다른 이슈와 구현 책임이 겹친다.

정본 변경이 필요하면 문서 변경을 먼저 반영하고 그 변경을 이슈에 연결한다.

### 문서 관리 메타데이터

사람용 대표 진입점이나 현재 제품·개발·운영 판단을 직접 소유하는 활성 핵심 문서는 말미에 `소유자`·`최종 검증일`·`폐기 조건`을 기록한다. 정본으로 연결하기만 하는 `**/AGENTS.md` 라우터와 동결 아카이브는 대상에서 제외한다.

- `소유자`는 문서가 선언한 책임 범위를 검토하고 변경을 승인할 팀 또는 역할이다.
- `최종 검증일`은 매일 갱신하는 날짜나 마지막 편집일이 아니다. 소유 범위 전체를 연결된 정본과 현재 코드·설정·증거에 대조하고 알려진 충돌을 해소한 날에만 갱신한다.
- 링크·형식 검사나 일부 절만 확인한 경우에는 날짜를 바꾸지 않고 해당 변경의 검증 기록에 범위를 남긴다.
- 이 날짜는 생산 코드 구현, 운영 배포 또는 용량 측정 완료를 뜻하지 않는다. 해당 상태는 [P1 기능 상태 정본](p1/README.md#기능별-현재-상태)과 연결된 측정·운영 증거로 별도 판정한다.
- `폐기 조건`이 충족되면 새 정본을 먼저 연결하고 기존 문서를 아카이브하거나 제거한다.

README·COMMANDS 같은 첫 진입 경로를 크게 바꿀 때는 구두 안내 없이 README에서 시작하는 신규 독자 2명으로 확인하고, 변경 PR이나 이슈에 `첫 Green까지 걸린 시간`·`잘못 연 문서 수`·`실패한 명령 수`·`현재 상태/아키텍처/실행 명령까지의 링크 이동 수`를 기록한다. 첫 Green은 10분 이내, 세 정보는 각각 최대 두 번의 링크 이동을 목표로 한다. 사람 검증을 하지 않았다면 자동 링크 검사로 대신했다고 쓰지 말고 `사람 검증 미수행`으로 남긴다.

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

> 문서 관리: 소유자 `밤송이클럽 백엔드 팀` · 최종 검증일 `2026-08-12` · 폐기 조건 `백엔드 코드 규약과 협업 절차가 저장소의 다른 정본으로 완전히 대체될 때`
