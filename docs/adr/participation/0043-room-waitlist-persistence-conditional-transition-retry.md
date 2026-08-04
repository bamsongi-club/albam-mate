# ADR-0043: ROOM 대기열을 단일 최신 상태로 저장하고 조건부 전이·등록 재시도를 조정

- 상태: 대체됨
- 작성일: 2026-08-03
- 결정일: 2026-08-03
- 관련: [P1 PART-04](../../p1/room.md#part-04-선착순-대기열과-자동-승격), [P1 대기 API 계약](../../API.md#part-04-대기-등록재신청), [ERD](../../ERD.md), [ROOM 명령 아키텍처](../../ARCHITECTURE.md#방-변경), [GitHub Issue #344](https://github.com/bamsongi-club/albam-mate/issues/344), [ADR-0005](0005-room-participation-optimistic-locking.md), [ADR-0035](../room/0035-room-status-action-eligibility-separation.md), [ADR-0036](../room/0036-bounded-room-state-transition-processing.md)
- 대체 대상: [ADR-0037](0037-room-waitlist-latest-state-atomic-promotion.md)
- 후속 ADR: [ADR-0046](0046-room-waitlist-persistence-conditional-transition-retry.md)

## 맥락

P1은 시작 전 정원이 찬 ROOM에 별도로 대기를 신청하고, 본인의 최신 대기 상태와 현재 순번을 조회하며, 참가 취소로 생긴 빈자리에 첫 대기자를 자동 승격한다. 동일 사용자의 중복 신청은 기존 순서를 유지하고, 허용된 종료 상태에서 재신청하면 새 순번으로 대기열 맨 뒤에 들어가야 한다. 시작 시각 도달과 ROOM 취소 뒤에는 남은 대기도 종료 상태로 조회할 수 있어야 한다.

[ADR-0037](0037-room-waitlist-latest-state-atomic-promotion.md)은 ROOM·사용자별 단일 최신 상태, 조건부 상태 전이, FIFO 자동 승격과 참가 취소·승격의 원자적 처리를 승인했다. 이후 구현자가 임의로 선택하지 않도록 복합 식별자, 순번 발급, JPA 신규 판정, 재신청 출발 상태, 상태·순번 조회 스냅샷과 대기 등록 재시도 경계를 추가로 확정했다. 승인된 ADR의 결정 본문은 사후에 수정하지 않으므로 이 ADR이 기존 결정을 보존하면서 최신 계약으로 대체한다.

판단 기준은 다음과 같다.

- 같은 ROOM과 사용자의 현재 대기 결과를 하나의 저장 정본에서 조회한다.
- 중복 신청은 순서를 바꾸지 않고, 허용된 재신청은 새 순번으로 FIFO 맨 뒤에 배치한다.
- 참가 취소·승격·ROOM 상태·참가 관계가 부분 성공하지 않게 한다.
- 현재 상태와 동적 순번을 서로 다른 데이터베이스 스냅샷에서 조합하지 않는다.
- ROOM 낙관적 락과 대기 순번 충돌의 재시도 횟수를 하나의 제한된 예산으로 관리한다.
- 현재 기준선을 측정하기 전에 더 강한 잠금이나 별도 대기 버전을 도입하지 않는다.

## 검토한 대안

### 저장·식별자와 순번

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 신청·취소·승격마다 이력 행 추가 | 과거 변경과 순서를 복원할 수 있다. | 현재 상태 선별과 중복 활성 대기 방어가 복잡해지고 현재 API가 요구하지 않는 감사 기능을 먼저 구현한다. | 제외 |
| ROOM·사용자별 단일 최신 행과 `(room_id, user_id)` 복합 PK | 현재 관계가 한 행으로 고정되고 별도 식별자가 필요 없다. | 과거 이력을 복원할 수 없고 재신청 시 순번·시각을 명시적으로 갱신해야 한다. | 선택 |
| ROOM별 `max(queue_order) + 1` | 별도 sequence가 필요 없다. | 동시 신청이 같은 최댓값을 읽어 충돌하고 순번 발급을 위해 추가 직렬화가 필요하다. | 제외 |
| 전역 BIGINT sequence를 코드에서 명시적으로 발급 | 동시 요청 사이의 유일한 정렬 키를 단순하게 발급하고 ROOM별 FIFO를 결정적으로 비교할 수 있다. | 롤백으로 번호 공백이 생기고 전역 번호가 ROOM마다 연속되지 않는다. | 선택 |

### JPA 영속성과 상태 전이

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 복합 ID가 있는 신규 Entity를 기본 `save()` 판정에 맡김 | 추가 인터페이스가 필요 없다. | 할당된 식별자를 기존 행으로 오인해 최초 저장이 `merge`가 될 수 있다. | 제외 |
| `RoomWaitlist`만 `Persistable`로 신규 여부를 명시 | 최초 저장을 INSERT로 고정하고 공통 Entity 계층을 바꾸지 않는다. | 영속·조회 이후 신규 플래그 전환과 재시도마다 새 Entity 생성이 필요하다. | 선택 |
| 관리 Entity의 임의 변경으로 모든 상태 전이 처리 | 일반적인 dirty checking 흐름을 사용할 수 있다. | 경쟁 중 오래된 상태가 먼저 확정된 조건부 전이를 덮어쓸 수 있다. | 제외 |
| 목적별 조건부 SQL과 갱신 행 수 분기 | 허용된 출발 상태만 바꾸고 경쟁에서 먼저 확정된 결과를 보존한다. | 0행 결과마다 호출자가 최신 상태와 업무 결과를 구분해야 한다. | 선택 |

### 동시성·재시도

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 참가 취소와 자동 승격을 별도 트랜잭션으로 처리 | 각 작업이 단순하다. | 빈자리만 남거나 같은 빈자리에 둘 이상이 승격되는 부분 성공이 노출될 수 있다. | 제외 |
| ROOM 낙관적 락과 대기 조건부 전이를 같은 ROOM 처리 트랜잭션에서 사용 | 기존 동시성 루트를 유지하면서 대기 상태 덮어쓰기를 막는다. | 충돌 시 최신 상태를 다시 읽는 독립 트랜잭션이 필요하다. | 선택 |
| 기존 `RoomOptimisticLockRetrier`와 순번 충돌 재시도기를 중첩 | 기존 코드를 그대로 감쌀 수 있다. | 실제 시도 횟수가 두 예산의 곱으로 늘고 어떤 오류가 최종 결과를 소유하는지 불명확하다. | 제외 |
| PART-04 전용 Coordinator가 두 재시도 원인의 단일 예산을 관리 | 전체 시도 횟수와 롤백·오류 경계가 하나로 고정된다. | 공통 Retrier와 다른 좁은 조정 경계가 하나 더 필요하다. | 선택 |
| 대기 행 버전, 비관적 락 또는 `SKIP LOCKED` | 일부 경쟁을 데이터베이스 잠금이나 버전으로 직렬화할 수 있다. | 일괄 종료·엄격한 FIFO와 혼용할 경계가 복잡해지고 측정 전부터 잠금 대기 비용을 부담한다. | 제외 |

## 결정

### 단일 최신 저장 계약

같은 ROOM과 사용자의 대기 관계는 `room_waitlists`의 한 행으로 저장하고 `(room_id, user_id)` 복합 PK로 식별한다. 별도 단일 ID와 상태 변경 이력 행은 만들지 않는다. 최신 상태는 `WAITING`, `PROMOTED`, `CANCELED`, `EXPIRED`, `ROOM_CANCELED` 중 하나이며 대기 행에 별도 낙관적 락 `version`을 두지 않는다.

JPA 복합 ID는 `roomId`, `userId` scalar 값만 가진 `@EmbeddedId RoomWaitlistId`로 매핑한다. `RoomWaitlist`에서 `Room`·`User` 연관관계를 매핑하지 않는다. `RoomWaitlist`만 `Persistable<RoomWaitlistId>`을 구현해 최초 저장의 신규 여부를 명시하고 `@PostPersist`·`@PostLoad` 뒤에는 기존 Entity로 전환한다. 이를 위해 공통 BaseEntity를 새로 만들지 않으며, 등록 재시도의 각 INSERT 시도는 새 Entity를 사용한다.

저장값은 `status VARCHAR(20)`, 양수인 `queue_order BIGINT`, `queued_at`, `created_at`, `updated_at`이다. 세 시각은 `TIMESTAMPTZ`이며 한 유스케이스가 최초에 고정한 request time을 사용한다. 최초 INSERT에서는 세 시각이 같은 request time이고, 허용된 재신청에서는 `queued_at`과 `updated_at`을 새 request time으로 바꾸되 최초 `created_at`은 보존한다. 종료 전이는 순번·신청 시각·생성 시각을 보존하고 `updated_at`만 해당 요청의 고정 시각으로 바꾼다.

순번은 전역 `room_waitlist_queue_order_seq`에서 발급한다. sequence는 `BIGINT`, `START 1`, `INCREMENT 1`, `NO CYCLE`, `CACHE 1`이며 `queue_order`에 DB `DEFAULT`를 두지 않는다. `WAITING` 활성화 트랜잭션이 `Room.version` claim에 성공한 뒤 코드가 순번을 명시적으로 발급한다. 롤백으로 생긴 공백은 정상이며 반환하거나 재사용하지 않는다.

현재 `WAITING`의 ROOM별 FIFO는 `queue_order ASC` 하나로 결정한다. PostgreSQL에는 다음 부분 인덱스를 두며 predicate는 정확히 `status = 'WAITING'`으로 고정한다.

- `uq_room_waitlists_waiting_room_queue_order`: 현재 `WAITING`의 `(room_id, queue_order)` 부분 UNIQUE
- `idx_room_waitlists_waiting_user_room`: 현재 `WAITING`의 `(user_id, room_id)` 부분 인덱스

그 밖의 정확한 제약 이름, Repository·projection·메서드·패키지 이름과 Flyway 버전은 이 ADR이 새로 선택하지 않는다. 후속 ERD·구현 이슈는 위 의미를 바꾸지 않는 범위에서 저장소 규칙과 승인된 구현 계약을 따른다.

### 상태 전이와 조회 계약

신규 신청은 대기할 수 있는 최신 ROOM·사용자 조건에서 한 행을 `WAITING`으로 INSERT한다. 이미 `WAITING`인 중복 신청은 상태, `queue_order`, `queued_at`과 ROOM version을 바꾸지 않는다. `CANCELED`와 `PROMOTED`만 새 순번과 request time으로 `WAITING` 재신청할 수 있다. `EXPIRED`와 `ROOM_CANCELED`는 재활성화 출발 상태가 아니다.

상태 변경은 목적별 조건부 SQL로 수행한다.

- 대기 취소: `WAITING → CANCELED`
- 자동 승격: 현재 첫 `WAITING → PROMOTED`
- 시작 경계 종료: 남은 `WAITING → EXPIRED`
- ROOM 취소 종료: 남은 `WAITING → ROOM_CANCELED`
- 재신청: `CANCELED` 또는 `PROMOTED` → `WAITING`

각 SQL은 허용된 출발 상태를 조건으로 검사한다. 갱신 행 수가 0일 때 최신 상태를 다시 읽어 중복·업무 실패·다음 승격 후보를 구분하는 책임은 호출자가 소유한다. 관리 Entity의 오래된 상태가 조건부 SQL 결과를 덮어쓰게 하지 않는다. 구체적인 `save()`·`flush()` 횟수와 위치, 내부 collaborator 이름은 후속 구현 계약에서 정하며 이 ADR을 근거로 임의 선택하지 않는다.

본인의 최신 상태와 동적 `position`은 한 SQL과 한 데이터베이스 스냅샷에서 조회한다. `WAITING`의 `position`은 같은 ROOM에서 현재 `WAITING`이고 `queue_order`가 더 작은 행 수에 1을 더한 값이며, 나머지 네 상태의 `position`은 `null`이다. 첫 자동 승격 후보도 현재 `WAITING`을 `queue_order ASC`로 조회한다. 이 조회는 별도 조회 락, `SKIP LOCKED`나 독립 트랜잭션을 열지 않고 호출자의 트랜잭션에 참여한다.

### ROOM 일관성 경계

신규 대기와 허용된 재신청처럼 대기 관계를 `WAITING`으로 활성화하는 요청은 같은 트랜잭션에서 `Room.version`을 claim한 뒤 순번을 발급하고 대기 관계를 변경한다. 중복 `WAITING` 신청과 `WAITING → CANCELED`는 ROOM version을 강제로 증가시키지 않는다.

시작 전 참가 취소로 빈자리가 생기면 참가 취소, 첫 현재 `WAITING`의 `PROMOTED`, 기존 `CANCELED` 참가 관계의 재활성화 또는 새 `ACTIVE` 참가 관계 생성, ROOM 참가 인원과 상태 변경을 같은 ROOM 처리 트랜잭션에서 함께 커밋하거나 함께 롤백한다. 고수준 데이터베이스 변경 순서는 `ROOM → 기존 참가 취소 → 대기 승격 → 승격 참가 생성·복구`이고, ROOM 취소는 `ROOM → 남은 WAITING 종료`다. 구체적인 저장·flush 구조는 이 순서를 검증 가능하게 유지하되 별도 승인된 구현 계약이 소유한다.

자동 승격은 현재 첫 `WAITING`에 조건부 갱신을 시도한다. 동시 취소 등으로 0행이면 먼저 확정된 최신 상태를 보존하고 다음 현재 `WAITING` 후보를 계속 검사한다. `SKIPPED` 같은 별도 상태는 추가하지 않는다. 시작 경계와 ROOM 취소로 남은 대기를 종료하는 처리도 해당 ROOM 상태 판정과 같은 일관성 경계에서 수행한다.

대기 활성화가 먼저 커밋되면 동시 참가 취소는 ROOM version 충돌 뒤 최신 첫 대기자를 승격한다. 참가 취소가 먼저 활성 대기자 없음과 빈자리를 커밋하면 동시 대기 활성화는 충돌 뒤 최신 `RECRUITING` 상태를 읽고 대기 관계를 만들지 않는다. 따라서 활성 대기가 있는 ROOM을 `RECRUITING`으로 확정하지 않는다.

### 대기 등록 재시도 경계

대기 등록·재신청은 트랜잭션이 없는 PART-04 전용 Coordinator가 request time과 최초 시도 포함 총 3회의 단일 예산을 관리한다. 각 시도는 Spring Proxy를 거친 독립 `REQUIRES_NEW` 트랜잭션이며 최신 ROOM·참가·대기 상태를 다시 읽는다. 다음 두 원인만 같은 예산으로 전체 요청을 다시 시도한다.

- ROOM 낙관적 락 또는 조건부 version claim 충돌
- 정확히 `uq_room_waitlists_waiting_room_queue_order` 제약에서 발생한 현재 `WAITING` 순번 충돌

두 원인의 재시도기를 중첩하지 않는다. PART-04 전용 Coordinator가 공유 예산을 직접 소유하며 기존 `RoomOptimisticLockRetrier`와 [ADR-0005](0005-room-participation-optimistic-locking.md)의 다른 ROOM 명령 계약은 변경하지 않는다. 모든 시도는 최초에 고정한 같은 request time을 사용하고, 순번 충돌 재시도에서는 `Room.version` claim부터 최신 업무 조건을 다시 판정한 뒤 새 순번을 발급한다.

PK·FK·CHECK·그 밖의 UNIQUE 위반, 교착, 직렬화 실패와 분류할 수 없는 DB 오류는 재시도하지 않는다. 각 실패 시도는 전체 롤백한다. 최종 원인이 ROOM 충돌이면 기존 `409 ROOM_CONCURRENT_MODIFICATION`, 정확한 순번 UNIQUE 충돌의 예산 소진이나 비대상 DB 오류면 내부 제약명·SQL 정보를 노출하지 않는 공통 `500 INTERNAL_SERVER_ERROR`를 반환한다. 최종 내부 오류는 외부에 세부 정보를 노출하지 않고 원인과 ROOM을 추적할 수 있는 정제된 `ERROR` 로그를 요청당 한 번 남긴다.

## 결과

- 얻는 것:
    - 현재 대기 상태와 중복 신청 여부를 ROOM·사용자별 한 행에서 판단하고, 현재 상태와 순번을 같은 스냅샷으로 반환한다.
    - 전역 sequence와 현재 `WAITING` 부분 UNIQUE로 결정적인 FIFO를 방어하면서 롤백 번호 공백을 정상 상태로 취급한다.
    - 조건부 전이로 대기 취소·승격·종료 경쟁에서 먼저 확정된 상태를 덮어쓰지 않는다.
    - 참가 취소·승격·참가 관계·ROOM 변경의 부분 성공을 막고 활성 대기가 있는 `RECRUITING` ROOM을 남기지 않는다.
    - ROOM 충돌과 정확한 순번 충돌의 총 시도 횟수, 최종 오류와 로그 책임이 하나의 Coordinator에 고정된다.
- 감수할 비용·위험:
    - 전체 대기 변경 이력을 복원할 수 없다.
    - 전역 sequence에는 롤백 공백이 생기며 ROOM별 번호가 연속되지 않는다.
    - 목적별 조건부 SQL, 0행 뒤 최신 상태 분기와 JPA 영속성 컨텍스트 경계를 구현·검증해야 한다.
    - 같은 ROOM의 대기 활성화는 참가 변경과 ROOM version에서 충돌하고, 순번 충돌은 최대 세 개의 독립 트랜잭션 비용을 만들 수 있다.
- 후속 작업:
    - ERD·Flyway·JPA·Repository에 저장·조회·조건부 전이 계약을 일치시킨다.
    - 대기 등록·조회·취소와 참가 취소 자동 승격·ROOM 취소 연결을 분리된 구현 이슈에서 완성한다.
    - PostgreSQL에서 신규·재신청·중복, 두 커밋 순서, 조건부 전이 경쟁, 전체 롤백과 재시도 예산을 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - 전체 대기 이벤트 이력과 운영자용 이력 조회
    - 대기 행별 낙관적 락 version
    - 비관적 락, `SKIP LOCKED`, 분산 락과 외부 작업 큐
    - 모든 대기 변경의 ROOM version 강제 증가
    - 기존 `RoomOptimisticLockRetrier`의 의미·호출자 확대
- 보류 이유: 현재 API는 최신 상태와 본인 순번만 요구하며, 조건부 상태 전이와 기존 ROOM 낙관적 락으로 제한된 기준선을 구현·측정할 수 있다.
- 다시 검토할 조건: 재현 가능한 부하 테스트나 운영 측정에서 대기 활성화의 ROOM version 충돌·재시도, 조건부 갱신 재조회, 전역 순번 발급 또는 FIFO 조회 비용이 실제 문제로 확인되고 사용자가 대안 비교를 승인할 때

## 참고 자료

- [ADR-0037: ROOM 대기열을 단일 최신 상태로 저장하고 자동 승격을 원자적으로 처리](0037-room-waitlist-latest-state-atomic-promotion.md)
- [ADR-0005: 방 참가 동시성 제어에 낙관 락을 사용](0005-room-participation-optimistic-locking.md)
- [P1 PART-04 기능 계약](../../p1/room.md#part-04-선착순-대기열과-자동-승격)
- [P1 대기 API 계약](../../API.md#part-04-대기-등록재신청)
- [GitHub Issue #344](https://github.com/bamsongi-club/albam-mate/issues/344)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 복합 PK·sequence·제약·부분 인덱스의 ERD·Flyway·JPA 일치
    - 신규 INSERT 판정, 상태·순번 단일 스냅샷 조회와 목적별 조건부 전이
    - PART-04 전용 총 3회 재시도, 예외 분류·롤백·외부 오류·로그 경계
    - 신규 대기·재신청과 참가 취소의 두 커밋 순서, FIFO·동시 취소·승격·일괄 종료의 PostgreSQL 통합 테스트

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
