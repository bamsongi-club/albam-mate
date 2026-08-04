# ADR-0035: 방 생명주기 상태와 요청자별 행동 가능성을 분리

- 상태: 승인됨
- 작성일: 2026-07-31
- 결정일: 2026-08-03
- 관련: [결정 이슈 #301](https://github.com/bamsongi-club/albam-mate/issues/301), [정본화 이슈 #304](https://github.com/bamsongi-club/albam-mate/issues/304), [후속 정본 동기화 #305](https://github.com/bamsongi-club/albam-mate/issues/305), [P1 ROOM-08](../../p1/room.md#room-08-방-상태와-직접-참가대기-가능-여부-분리), [P0 방 상태 계약](../../archive/p0/P0-spec.md#방-상태roomstatus), [API RoomStatus](../../API.md#roomstatus), [API PublicRoomResponse](../../API.md#47-publicroomresponse), [ADR-0012](0012-room-request-boundary-state-reconciliation.md), [ADR-0037](../participation/0037-room-waitlist-latest-state-atomic-promotion.md), [ADR-0041](0041-postgresql-room-query-consistent-snapshot.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

P0는 `RoomStatus`로 ROOM의 생명주기를 표현하고, 공개 ROOM 응답의 `joinable`로 현재 요청자가 직접 참가할 수 있는지를 표현한다. P1 대기열을 도입하면 같은 `CLOSED` 상태라도 정원 충족으로 시작 전에 닫힌 ROOM은 대기를 신청할 수 있고, 시작 시각에 도달해 닫힌 ROOM은 대기를 신청할 수 없다.

대기 가능 여부는 ROOM 상태만으로 결정되지 않는다. 현재 시각과 정원뿐 아니라 요청자가 주최자인지, 현재 참가자인지, 이미 대기 중인지도 함께 판단해야 한다. 이 차이를 `RoomStatus`에 넣으면 ROOM 생명주기와 요청자별 행동 가능성이 한 enum에 섞이고, 상태와 사용자 관계 사이에 유효하지 않은 조합이 생길 수 있다.

이 결정은 다음 기준을 우선한다.

- P0의 `RoomStatus` 값과 전이, 기존 `joinable` 의미를 유지한다.
- 같은 입력에서 직접 참가와 대기 신청이 동시에 허용되는 잘못된 조합을 막는다.
- 요청자 관계를 포함한 최종 판정은 서버가 책임진다.
- 대기열 도입이 기존 API 소비자에게 주는 변경을 최소화한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `RoomStatus`에 정원 마감·대기 가능 상태를 추가 | 응답의 상태 하나만 보고 현재 모집 형태를 알기 쉽다. | ROOM 생명주기와 요청자별 행동을 섞고 상태 수와 전이 조합이 늘어난다. 요청자마다 다른 대기 가능 여부는 여전히 표현하지 못한다. | 제외 |
| `joinable`을 제거하고 직접 참가·대기 등 모든 행동을 하나의 신규 enum으로 표현 | 허용 행동이 늘어날 때 하나의 필드로 확장할 수 있다. | 기존 API 계약을 깨고, 앞으로 서로 독립적으로 허용될 수 있는 행동을 단일 값으로 표현하기 어렵다. 현재 두 행동만을 위해 변경 범위가 크다. | 제외 |
| `RoomStatus`와 `joinable`을 유지하고 `waitlistable`을 추가해 서버의 공통 판정에서 계산 | 생명주기와 행동 가능성의 책임을 분리하고 기존 직접 참가 계약을 유지한다. 두 값의 조합을 서버에서 통제할 수 있다. | 응답 필드와 판정 조합 테스트가 늘고, 공개 응답을 만들 때 요청자 관계와 활성 대기 여부가 필요하다. | 선택 |
| 클라이언트가 상태·정원·시각으로 대기 가능 여부를 추론 | 서버 응답 필드를 추가하지 않아도 된다. | 클라이언트가 알 수 없는 요청자 관계와 최신 동시성 상태를 반영하지 못하고, 소비자마다 규칙이 중복된다. | 제외 |

## 결정

`RoomStatus`는 ROOM 생명주기만 나타내며 P0의 값과 전이를 유지한다. 기존 `joinable`의 이름과 직접 참가 가능 여부라는 의미도 유지한다.

`Room.getTotalParticipantCount()`와 `Room.getRemainingRecruitmentSeats()`는 Room 필드만 사용하는 순수 파생 메서드로 둔다. 이 메서드에 요청자 관계 조회나 행동 가능성 판정을 넣지 않는다.

공개 ROOM 응답에 요청자별 대기 신청 가능 여부를 나타내는 `waitlistable`을 추가한다. 서버는 같은 기준 시각과 일관된 ROOM·현재 `ACTIVE` 참가·현재 `WAITING` 대기 사실을 `RoomActionAvailabilityFacts`로 모으고, 하나의 `RoomActionAvailabilityEvaluator`가 `joinable`과 `waitlistable`을 담은 `RoomActionAvailability`를 함께 반환하게 한다. 두 값은 동시에 `true`일 수 없으며, 허용되는 행동이 없으면 모두 `false`일 수 있다.

요청자 관계를 `Room` Entity에 넣는 `Room.isJoinableBy(...)`, 행동 가능성을 위한 신규 enum, Factory·Assembler·범용 mapper·추가 policy 계층은 도입하지 않는다. 요청자별 판정은 위 사실 값과 evaluator의 책임으로 한정한다.

`RoomActionAvailability`는 [PublicRoomResponse](../../API.md#47-publicroomresponse), [ParticipantRoomResponse](../../API.md#48-participantroomresponse), [MyRoomListItem](../../API.md#410-myroomlistitem)에 공통 적용한다. 참가 명령 결과인 `RoomParticipationResponse`에는 행동 가능성 필드를 추가하지 않는다.

조회 응답의 행동 가능성은 안내값이다. 실제 참가 명령은 최신 ROOM·참가·대기 상태를 다시 검증하며, 직접 참가 요청이 좌석 경합으로 실패하더라도 해당 요청을 대기 신청으로 바꾸지 않는다. 대기 관계는 별도의 명시적 대기 신청이 성공했을 때만 생성한다.

상태 보정 커밋 뒤 ROOM과 현재 `ACTIVE`·`WAITING` 사실을 같은 PostgreSQL 스냅샷에서 읽는 트랜잭션 계약은 [ADR-0041](0041-postgresql-room-query-consistent-snapshot.md)이 소유한다. `WAITING` 물리 저장·조회 기반 구현은 PART-04가 소유하고 저장 모델과 자동 승격 일관성 결정은 [ADR-0037](../participation/0037-room-waitlist-latest-state-atomic-promotion.md)을 따른다. 이 ADR은 ADR-0037의 결정·상태·대체 관계를 바꾸지 않는다.

## 결과

- 얻는 것:
    - ROOM 생명주기와 요청자별 행동 가능성의 의미가 분리된다.
    - 기존 `joinable` 소비자는 의미 변경 없이 대기열 기능을 점진적으로 수용할 수 있다.
    - 직접 참가와 대기 신청의 잘못된 동시 허용 조합을 서버의 한 판정 지점에서 방지할 수 있다.
- 감수할 비용·위험:
    - 공개 응답 조립에 활성 대기 관계가 추가로 필요할 수 있다.
    - 상태·시각·정원·요청자 관계 조합이 늘어나 판정 테스트가 증가한다.
- 후속 작업:
    - 후속 정본 동기화 이슈 #305에서 ROOM-08 관련 일반 정본을 이 결정과 ADR-0041에 맞춘다.
    - PART-04의 현재 `WAITING` 조회 기반과 ROOM-08 공통 판정·응답 조립을 구현한다.
    - 두 가능 여부의 상호 배타성, 시작 경계와 세 응답의 동일 판정을 단위·통합 테스트로 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: 모집 형태를 위한 신규 `RoomStatus` 또는 행동 enum 도입
- 보류 이유: P1 범위의 직접 참가와 대기 신청은 기존 `joinable`과 추가 `waitlistable`로 구분할 수 있고, 생명주기 상태를 늘릴 근거가 없다.
- 다시 검토할 조건: 독립적으로 허용되는 행동이 계속 늘어나 Boolean 조합의 의미가 불명확해지거나, 새 API 버전에서 행동 가능성 계약을 일괄 변경할 필요가 생길 때

## 참고 자료

- 이 문서의 맥락·대안으로 갈음

## 검증

- 상태: 검증됨
- 근거:
    - 구현:
        - `Room`의 순수 인원·잔여석 파생 메서드와 `RoomActionAvailabilityEvaluator`가 요청자별 직접 참가·대기 가능 여부를 함께 판정한다.
        - `PublicRoomResponse`, `ParticipantRoomResponse`, `MyRoomListItem`은 같은 `RoomActionAvailability`를 사용하고 `RoomParticipationResponse`는 행동 가능성 필드를 추가하지 않는다.
    - 테스트:
        - `room.service.RoomActionAvailabilityContractTest`는 파생값, 상호 배타적 판정, 세 공개 DTO 조립을 확인한다.
        - ROOM 컨트롤러 테스트는 세 공개 DTO의 HTTP JSON 직렬화 경계를 확인한다.
        - `room.service.command.RoomParticipationServiceTest`는 직접 참가 실패가 대기 생성으로 바뀌지 않는 기존 명령 계약을 확인한다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
