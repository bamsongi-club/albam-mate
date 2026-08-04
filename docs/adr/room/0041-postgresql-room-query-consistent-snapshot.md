# ADR-0041: 상태 보정 뒤 ROOM 조회를 PostgreSQL 일관 스냅샷으로 구성

- 상태: 승인됨
- 작성일: 2026-08-03
- 결정일: 2026-08-03
- 관련: [결정 이슈 #301](https://github.com/bamsongi-club/albam-mate/issues/301), [정본화 이슈 #304](https://github.com/bamsongi-club/albam-mate/issues/304), [후속 정본 동기화 #305](https://github.com/bamsongi-club/albam-mate/issues/305), [P1 ROOM-08](../../p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리), [트랜잭션 컨벤션](../../CONVENTIONS.md#transaction), [ADR-0012](0012-room-request-boundary-state-reconciliation.md), [ADR-0035](0035-room-status-action-eligibility-separation.md), [ADR-0037](../participation/0037-room-waitlist-latest-state-atomic-promotion.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

ADR-0012는 목록·상세 ROOM 조회가 상태 보정을 먼저 커밋한 뒤 최신 상태를 읽도록 결정했다. 그러나 현재 PostgreSQL 기본 `READ_COMMITTED`에서 여러 SELECT를 실행하면 각 문장이 서로 다른 스냅샷을 볼 수 있다. 응답 하나를 조립하는 동안 참가·대기 관계가 커밋되면 ROOM의 상태·인원과 현재 `ACTIVE`·`WAITING` 사실이 서로 다른 시점에서 읽혀 `joinable`·`waitlistable` 판정이 내부적으로 모순될 수 있다.

행동 가능성은 실제 명령을 대신하지 않는 안내값이지만, 한 응답 안에서는 같은 시점의 ROOM과 요청자 관계를 사용해야 한다. 동시에 조회가 참가·대기 명령을 막지 않아야 하고, Game·User 정보 조회나 응답 전체를 위한 거대한 projection 때문에 ROOM 데이터베이스 트랜잭션이 길어져서도 안 된다.

이 결정은 다음 기준을 우선한다.

- 상태 보정이 커밋된 뒤 ROOM과 필요한 현재 `ACTIVE`·`WAITING` 사실을 하나의 PostgreSQL 스냅샷에서 읽는다.
- 조회 락 없이 참가·대기 명령과 병행한다.
- ROOM 행동 가능성 판정에 필요한 데이터만 짧은 데이터베이스 트랜잭션에서 읽는다.
- 기존 Repository 조회와 DTO 조립 경계를 거대한 단일 projection으로 합치지 않는다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 목록·상세 ReadService를 독립 `REPEATABLE_READ` 읽기 트랜잭션으로 실행 | 기존 여러 SELECT를 유지하면서 한 응답의 ROOM·현재 관계를 같은 스냅샷에서 읽을 수 있다. 조회 락이 필요 없다. | 독립 트랜잭션과 connection 점유가 추가되며 트랜잭션 범위를 짧게 유지해야 한다. | 선택 |
| 여러 SELECT를 현재 `READ_COMMITTED`로 유지 | 추가 격리 설정과 트랜잭션 경계가 필요 없다. | 문장 사이 커밋을 서로 다른 시점에서 읽어 한 응답의 행동 가능성 사실이 섞일 수 있다. | 제외 |
| 조회 일관성 결정을 ADR-0035에 함께 기록 | ROOM-08 결정을 한 파일에서 볼 수 있다. | 제품 행동 가능성 계약과 PostgreSQL 트랜잭션 선택의 변경 이유·재검토 조건이 섞인다. | 제외 |
| ROOM·참가·대기·Game·User를 하나의 거대한 SQL projection으로 조회 | 한 SQL 문장 안에서 읽기 시점을 맞출 수 있다. | 목록·상세별 projection과 조립 책임이 결합되고 쿼리·응답 변경 비용이 커진다. | 제외 |
| 조회 락 또는 응답 직전 재조회로 최신성을 강제 | 읽는 동안 일부 변경을 막거나 마지막 값으로 덮을 수 있다. | 명령을 불필요하게 대기시키며, 재조회만으로 여러 사실의 동일 스냅샷을 보장하지 못한다. | 제외 |

## 결정

ADR-0012의 상태 보정 시도가 성공해 커밋된 뒤, 목록·상세 ROOM ReadService는 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ`인 독립 Spring 트랜잭션에서 ROOM과 행동 가능성 판정에 필요한 현재 `ACTIVE`·`WAITING` 사실을 읽는다. 호출자 트랜잭션에 합류하지 않으며 상태 보정 쓰기 트랜잭션과 조회 트랜잭션을 겹치지 않는다.

이 트랜잭션에서는 여러 SELECT를 사용할 수 있지만 모두 같은 PostgreSQL 스냅샷을 사용한다. 스냅샷을 획득하기 전에 커밋된 상태 보정·참가·대기 변경은 같은 응답에 포함하고, 획득한 뒤 커밋된 변경은 다음 조회에서 반영한다. `FOR UPDATE`, `FOR SHARE` 등 조회 락은 사용하지 않는다.

ROOM 스냅샷 트랜잭션에는 ROOM과 현재 `ACTIVE`·`WAITING` 사실을 읽는 데이터베이스 작업만 둔다. Game·User 조회와 최종 DTO 조립은 이 트랜잭션 밖에서 수행한다. 따라서 이 ADR의 동일 스냅샷 보장은 ROOM 행동 가능성 사실에 한정하며, Game·User 보강 데이터까지 한 시점으로 묶지 않는다. 응답 전체를 하나의 거대한 projection으로 합치지 않는다.

## 결과

- 얻는 것:
    - 상태 보정 결과와 요청자별 행동 가능성 사실을 한 PostgreSQL 스냅샷에서 읽어 응답 내부의 시점 혼합을 막는다.
    - 조회 락 없이 기존 Repository 조회를 조합할 수 있다.
    - Game·User 조회와 DTO 조립을 분리해 ROOM 트랜잭션을 짧게 유지한다.
- 감수할 비용·위험:
    - `REQUIRES_NEW` 트랜잭션 시작과 connection 점유 비용이 추가된다.
    - 호출자가 이미 connection을 점유한 트랜잭션이면 일시적인 pool wait 위험이 있으므로 조회 조정자는 불필요한 외부 트랜잭션을 열지 않아야 한다.
    - Game·User 보강 데이터는 ROOM 행동 가능성 스냅샷과 같은 시점을 보장하지 않는다.
- 후속 작업:
    - 후속 정본 동기화 이슈 #305에서 목록·상세 조회 흐름과 트랜잭션 책임을 일반 정본에 연결한다.
    - ROOM-08 구현에서 독립 읽기 트랜잭션과 현재 `ACTIVE`·`WAITING` 조회를 구현한다.
    - 실제 PostgreSQL에서 스냅샷 획득 전후 동시 커밋과 조회 락 부재를 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: 거대한 단일 projection, 조회 락, 응답 직전 재조회, Game·User 조회를 포함한 장기 트랜잭션
- 보류 이유: 짧은 `REPEATABLE_READ` 읽기 트랜잭션이 기존 조회 책임을 유지하면서 필요한 ROOM 행동 가능성 사실의 동일 스냅샷을 보장한다.
- 다시 검토할 조건: 재현 가능한 측정에서 독립 트랜잭션의 pool wait 또는 응답 지연이 실제 문제로 확인될 때 단일 SQL 방식의 비용과 이점을 다시 비교한다.

## 참고 자료

- [PostgreSQL 18 트랜잭션 격리](https://www.postgresql.org/docs/18/transaction-iso.html)
- [ADR-0012](0012-room-request-boundary-state-reconciliation.md)
- [ADR-0035](0035-room-status-action-eligibility-separation.md)

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - 목록·상세 ReadService는 상태 보정 커밋 뒤 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ`에서 ROOM과 현재 `ACTIVE`·`WAITING` 사실만 읽는다.
        - Game·User 조회와 DTO 조립은 ROOM 스냅샷 트랜잭션 밖에서 수행한다.
    - 테스트:
        - `room.service.query.RoomActionAvailabilityReadServiceTest`는 목록·상세의 독립 읽기 트랜잭션 애너테이션과 SEARCH-02 조회 경계 보존을 확인한다.
        - `room.service.query.RoomActionAvailabilitySnapshotPostgresTest`는 PostgreSQL에서 중간 `WAITING` 커밋이 같은 목록 스냅샷에 섞이지 않고 조회 락이 없는지 확인한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
