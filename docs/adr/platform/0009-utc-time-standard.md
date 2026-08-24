# ADR-0009: 시스템 기준 시각을 UTC로 통일

- 상태: 승인됨
- 작성일: 2026-07-24
- 결정일: 2026-07-24
- 관련: [ADR-0002](0002-postgresql-primary-database.md), [API 명세](../../API.md), [ERD](../../ERD.md), [Room 상태 전이 ADR](../room/0004-room-state-transition-scheduler.md), [프로젝트 컨벤션](../../CONVENTIONS.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

Albam Mate의 방 생성, 참가 가능 여부, 참가 취소와 상태 전이는 모두 현재 시각과 `startsAt`을 비교한다. 같은 시각이 개발 PC, AWS 실행 환경, PostgreSQL 세션과 테스트에서 다르게 해석되면 모집 종료나 참가 허용 결과가 환경마다 달라질 수 있다.

AWS EC2 인스턴스의 기본 시간대는 UTC지만, 애플리케이션이 실행될 JVM·컨테이너·데이터베이스 세션의 기본 시간대까지 항상 같다고 전제할 수는 없다. 또한 P0 모임은 홍대에서 열리므로 사용자는 한국 현지 시각으로 입력하고 확인해야 한다. 시스템 내부의 비교 기준과 사용자에게 보여 주는 시간대를 분리해야 한다.

현재 API 명세는 ISO 8601 오프셋 형식을 사용하고 예시에 `+09:00`을 포함한다. ERD의 `start_at`, `created_at`, `updated_at`, `joined_at`과 `canceled_at`은 PostgreSQL `TIMESTAMPTZ`로 정의돼 있다. 이 계약을 Java 타입, 실행 환경과 테스트까지 일관되게 연결할 기준이 필요하다.

이번 결정의 기준은 다음과 같다.

- 같은 순간을 실행 환경과 무관하게 동일하게 비교할 수 있을 것
- 한국 사용자가 입력하고 보는 현지 시각을 보존할 것
- API, Java와 PostgreSQL 사이의 암묵적인 시간대 해석을 제거할 것
- 시간 기반 상태 전이를 고정된 현재 시각으로 재현해 테스트할 수 있을 것
- 향후 다른 지역을 지원할 때 시간대 식별자를 확장할 수 있을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 내부 UTC + API 경계에서 `Asia/Seoul` 변환 | 저장·비교 기준이 하나이며 AWS와 로컬 환경의 기본 시간대 차이에 영향을 받지 않는다. 현재 한국 사용자에게는 `+09:00` 현지 시각을 제공할 수 있다. | 입출력 경계에서 명시적인 변환이 필요하며, 원래 입력한 시간대 식별자는 별도 저장하지 않으면 남지 않는다. | 선택 |
| 모든 계층에서 `Asia/Seoul` 사용 | P0 사용자에게 보이는 시각과 서버 표현이 같아 초기 이해가 쉽다. | 서버·DB 기본 설정에 결합되고, UTC 기반 외부 시스템 연동이나 다른 지역 지원 시 변환 지점이 퍼진다. `LocalDateTime`을 사용하면 같은 순간이라는 정보가 타입에 남지 않는다. | 제외 |
| 각 실행 환경의 기본 시간대 사용 | 별도 설정과 변환 코드가 적다. | 개발 PC, 컨테이너, AWS와 DB 세션 설정에 따라 같은 값의 해석이 달라진다. 테스트와 운영 결과를 재현하기 어렵다. | 제외 |

## 결정

Albam Mate의 저장·비교·로그 기준 시각은 UTC로 통일한다. JVM, 컨테이너와 PostgreSQL 연결의 기본 시간대는 배포 설정에서 UTC로 명시하고, 운영체제나 AWS 리전의 암묵적인 기본값에 의존하지 않는다.

실제 타임라인 위의 한 순간을 나타내는 Entity 필드에는 Java `Instant`를 사용하고 PostgreSQL `TIMESTAMPTZ`에 저장한다. `startsAt`, `createdAt`, `updatedAt`, `joinedAt`, `canceledAt`과 세션 만료 시각이 이에 해당한다. 시간대가 없는 `LocalDateTime`은 이런 필드에 사용하지 않는다. 날짜만 또는 시각만을 의미하는 별도 요구가 생기면 `LocalDate`나 `LocalTime`을 용도에 맞게 사용한다.

API는 시간 값에 오프셋이 포함된 ISO 8601 형식을 사용한다. 요청 DTO는 오프셋이 포함된 값을 받아 `Instant`로 정규화한다. P0 응답과 화면 표시는 `Asia/Seoul` 기준의 `+09:00` 오프셋으로 변환한다. PostgreSQL `TIMESTAMPTZ`는 같은 순간을 UTC 기준으로 저장하지만 원래 입력한 지역 시간대 이름은 보존하지 않으므로, 향후 여러 지역의 현지 일정 규칙이 필요하면 IANA 시간대 식별자를 별도 컬럼으로 저장한다.

현재 시각을 사용하는 업무 로직에는 Java `Clock`을 주입한다. 운영 기본값은 `Clock.systemUTC()`로 두고 테스트에서는 `Clock.fixed(...)`를 사용해 경계 시각을 재현한다. 도메인 코드에서 `LocalDateTime.now()`, `OffsetDateTime.now()` 또는 시스템 기본 시간대를 직접 호출하지 않는다.

## 결과

- 얻는 것: 방 상태와 참가 가능 여부를 모든 환경에서 같은 순간 기준으로 판단하고, 시간 기반 테스트를 고정된 값으로 재현할 수 있다. 사용자는 계속 한국 현지 시각을 입력하고 확인한다.
- 감수할 비용·위험: API 경계에서 UTC와 `Asia/Seoul` 변환이 필요하다. `TIMESTAMPTZ`만으로는 사용자가 입력한 원래 지역 시간대 이름을 복원할 수 없다.
- 후속 작업: UTC `Clock` Bean과 시간 변환 코드를 추가하고, 실행 환경과 데이터베이스 연결의 시간대를 UTC로 설정한다. API 직렬화·역직렬화와 `now == startsAt` 경계 테스트를 추가한다.

## 보류 및 재검토

- 지금 하지 않는 것: 사용자별 시간대 설정, 방별 IANA 시간대 컬럼, 반복 일정과 일광 절약 시간 전환 규칙
- 보류 이유: P0의 오프라인 모임 지역은 홍대로 고정돼 있고 `Asia/Seoul` 외의 현지 일정 요구가 없다.
- 다시 검토할 조건: 다른 국가·시간대에서 방을 만들 수 있을 때, 반복 일정이나 현지 달력 규칙을 지원할 때, 외부 캘린더와 원래 시간대 식별자를 교환해야 할 때

## 참고 자료

- [AWS EC2 인스턴스 시간대](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/change-time-zone-of-instance.html)
- [PostgreSQL 날짜와 시간 타입](https://www.postgresql.org/docs/current/datatype-datetime.html)
- [Java 21 Clock API](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/Clock.html)

## 검증

- 상태: 미검증
- 근거:
    - 구현:
        - `UtcTimeZone`, 모든 프로필과 Gradle `Test`·`JavaExec` 설정이 JVM·Jackson·Hibernate·Hikari 실행 시간대를 UTC로 고정한다.
        - 엔티티와 마이그레이션은 `Instant`·`TIMESTAMP WITH TIME ZONE`을 사용하고 `TimeConfig`는 `Clock.systemUTC()`를 제공한다.
    - 테스트:
        - `TimeConfigTest`와 `UtcTimeZoneTest`는 실행·연결 시간대, 동일 순간 정규화, 잘못된 오프셋 거절, `Asia/Seoul` 응답 직렬화와 JVM 기본 시간대 변경을 확인한다.
        - PostgreSQL 18의 `ddl-auto=validate`가 통과했고, `room.entity.RoomStatusCorrectionTest`는 고정 `Clock`으로 두 상태 전이 경계를 확인한다.
    - CI:
        - `TZ=UTC`를 사용한다.
- 미검증:
    - AWS 운영 런타임의 시간대 적용은 아직 배포하지 않았으므로 [ADR-0021](0021-p0-aws-ec2-rds-deployment-baseline.md)의 배포 검증에서 확인한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
