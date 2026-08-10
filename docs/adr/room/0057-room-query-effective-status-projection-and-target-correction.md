# ADR-0057: ROOM 조회 유효 상태 projection과 대상 ROOM 보정 계약

- 상태: 승인됨
- 작성일: 2026-08-10
- 결정일: 2026-08-10
- 관련: [결정 이슈 #561](https://github.com/bamsongi-club/albam-mate/issues/561), [구현 이슈 #557](https://github.com/bamsongi-club/albam-mate/issues/557), [ADR-0055](0055-room-query-effective-status-and-persistence-correction.md), [ADR-0056](0056-postgresql-room-query-snapshot-without-global-pre-correction.md), [ADR-0036](0036-bounded-room-state-transition-processing.md)
- 대체 대상: ADR-0055의 목록·내 모임 응답 조립 및 대상 ROOM 보정·오류 계약 범위, ADR-0056의 목록·내 모임 ReadService 유효 상태 반환 범위
- 후속 ADR: 없음

## 맥락

ADR-0055는 목록·내 모임의 전역 저장 보정을 유효 상태 조회로 분리하고, 상세·명령·대기·채팅의 대상 ROOM 보정과 Scheduler 책임은 유지했다. ADR-0056은 목록·내 모임의 고정 `requestTime`과 PostgreSQL snapshot 경계를 정했다. 그러나 #557이 목록·내 모임의 유효 상태를 행동 가능성 판정과 응답 조립까지 전달하려면, 조회 결과에 저장 `Room`을 바꾸지 않은 별도 유효 상태를 포함하는 경계와 대상 ROOM 경로가 계속 지켜야 할 시간 전이·재시도·오류 계약을 함께 명시해야 한다.

저장 `Room`만 반환한 뒤 DTO 조립에서 상태를 임의로 다시 계산하면 조회 조건에 사용한 유효 상태와 어긋날 수 있다. 반대로 유효 상태를 Entity에 저장하거나 목록 GET에서 대상 ROOM 보정을 호출하면 ADR-0055의 전역 무DML 경계를 위반한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 저장 `Room`과 관계 사실만 반환하고 유효 상태를 DTO 조립 단계에서 다시 계산 | ReadService 반환값이 작다. | 조회 조건·행동 가능성·응답의 상태 식이 중복돼 서로 어긋날 수 있다. | 제외 |
| 유효 상태를 저장 `Room`에 반영한 뒤 기존 DTO를 사용 | DTO 전달이 단순해 보인다. | 목록·내 모임 GET이 DML과 대상 ROOM 부수효과를 수행한다. | 제외 |
| 조회 snapshot에서 별도 유효 상태를 반환하고 대상 ROOM 보정 계약을 명시적으로 유지 | 목록 응답과 필터의 상태 식을 일치시키고 대상 경로의 기존 동시성 계약을 보존한다. | ReadResult와 조립 입력에 유효 상태를 명시해야 한다. | 선택 |

## 결정

공개 목록과 내 모임 ReadService는 하나의 `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ` snapshot에서 읽은 저장 `Room`, 해당 ROOM의 별도 `effectiveStatus`, 현재 `ACTIVE`·`WAITING`·역할 관계 사실을 함께 반환한다. `effectiveStatus`는 고정된 `requestTime`과 같은 snapshot의 저장 상태·시작 시각으로 결정하며, content·count·필터·정렬·페이지에 적용한 상태 식과 같아야 한다. 이 값은 저장 Entity를 변경하지 않고 QueryService가 `RoomActionAvailabilityEvaluator`와 목록·내 모임 DTO 조립에 전달한다.

목록 `PublicRoomResponse`는 `effectiveStatus`, 인원·잔여 좌석, `joinable`, `waitlistable`을 조립하고, 내 모임 `MyRoomListItem`은 같은 값에 `chatAvailable`을 추가한다. Game·User 조회와 최종 DTO 조립은 ROOM snapshot 밖에서 수행하며, 조립 단계에서 현재 시각이나 ROOM·참가·대기 관계를 다시 읽지 않는다. 상세는 대상 ROOM 보정 뒤 저장 상태를 사용하며 목록·내 모임의 `effectiveStatus`를 사용하지 않는다.

상세와 본인 대기 상태 조회·채팅 접근은 대상 ROOM의 `correctRoom(roomId, requestTime)` 보정이 커밋된 뒤 뒤의 읽기·접근 검증을 수행한다. 단건 보정은 최신 ROOM을 다시 읽는 독립 `REQUIRES_NEW` 쓰기 트랜잭션에서 `RECRUITING → CLOSED → FINISHED` 시간 전이를 조건부·멱등으로 적용하고, `CANCELED`·`FINISHED` 또는 동시 변경 상태를 덮어쓰지 않는다. 시작 경계의 대기열 만료와 새 `FINISHED`의 종료 이벤트는 이 대상 보정 경계에서 함께 처리한다.

상태 의존 명령과 대기 명령은 요청마다 고정한 `requestTime`으로 각 독립 `REQUIRES_NEW` 시도 안에서 상태를 먼저 보정하고, 같은 시도에서 원래 업무 규칙을 평가·변경한다. 트랜잭션 경계 밖 조정자는 낙관 락 충돌만 최초 시도를 포함해 최대 세 번 재시도하며, 각 시도는 같은 `requestTime`과 최신 상태를 사용한다. 상세·상태 의존 명령·대기·채팅의 대상 ROOM 보정에서 재시도 예산을 소진하면 `409 ROOM_CONCURRENT_MODIFICATION`을 반환한다. 목록·내 모임은 전역 보정을 수행하지 않으므로 이 충돌 오류를 반환하지 않는다.

Scheduler는 ADR-0036의 제한 후보와 ROOM별 독립 트랜잭션으로 같은 저장 상태 전이, 대기열 만료와 종료 이벤트를 최종 정리한다. 이 결정은 Scheduler의 처리 경계, 명령의 재검증, ROOM 상태 열거형, 엔드포인트 경로 또는 성공 응답 JSON 필드 구조를 변경하지 않는다.

## 결과

- 얻는 것:
    - 목록·내 모임의 유효 상태가 조회 조건·행동 가능성·응답에서 같은 snapshot 사실로 전달된다.
    - 공개 목록 응답에 `chatAvailable`을 추가하지 않고, 내 모임에만 해당 접근 가능 값을 제공한다.
    - 대상 ROOM 보정 경로의 시간 전이·대기열·종료 이벤트·낙관 락 오류 계약이 목록 GET 변경과 분리된다.
- 감수할 비용·위험:
    - ReadResult와 evaluator·DTO 조립 입력에 별도 유효 상태를 전달해야 한다.
    - 저장 상태는 Scheduler 처리 전까지 목록·내 모임 유효 상태보다 이전일 수 있다.
- 후속 작업:
    - #557에서 목록·내 모임 ReadService 결과와 evaluator·DTO 조립에 `effectiveStatus`를 연결하고, content·count·필터·정렬·페이지에 같은 상태 식을 적용한다.
    - #557에서 목록·내 모임 GET에 전역 저장 보정, ROOM·대기열 DML, 종료 이벤트와 `ROOM_CONCURRENT_MODIFICATION`이 없고 대상 ROOM 경로의 기존 회귀가 유지되는지 PostgreSQL로 확인한다.

## 보류 및 재검토

- 지금 하지 않는 것: 저장 `Room`에 유효 상태를 영속화, 목록·내 모임 GET의 대상 ROOM 보정 호출, 조회 락·거대한 단일 projection 도입
- 보류 이유: 별도 projection이 읽기 경계와 기존 Entity 책임을 유지하면서 같은 snapshot의 상태 계약을 전달한다.
- 다시 검토할 조건: 유효 상태 projection과 SQL 상태 식의 중복이 측정·회귀에서 유지 비용을 크게 만들거나, snapshot 반환 구조가 목록·내 모임의 성능 병목으로 확인될 때

## 참고 자료

- [결정 이슈 #561](https://github.com/bamsongi-club/albam-mate/issues/561)
- [구현 이슈 #557](https://github.com/bamsongi-club/albam-mate/issues/557)
- [ADR-0055](0055-room-query-effective-status-and-persistence-correction.md)
- [ADR-0056](0056-postgresql-room-query-snapshot-without-global-pre-correction.md)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - #557의 승인된 T1~T7과 기존 ROOM-08·09·10 회귀를 구현·테스트·CI로 확인해야 한다.
    - 목록·내 모임 `effectiveStatus`가 SQL의 content·count·필터·정렬·페이지와 응답·행동 가능성에 같은 기준으로 전달되는지 PostgreSQL 회귀로 확인해야 한다.
    - 대상 ROOM 보정이 고정 `requestTime`의 시간 전이, 대기열 만료·종료 이벤트, 낙관 락 재시도와 오류 계약을 계속 지키는지 기존 회귀로 확인해야 한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
