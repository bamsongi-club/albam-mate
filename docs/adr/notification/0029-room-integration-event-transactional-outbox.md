# ADR-0029: 방 변경 통합 이벤트와 Transactional Outbox 기록 경계를 확정

- 상태: 승인됨
- 작성일: 2026-07-31
- 결정일: 2026-07-31
- 관련: [P1 알림 구현 명세](../../p1/notification.md#noti-01-모임-변경-알림-생성), [P1 알림 생성과 조회](../../P1-spec.md#알림-생성과-조회), [아키텍처](../../ARCHITECTURE.md), [P1 알림 저장 계약](../../ERD.md#p1-알림-저장-계약), [ADR-0002](../platform/0002-postgresql-primary-database.md), [ADR-0005](../participation/0005-room-participation-optimistic-locking.md), [ADR-0007](../platform/0007-domain-centered-modular-monolith.md), [ADR-0009](../platform/0009-utc-time-standard.md), [ADR-0030](0030-postgresql-notification-relay-processing-recovery.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

P1 서비스 내 웹 알림은 참가·재참가, 참가 취소와 방 취소의 최종 성공을 정해진 사용자에게 전달해야 한다. 원인 업무가 성공한 직후 프로세스가 종료돼도 알림 생성 근거를 잃지 않아야 하고, 낙관적 락 재시도의 실패 시도나 롤백된 업무는 알림을 남기지 않아야 한다. 실제 알림 생성은 원인 요청과 분리하되, 그 작업을 나타내는 영속 기록은 원인 업무와 원자적으로 남아야 한다.

`room`은 앞으로 대기열 등 추가 기능을 수용할 수 있으므로 알림 모듈의 구체 구현과 정책에 직접 의존하지 않아야 한다. 반대로 모든 방 내부 변경을 범용 이벤트로 공개하면 아직 소비자가 없는 계약과 이벤트 플랫폼을 먼저 만들게 된다. 현재 필요한 경계는 승인된 방 변경 사실만 타입이 분명한 계약으로 공개하고, `notification`이 그 계약을 통해 자기 Outbox와 알림 저장 책임을 수행하는 것이다.

이번 결정은 다음 기준을 만족해야 한다.

- 원인 업무 변경과 알림 생성 근거를 같은 PostgreSQL 트랜잭션으로 커밋할 것
- `room`이 `notification`의 내부 구현이나 알림 문구·전달 정책에 종속되지 않을 것
- 방 취소 수신자는 원인 커밋 시점의 `ACTIVE` 참가자로 고정하고 relay 시점에 다시 계산하지 않을 것
- 재시도와 동시 처리에도 같은 원인 이벤트와 수신자의 알림이 한 건으로 수렴할 것
- 이벤트 payload와 로그에 참가자 식별자·이메일·정확한 장소를 넣지 않을 것
- 범용 이벤트 플랫폼과 외부 broker를 측정된 필요 없이 도입하지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `room`이 `notification.contract`를 직접 호출해 Outbox를 기록 | 구현 흐름이 직접적이고 알림에 필요한 값을 바로 전달할 수 있다. | 대기열·통계 등 후속 소비자가 늘 때 `room`의 외부 의존이 계속 증가하고 핵심 방 업무가 후속 기능을 알게 된다. | 제외 |
| `room.contract`가 타입화된 통합 이벤트와 기록 포트를 소유하고 `notification`이 구현 | `room`은 방 변경 사실만 공개하고 `notification`이 Outbox·멱등성·알림 저장을 소유한다. 같은 애플리케이션과 DB 트랜잭션을 유지하면서 컴파일 의존을 `notification → room.contract`로 둘 수 있다. | 계약과 구현의 런타임 호출 방향이 다르므로 구조 테스트와 트랜잭션 검증이 필요하다. 새 이벤트 타입은 공개 계약 변경으로 관리해야 한다. | 선택 |
| 원인 커밋 뒤 Spring 이벤트 listener가 best-effort로 Outbox 또는 알림을 기록 | Outbox 기록 오류가 원인 업무를 롤백하지 않는다. | 원인 커밋 직후 프로세스가 종료되거나 listener가 실패하면 복구할 영속 이벤트 없이 알림이 유실될 수 있다. | 제외 |
| 원인 트랜잭션에서 수신자별 Notification을 직접 생성 | 별도 relay 없이 원자성과 즉시 생성을 얻을 수 있다. | 메시지 생성과 알림 저장 실패가 방 업무 트랜잭션을 늘리고, 재시도·실패 복구 책임이 `room` 흐름에 섞인다. | 제외 |
| Kafka·RabbitMQ 등 외부 broker에 바로 발행 | 독립 consumer와 전달 기반을 미리 확보할 수 있다. | DB 커밋과 broker 발행 사이의 이중 쓰기 문제는 그대로 남고, 현재 단일 애플리케이션에 운영 복잡도만 추가한다. | 제외 |

Notification의 원인 이벤트 식별자와 Outbox 정리 수명주기는 별도로 비교했다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `sourceEventId`를 FK 없는 논리적 멱등성 키로 보존하고 완료 Outbox를 정리 | Outbox 크기를 제한하고 Notification과 전달 작업의 수명주기를 분리한다. | Outbox 정리 뒤 원인 이벤트 존재를 DB FK가 검증하지 못하며 새 쓰기 경로·운영 SQL의 잘못된 값을 애플리케이션과 테스트로 막아야 한다. | 선택 |
| 최소 source-event registry를 영구 보존하고 Notification FK 유지 | 원인 이벤트 존재를 DB 참조 무결성으로 계속 검증할 수 있다. | 이벤트마다 영구 행이 누적되고 source event·Outbox·Notification의 세 수명주기를 관리해야 한다. 보존한 최소 행만으로 원인 상세를 복원할 수도 없다. | 제외 |

## 결정

`room.contract`는 방 변경 사실을 표현하는 공통 인터페이스와 이벤트별 구체 타입, 그 사실을 기록하는 포트를 소유한다. `notification`은 이 포트를 구현한다. 컴파일 의존 방향은 `notification → room.contract`이며, `room`은 `notification`의 패키지·Entity·Repository·DTO를 참조하지 않는다. 구현 시 [아키텍처](../../ARCHITECTURE.md)와 모듈 구조 테스트에 이 허용 의존을 반영한다.

공개 이벤트는 모든 방 내부 변경이 아니라 승인된 모듈 간 협력에 필요한 다음 세 종류로 제한한다.

- 참가 또는 재참가 성공
- 참가 취소 성공
- 방 취소 성공

각 이벤트는 유형, `roomId`와 원인 발생 시각처럼 알림 문구와 무관한 방 변경 사실만 가진다. `sourceEventId`는 `room`이 미리 만들지 않고 Outbox 이벤트를 저장할 때 PostgreSQL `BIGINT IDENTITY`로 생성한다. 새 이벤트는 실제 소비자와 요구가 확정된 뒤 공개 계약으로 추가하며, 방 생성·수정·자동 종료·상태 보정을 미리 이벤트화하지 않는다.

최종 Room Command Executor는 원인 업무가 성공하는 같은 트랜잭션에서 수신자 스냅샷을 확정해 이벤트 payload와 분리하여 기록 포트에 전달한다.

- 참가·재참가와 참가 취소는 방 주최자 한 명을 고정한다.
- 방 취소는 취소 트랜잭션이 확정한 현재 `ACTIVE` 참가자 목록을 고정한다.
- `ACTIVE` 참가자가 없는 방 취소는 정상적으로 방만 취소하고 Outbox를 만들지 않는다.
- 참가·재참가 또는 참가 취소에서 주최자를 확정할 수 없으면 방 데이터 불변식 위반으로 보고 원인 트랜잭션을 실패시킨다.

Outbox는 원인 이벤트 한 행과 수신자 N행으로 저장한다. 사용자 ID는 이벤트 payload가 아니라 내부 수신자 관계에만 두고 메시지·로그·응답에 노출하지 않는다. 수신자 관계는 Outbox 이벤트를 강한 FK로 참조하고 이벤트 정리 시 함께 삭제한다.

원인 업무 변경과 최소 Outbox 기록은 하나의 PostgreSQL 트랜잭션으로 커밋한다. 업무 검증 실패, DB 쓰기 실패, 낙관적 락 충돌 시도와 최종 실패가 롤백되면 이벤트와 수신자도 함께 롤백한다. Outbox 기록 자체가 실패하면 원인 업무도 롤백한다. 실제 Notification을 만드는 relay 실패는 이미 커밋된 원인 업무를 되돌리지 않으며 [ADR-0030](0030-postgresql-notification-relay-processing-recovery.md)의 재시도·복구 정책을 따른다.

relay는 at-least-once 처리를 전제로 하고 Notification에 `(sourceEventId, recipientUserId)` 유일 제약을 둔다. `sourceEventId`는 Outbox를 조회하는 영속 관계가 아니라 원인 추적과 멱등성에 쓰는 논리적 키다. 처리 완료 Outbox를 정리할 수 있도록 Notification의 `sourceEventId`에는 Outbox FK를 두지 않는다. 이 선택으로 Outbox 정리 뒤 원인 이벤트 존재를 DB FK가 검증해 주는 참조 무결성을 포기하며, 알림 생성 경로 제한과 PostgreSQL 유일 제약·통합 테스트로 위험을 낮춘다.

원인 트랜잭션에서는 이벤트와 수신자만 기록한다. 알림 유형 매핑, 일반 문구 생성과 Notification 저장은 relay가 담당하며 외부 호출을 실행하지 않는다.

## 결과

- 얻는 것:
    - `room`이 알림 구현을 모르면서도 원인 업무와 알림 생성 근거를 원자적으로 보존한다.
    - 하나의 원인 이벤트를 여러 수신자 알림으로 fan-out하고 DB 유일 제약으로 재처리를 한 건에 수렴시킨다.
    - 수신자를 최종 성공 트랜잭션에서 고정해 이후 참가 관계 변화가 과거 알림 대상을 바꾸지 않는다.
- 감수할 비용·위험:
    - Outbox 기록 결함이 있으면 방·참가 업무도 실패하므로 마이그레이션과 통합 테스트가 핵심 업무의 필수 검증 범위가 된다.
    - Notification의 논리적 `sourceEventId`에 Outbox FK가 없어 새 쓰기 경로나 운영 SQL의 잘못된 값까지 DB가 차단하지는 못한다.
    - 공개 이벤트 타입과 기록 포트는 모듈 간 계약이므로 호환성을 검토하며 변경해야 한다.
- 후속 작업:
    - ERD에 Outbox 이벤트·수신자와 Notification 테이블, FK·유일 제약·인덱스·정리 관계를 반영한다.
    - 아키텍처와 구조 테스트에 `notification` 책임과 `notification → room.contract` 의존을 추가한다.
    - 원인 Command Executor의 동일 트랜잭션 기록과 낙관적 락 재시도·롤백·동시 방 취소 수신자 스냅샷을 PostgreSQL에서 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - 모든 방 변경을 수집하는 범용 이벤트 플랫폼
    - Notification과 Outbox 사이의 영구 source-event registry. 이를 제외하며 포기한 DB 참조 무결성은 결정과 결과에 명시한다.
    - 이메일·모바일 푸시·Web Push·SMS 전달
    - Kafka·RabbitMQ와 독립 메시지 broker
- 보류 이유:
    - 현재 승인된 소비자는 서비스 내 웹 알림이고, 단일 Spring Boot 애플리케이션과 PostgreSQL 트랜잭션으로 요구를 충족할 수 있다.
- 다시 검토할 조건:
    - 같은 원인 이벤트를 독립적으로 처리하는 consumer가 둘 이상 생길 때
    - 모듈별 독립 배포나 DB 밖의 전역 이벤트 식별자가 필요할 때
    - Outbox 정리 뒤에도 DB FK로 원인 이벤트 존재를 영구 검증해야 하는 감사 요구가 생길 때
    - 외부 채널의 사용자 정책·보안·전달 보장이 구체화될 때

## 참고 자료

- [P1 알림 구현 명세](../../p1/notification.md)
- [P1 공통 명세](../../P1-spec.md)
- [ADR-0007](../platform/0007-domain-centered-modular-monolith.md)
- [ADR-0030](0030-postgresql-notification-relay-processing-recovery.md)

## 검증

- 상태: 검증됨
- 근거:
    - 구현: [PR #297](https://github.com/bamsongi-club/albam-mate/pull/297)이 Outbox·수신자·Notification 스키마와 영속 모델을, [PR #447](https://github.com/bamsongi-club/albam-mate/pull/447)이 `room.contract` 기록 포트와 참가·취소 Command의 동일 트랜잭션 기록을 구현했다.
    - 테스트: `ModuleArchitectureTest`가 `notification → room.contract` 의존만 허용하고, `NotificationSchemaPostgresTest`·`NotificationRoomChangeOutboxPostgresTest`가 수신자 스냅샷, 롤백, 동시 방 변경과 `(sourceEventId, recipientUserId)` 멱등 제약을 PostgreSQL에서 검증한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
