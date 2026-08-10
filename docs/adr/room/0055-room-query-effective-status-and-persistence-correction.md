# ADR-0055: ROOM 조회 유효 상태와 저장 상태 보정 책임 분리

- 상태: 승인됨
- 작성일: 2026-08-10
- 결정일: 2026-08-10
- 관련: [결정 이슈 #561](https://github.com/bamsongi-club/albam-mate/issues/561), [구현 이슈 #557](https://github.com/bamsongi-club/albam-mate/issues/557), [ADR-0012](0012-room-request-boundary-state-reconciliation.md), [ADR-0035](0035-room-status-action-eligibility-separation.md), [ADR-0036](0036-bounded-room-state-transition-processing.md), [ADR-0041](0041-postgresql-room-query-consistent-snapshot.md)
- 대체 대상: ADR-0012
- 후속 ADR: 없음

## 맥락

ADR-0012는 Scheduler 실행 지연 중에도 현재 시각에 맞는 ROOM 상태와 행동 가능 여부를 반환하기 위해 목록·상세·내 모임 조회와 상태 의존 명령이 요청 경계에서 저장 상태를 보정하도록 결정했다.

현재 목록과 내 모임 조회는 요청마다 하나의 기준 시각을 얻은 뒤 `correctDueRooms(requestTime)`을 호출한다. 이 경로는 현재 결과와 무관한 모든 due ROOM을 대상으로 저장 상태와 버전을 변경하고, 시작 경계의 남은 대기열을 만료하며, 새로 `FINISHED`가 된 ROOM의 종료 이벤트와 동기 부수효과를 수행한다. 따라서 단순 GET의 지연과 실패가 전체 미처리 backlog, DML, 낙관 락 충돌, 대기열 만료와 종료 이벤트에 결합된다.

기존 방식은 당시 정확한 API 상태를 보장하기 위한 의도적 선택이었다. 다만 목록·내 모임 조회는 현재 시각의 정확한 상태를 반환해야 하지만, 그 정확성을 위해 전역 저장 보정을 수행할 필요는 없다. 상세·상태 의존 명령·대기·채팅 접근은 대상 ROOM 하나를 보정해 저장 상태와 부수효과를 최종 정리할 수 있고, Scheduler는 ADR-0036의 제한 후보·ROOM별 독립 처리 경계로 요청이 없는 ROOM을 처리한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 목록·내 모임 조회 전에 전역 저장 보정을 유지 | 저장 상태와 응답 상태가 즉시 일치한다. | 단순 GET이 무관한 due backlog의 DML·충돌·대기열 만료·종료 이벤트에 종속된다. | 제외 |
| Scheduler가 저장 상태를 보정할 때까지 이전 상태를 반환 | 목록 조회가 쓰기와 분리된다. | 현재 시각 기준 상태와 행동 가능 여부의 정확성을 포기한다. | 제외 |
| 목록·내 모임 조회는 유효 상태를 계산하고, 저장 상태 보정은 대상 ROOM 보정과 Scheduler가 담당 | 현재 시각의 정확한 응답을 유지하면서 전역 GET 쓰기를 제거한다. | 저장 상태와 유효 상태가 Scheduler 처리 전까지 일시적으로 다를 수 있다. | 선택 |
| 페이지 조회 뒤 DTO의 상태만 바꿈 | 변경 범위가 작아 보인다. | 필터·정렬·content/count·페이지가 저장 상태 기준으로 남아 응답 계약이 깨진다. | 제외 |

## 결정

공개 목록과 내 모임 목록은 요청마다 하나의 `requestTime`을 고정하고, 저장 상태를 변경하지 않는 조회 유효 상태를 다음 순서로 결정한다.

1. 저장 상태가 `CANCELED` 또는 `FINISHED`이면 그대로 사용한다.
2. 그 외에 `requestTime >= startAt + 24시간`이면 유효 상태를 `FINISHED`로 본다.
3. 그 외에 저장 상태가 `RECRUITING`이고 `requestTime >= startAt`이면 유효 상태를 `CLOSED`로 본다.
4. 그 외에는 저장 상태를 유효 상태로 사용한다.

공개 목록과 내 모임 목록은 이 유효 상태를 필터·정렬·content/count·페이지·응답 `status`·참가 및 대기 가능 여부에 일관되게 사용한다. 내 모임 `MyRoomListItem`의 `chatAvailable`도 같은 유효 상태를 사용하며, 공개 목록 `PublicRoomResponse`에 `chatAvailable` 필드를 추가하지 않는다. ADR-0056의 ReadService가 같은 snapshot에서 반환한 `effectiveStatus`와 관계 사실은 QueryService가 다시 계산하거나 읽지 않고 `RoomActionAvailabilityEvaluator`와 목록·내 모임 DTO 조립에 전달한다. 페이지를 조회한 뒤 DTO의 일부 상태만 바꾸는 방식은 사용하지 않는다. 유효 상태 판정에 필요한 `requestTime`은 조회 조건에 전달하며, 데이터베이스의 실행 시각이나 행마다 다른 현재 시각에 의존하지 않는다.

목록·내 모임 GET은 전역 `correctDueRooms(requestTime)`을 호출하지 않는다. 이 조회는 `rooms` 또는 `room_waitlists` DML, `RoomTerminalStateReached` 발행과 그 동기 부수효과, 전역 due backlog의 ROOM Entity 적재를 수행하지 않는다.

상세와 본인 대기 상태 조회·채팅 접근은 대상 ROOM 한 건의 `correctRoom(roomId, requestTime)` 보정이 커밋된 뒤 읽기·접근 검증을 수행한다. 단건 보정은 최신 ROOM을 다시 읽는 독립 `REQUIRES_NEW` 쓰기 트랜잭션에서 `RECRUITING → CLOSED → FINISHED` 시간 전이를 조건부·멱등으로 적용하고, `CANCELED`·`FINISHED` 또는 동시 변경 상태를 덮어쓰지 않는다. 시작 경계의 대기열 만료와 새 `FINISHED`의 종료 이벤트는 이 대상 보정 경계에서 함께 처리한다.

상태 의존 명령과 대기 명령은 요청마다 고정한 `requestTime`으로 각 독립 `REQUIRES_NEW` 시도 안에서 상태를 먼저 보정하고, 같은 시도에서 원래 업무 규칙을 평가·변경한다. 트랜잭션 경계 밖 조정자는 낙관 락 충돌만 최초 시도를 포함해 최대 세 번 재시도하며, 각 시도는 같은 `requestTime`과 최신 상태를 사용한다. 재시도 예산을 소진하면 상세·상태 의존 명령·대기·채팅의 대상 ROOM 보정 경로만 `409 ROOM_CONCURRENT_MODIFICATION`을 반환한다. 목록·내 모임은 전역 보정을 수행하지 않으므로 이 오류를 반환하지 않는다.

Scheduler는 ADR-0036의 제한 후보와 ROOM별 독립 트랜잭션으로 저장 상태, 대기열 만료와 종료 이벤트를 최종 정리한다. 이 ADR은 Scheduler의 처리 경계, 명령의 재검증, ROOM 상태 열거형, 엔드포인트 경로 또는 성공 응답 JSON 필드 구조를 변경하지 않는다.

## 결과

- 얻는 것:
    - 목록·내 모임이 현재 시각 기준의 정확한 상태를 반환하면서 전역 GET 쓰기와 무관한 backlog 의존성을 제거한다.
    - 공개 목록의 필터·count·페이지와 응답 상태, 행동 가능성과 내 모임 `chatAvailable`이 같은 유효 상태 기준을 사용한다.
    - 종료 이벤트와 대기열 정리는 대상 ROOM 보정 또는 Scheduler의 책임으로 분리된다.
- 감수할 비용·위험:
    - 저장 상태는 Scheduler 처리 전까지 조회 유효 상태보다 이전일 수 있다.
    - 종료 이벤트와 대기열 만료의 시점은 목록 GET이 아니라 대상 ROOM 보정 또는 Scheduler 실행 시점에 결정된다.
- 후속 작업:
    - #557에서 ADR-0056의 `effectiveStatus`와 관계 사실을 행동 가능성 판정·응답 조립에 전달하고, 목록·내 모임 GET에 DML·이벤트·전역 backlog 적재가 없음을 검증한다.
    - 사전 전역 보정 없는 목록·내 모임의 PostgreSQL snapshot 경계는 ADR-0056으로 기록한다.

## 보류 및 재검토

- 지금 하지 않는 것: `correctDueRooms()` 또는 `findDueRooms()` 자체의 삭제·이동, Scheduler 처리 경계 변경, 새 저장 열 또는 새 인덱스 도입, 종료 이벤트의 비동기화.
- 보류 이유: 이번 결정은 목록·내 모임 조회의 전역 저장 보정만 분리하며, 대상 ROOM 보정과 Scheduler의 저장 정리 계약은 유지한다.
- 다시 검토할 조건: Scheduler 또는 대상 보정의 지연이 대기열 만료·종료 이벤트의 운영 요구를 충족하지 못하거나, 재현 가능한 측정에서 유효 상태 조건의 조회 비용이 실제 병목으로 확인될 때.

## 참고 자료

- [결정 이슈 #561](https://github.com/bamsongi-club/albam-mate/issues/561)
- [구현 이슈 #557](https://github.com/bamsongi-club/albam-mate/issues/557)
- [ADR-0012](0012-room-request-boundary-state-reconciliation.md)
- [ADR-0036](0036-bounded-room-state-transition-processing.md)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - #557의 승인된 T1~T7과 기존 ROOM-08·09·10 회귀를 구현·테스트·CI로 확인해야 한다.
    - 목록·내 모임 GET이 전역 저장 보정, ROOM·대기열 DML, 종료 이벤트와 무관함을 PostgreSQL 회귀로 확인해야 한다.
    - SQL의 유효 상태 식이 ReadService 결과·행동 가능성 판정·응답 `status`와 내 모임 `chatAvailable`까지 재계산 없이 전달되고, 공개 목록에 `chatAvailable`이 추가되지 않음을 확인해야 한다.
    - 대상 ROOM 보정의 시간 전이·대기열 만료·종료 이벤트·낙관 락 재시도와 `409 ROOM_CONCURRENT_MODIFICATION` 기존 회귀를 확인해야 한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
