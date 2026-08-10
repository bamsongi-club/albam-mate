# ADR-0056: 사전 전역 보정 없는 ROOM 조회의 PostgreSQL 일관 snapshot

- 상태: 승인됨
- 작성일: 2026-08-10
- 결정일: 2026-08-10
- 관련: [결정 이슈 #561](https://github.com/bamsongi-club/albam-mate/issues/561), [구현 이슈 #557](https://github.com/bamsongi-club/albam-mate/issues/557), [ADR-0012](0012-room-request-boundary-state-reconciliation.md), [ADR-0041](0041-postgresql-room-query-consistent-snapshot.md)
- 대체 대상: ADR-0041
- 후속 ADR: 없음

## 맥락

ADR-0041은 전역 저장 상태 보정이 커밋된 뒤 목록·상세 조회가 독립 `REPEATABLE_READ` 읽기 트랜잭션에서 ROOM과 현재 관계 사실을 읽도록 결정했다. 이 순서는 상태 보정 결과와 응답을 하나의 일관된 스냅샷으로 구성하기 위한 것이었다.

#561은 공개 목록과 내 모임 목록에서 전역 `correctDueRooms(requestTime)`을 제거하고, 고정된 요청 시각으로 유효 상태를 조회하도록 결정했다. 따라서 목록·내 모임은 더 이상 “전역 보정 커밋 후 조회” 순서를 사용하지 않는다.

전역 보정을 제거해도 한 응답의 content·count·현재 `ACTIVE`·`WAITING`·역할 관계는 같은 PostgreSQL snapshot을 사용해야 한다. 특히 Spring Data의 페이지 조회는 content와 count가 별도 SQL로 실행될 수 있으므로 기본 `READ_COMMITTED`에서는 중간 커밋이 한 응답에 섞일 수 있다. 동시에 조회는 Scheduler·참가·대기·채팅 명령을 막지 않아야 한다.

상세·상태 의존 명령·대기·채팅 접근은 대상 ROOM 하나의 저장 상태 보정과 검증을 계속 수행한다. 이 경로는 전역 보정 없는 목록·내 모임 조회와 다른 책임을 가진다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 목록·내 모임도 전역 보정을 커밋한 뒤 기존 snapshot 조회 순서를 유지 | 기존 ADR-0041의 흐름을 그대로 유지한다. | #561의 전역 GET 쓰기 제거 결정을 위반한다. | 제외 |
| 목록·내 모임을 기본 `READ_COMMITTED` 조회로 실행 | 구현 변경이 작다. | content·count·현재 관계 사실이 서로 다른 시점의 커밋을 볼 수 있다. | 제외 |
| 목록·내 모임을 요청 시각 고정 뒤 독립 `REPEATABLE_READ` 읽기 트랜잭션으로 실행 | 전역 쓰기 없이 유효 상태, 페이지, 관계 사실을 하나의 snapshot으로 구성한다. | 독립 트랜잭션과 connection 점유 비용이 있다. | 선택 |
| 거대한 단일 projection 또는 조회 락으로 일관성을 강제 | 단일 SQL 또는 잠금으로 일부 일관성 문제를 줄일 수 있다. | 조회 책임이 결합되거나 명령을 불필요하게 대기시킨다. | 제외 |

## 결정

공개 목록과 내 모임 목록은 다음 순서로 조회한다.

1. QueryService는 요청 전체에 사용할 하나의 `requestTime`을 고정한다.
2. 전역 `correctDueRooms(requestTime)`을 호출하지 않는다.
3. 대응 ReadService는 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ`의 독립 트랜잭션을 시작한다.
4. 같은 트랜잭션에서 고정된 `requestTime`을 사용해 유효 상태를 조회 조건에 적용하고, content·count·현재 `ACTIVE`·`WAITING`·역할 관계와 행동 가능성 판정에 필요한 사실을 읽는다.
5. ReadService는 스냅샷에서 읽은 저장 `Room`, 별도 `effectiveStatus`, 현재 `ACTIVE`·`WAITING`·역할 관계 사실만 반환한다. `effectiveStatus`는 고정된 `requestTime`과 같은 snapshot의 저장 상태·시작 시각으로 결정하며, content·count·필터·정렬·페이지에 적용한 상태 식과 같아야 한다. Game·User 조회와 최종 DTO 조립은 이 트랜잭션 밖에서 수행한다.

공개 목록과 내 모임 목록 모두 content·count·현재 관계 사실에 같은 `REPEATABLE_READ` snapshot을 사용한다. 내 모임의 기존 `REQUIRES_NEW` 기본 격리는 이 결정에 따라 `REPEATABLE_READ`로 변경한다.

유효 상태의 필터·정렬·페이지 조건은 페이지 조회 전에 데이터베이스 조회에 적용한다. DTO 조립 단계는 이미 반환된 유효 상태와 snapshot 사실을 사용하며, 현재 시각을 다시 얻거나 ROOM·참가·대기 관계를 추가로 조회해 다른 시점의 사실을 섞지 않는다.

이 트랜잭션은 `FOR UPDATE`, `FOR SHARE` 등 조회 락을 사용하지 않는다. Scheduler 또는 명령이 snapshot 획득 전 커밋하면 그 결과를 현재 응답에 포함하고, 획득 뒤 커밋하면 다음 요청에서 반영한다. 목록·내 모임 조회는 Scheduler의 후보 선별·cursor·fencing·ROOM별 처리나 저장 상태 보정을 막거나 변경하지 않는다.

상세 조회는 기존 순서를 유지한다. 상세 QueryService는 대상 ROOM 하나의 `correctRoom(roomId, requestTime)` 보정이 커밋된 뒤, 독립 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ` 트랜잭션에서 ROOM과 필요한 현재 관계 사실을 읽는다. 상세는 목록·내 모임의 `effectiveStatus`를 사용하지 않고 대상 ROOM 보정 뒤 저장 상태를 사용한다. 이 ADR은 상세·명령·대기·채팅의 대상 ROOM 보정 책임을 변경하지 않는다.

## 결과

- 얻는 것:
    - 목록·내 모임의 유효 상태, content·count·페이지와 관계 사실을 전역 저장 보정 없이 하나의 일관된 snapshot 결과로 구성한다.
    - Scheduler 및 명령과 병행하면서 조회 락을 사용하지 않는다.
    - Game·User 보강과 DTO 조립을 ROOM 읽기 트랜잭션 밖에 두어 트랜잭션 범위를 짧게 유지한다.
- 감수할 비용·위험:
    - `REQUIRES_NEW`, `REPEATABLE_READ` 트랜잭션의 connection 점유와 pool wait 가능성이 있다.
    - ReadService 반환값에 별도 `effectiveStatus`를 포함해야 한다.
    - snapshot 획득 뒤의 커밋은 현재 응답이 아니라 다음 요청에서 반영된다.
- 후속 작업:
    - #557에서 공개 목록·내 모임의 content/count·관계 사실과 `effectiveStatus`를 같은 snapshot으로 읽어 반환하도록 구현한다.
    - PostgreSQL에서 Scheduler·참가·대기 변경과 병행할 때 한 응답에 중간 커밋이 섞이지 않고 조회 락이 없음을 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: 전역 저장 보정 복구, 거대한 ROOM·참가·대기·Game·User 단일 projection, 조회 락, Scheduler 처리 경계 변경.
- 보류 이유: 짧은 독립 `REPEATABLE_READ` 읽기 트랜잭션이 필요한 ROOM 조회 사실의 일관성을 보장하면서 기존 모듈 책임을 유지한다.
- 다시 검토할 조건: 재현 가능한 측정에서 connection pool wait 또는 응답 지연이 실제 병목으로 확인되거나, snapshot 범위를 줄일 수 있는 동등한 계약·검증 근거가 마련될 때.

## 참고 자료

- [결정 이슈 #561](https://github.com/bamsongi-club/albam-mate/issues/561)
- [구현 이슈 #557](https://github.com/bamsongi-club/albam-mate/issues/557)
- [PostgreSQL 트랜잭션 격리](https://www.postgresql.org/docs/current/transaction-iso.html)
- [ADR-0041](0041-postgresql-room-query-consistent-snapshot.md)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - #557의 승인된 T1~T7과 기존 ROOM-08·09·10 회귀를 구현·테스트·CI로 확인해야 한다.
    - 공개 목록과 내 모임의 content/count 실행계획 및 snapshot·조회 락 부재를 PostgreSQL에서 확인해야 한다.
    - ReadService가 `effectiveStatus`와 관계 사실을 같은 `REPEATABLE_READ` snapshot에서 반환하고, DTO 조립이 ROOM·참가·대기 관계를 다시 읽지 않음을 확인해야 한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
