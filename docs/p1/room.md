# P1 ROOM·참가 고도화 명세

이 문서는 P1의 `ROOM-08`~`ROOM-10`, `PART-04`를 독립적으로 구현·검증하기 위한 규칙과 완료 기준을 정의한다. 현재 계약·생산 코드·자동 검증·운영 상태는 [P1 기능 상태 정본](README.md#기능별-현재-상태)을 따른다. P1 공통 규칙은 [P1 명세](../P1-spec.md)를 따르며, P1에서 별도로 변경하지 않은 정원·상태·권한·공개 범위·시간 규칙은 현재 [API](../API.md)·[ERD](../ERD.md)·관련 승인 ADR을 따른다. [P0 완료 문서](../archive/p0/README.md)는 완료 시점의 제품 배경으로만 참조하고 현재 구현 정본으로 사용하지 않는다.

요청·응답·오류는 [API 명세](../API.md), 저장 구조와 제약은 [ERD](../ERD.md), 되돌리기 어렵거나 논쟁적인 기술 선택과 측정 근거는 [ADR](../adr/README.md)에서 관리한다. 기능 규칙은 구현해야 하는 필수 동작이고, 완료 기준은 이슈 완료를 판정하는 필수 검증 계약이며, 제외 범위는 이 문서에서 생성하는 이슈와 PR에 포함하지 않는다.

구현 컨텍스트의 결정 행은 시점을 다음과 같이 구분한다.

- `선행 승인`: 승인 전에는 해당 기능 구현을 시작하지 않는다.
- `착수 전 확정`: [계약·구현 단일 이슈·PR 규칙](README.md#계약과-구현을-같은-이슈pr에서-처리할-때)에 따라 같은 이슈·PR에서 결정할 수 있지만 관련 생산 코드나 스키마보다 먼저 정본에 반영한다.
- `구현·측정 후 확정`: 기준선 구현과 측정을 막지 않으며, 측정 근거로 운영값을 확정해 기능 완료 전에 정본에 반영한다.
- `측정 후 사용자 결정`: 기준선 구현과 측정을 막지 않는다. 결과를 `DECISION_NEEDED`로 제시하고 승인받기 전에는 선택 비교나 후속 구현에 착수하지 않는다.

관련 API·ERD·ADR과 제품 규칙이 충돌하거나 값이 미정이면 구현에서 추측하지 않는다. 선행 승인과 `착수 전 확정`이 끝나기 전에는 계약 준비 완료로 취급하지 않는다.

P0 문서와 코드의 `상태 정합화`는 저장된 상태를 현재 시각에 맞추는 처리를 뜻한다. 이 문서에서는 같은 의미를 더 직접적으로 드러내기 위해 `시간 기반 상태 자동 전환`이라고 부른다.

## ROOM-08 방 상태와 직접 참가·대기 가능 여부 분리

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기능 ID | `ROOM-08` |
| 현행 기준 | [RoomStatus](../API.md#roomstatus), [`joinable` 판정](../API.md#47-publicroomresponse) |
| API 계약 | [PublicRoomResponse](../API.md#47-publicroomresponse), [ROOM 목록 조회](../API.md#room-01-방-목록-조회), [ROOM 상세 조회](../API.md#room-02-방-상세-조회), [내 모임 조회](../API.md#part-03-내-모임-조회) |
| 고도화 이유 | 정원 충족 전 `CLOSED`와 시작 시각 도달 후 `CLOSED`는 같은 상태지만, 전자는 대기를 신청할 수 있고 후자는 신청할 수 없다. |
| 공통 규칙 | [P0 계약 상속](../P1-spec.md#p0-계약-상속), [RoomStatus](../API.md#roomstatus), [ROOMS](../ERD.md#rooms) |
| 데이터 모델 | [ROOMS](../ERD.md#rooms), [PARTICIPATIONS](../ERD.md#participations) |
| 선행 승인 | [ADR-0035 방 생명주기 상태와 요청자별 행동 가능성을 분리](../adr/room/0035-room-status-action-eligibility-separation.md), [ADR-0055 ROOM 조회 유효 상태와 저장 상태 보정 책임 분리](../adr/room/0055-room-query-effective-status-and-persistence-correction.md), [ADR-0056 사전 전역 보정 없는 ROOM 조회의 PostgreSQL 일관 snapshot](../adr/room/0056-postgresql-room-query-snapshot-without-global-pre-correction.md) — `승인됨` |
| 연결 기능 | [PART-04 선착순 대기열과 자동 승격](#part-04-선착순-대기열과-자동-승격) |
| 선행 구현 | PART-04가 소유하는 현재 WAITING 저장·조회 기반([#302](https://github.com/bamsongi-club/albam-mate/issues/302))이 기준 브랜치에 병합된 뒤 ROOM-08 구현을 시작한다. |
| 반영·검증 | [#557](https://github.com/bamsongi-club/albam-mate/issues/557)의 [PR #574](https://github.com/bamsongi-club/albam-mate/pull/574)에서 ADR-0055·0056의 목록·내 모임 유효 상태·snapshot 경계를 생산 코드와 PostgreSQL 회귀로 반영·검증했다. |
| 필수 테스트 계약 | [#303의 최신 전체 ROOM-08-T1~T8](https://github.com/bamsongi-club/albam-mate/issues/303#issuecomment-5177036311) |
| 착수 전 확정 | 없음 |

### 기능 규칙

- `RoomStatus`는 ROOM의 생명주기를, `joinable`과 `waitlistable`은 현재 요청자가 수행할 수 있는 동작을 표현한다.
- `Room.getTotalParticipantCount()`는 저장된 `activeParticipantCount + 1`, `Room.getRemainingRecruitmentSeats()`는 저장된 `capacity - activeParticipantCount`를 반환한다. 두 메서드는 Room 필드만 사용하는 순수 파생 메서드이며 음수 잔여석을 `0`으로 보정하거나 조회·검증·상태 변경·복구를 수행하지 않는다.
- 같은 요청 기준 시각, 목록·내 모임의 유효 상태와 일관된 ROOM·현재 `ACTIVE`·현재 `WAITING` 사실을 `RoomActionAvailabilityFacts`로 모으고, 하나의 `RoomActionAvailabilityEvaluator`가 `joinable`과 `waitlistable`을 담은 `RoomActionAvailability`를 반환한다. 목록·내 모임은 저장 `Room`을 변경하지 않고 유효 상태를 별도 입력으로 전달하며, 상세는 대상 ROOM 보정 뒤 저장 상태를 같은 입력으로 전달한다. 이 세 타입에는 Repository·GameQuery·UserQuery·Clock·`Instant.now()`·상태 변경을 넣지 않는다.
- 비인증 요청자, 주최자, 현재 `ACTIVE` 참가자, `now >= startsAt`, `CANCELED`, `FINISHED`에서는 두 값이 모두 `false`다. 과거 `CANCELED` 참가·대기 관계는 현재 `ACTIVE`·`WAITING`으로 취급하지 않으며 다른 조건을 충족하면 다시 판정 대상이 된다.
- 시작 전 `RECRUITING`이고 남은 모집 좌석이 1 이상인 자격 있는 요청자는 `joinable=true`, `waitlistable=false`다. 시작 전 `CLOSED`이고 남은 모집 좌석이 0이며 현재 `WAITING`이 아닌 자격 있는 요청자는 `joinable=false`, `waitlistable=true`다.
- 이미 현재 `WAITING`인 요청자는 같은 대기 조건에서 두 값이 모두 `false`이며, 현재 `WAITING`은 독립적인 `joinable=true` 조건이 아니다. 지원하지 않는 조합도 두 값이 모두 `false`이고 어떤 조합에서도 동시에 `true`일 수 없다.
- WAITING 물리 저장·조회 기반은 PART-04가 소유한다. ROOM-08은 PART-04가 제공하는 ROOM·사용자 단건 및 여러 ROOM 일괄 현재 `WAITING` 조회 결과를 사용하며 저장 구조나 상태 전이를 소유하지 않는다.
- 목록·내 모임 QueryService는 ReadService가 반환한 유효 상태와 필요한 요청자 사실을, 상세 QueryService는 대상 ROOM 보정 뒤 저장 상태와 필요한 사실을 같은 evaluator에 전달한다. 내 모임은 주최자 또는 현재 `ACTIVE`인 기존 조회 결과를 사용하고 불필요한 WAITING 조회를 추가하지 않으며, 주최자·현재 참가자 결과를 별도 상수 규칙으로 복제하지 않는다.
- 공개 목록과 내 모임 ReadService는 전역 저장 보정 없이 고정된 `requestTime`의 유효 상태를 적용하고, `REQUIRES_NEW`, `readOnly = true`, `REPEATABLE_READ`인 짧고 락 없는 PostgreSQL 트랜잭션에서 ROOM과 필요한 현재 `ACTIVE`·`WAITING`·역할 사실을 같은 스냅샷으로 읽는다. 상세 ReadService는 대상 ROOM 보정이 커밋된 뒤 같은 스냅샷 경계를 사용한다. 스냅샷 뒤 커밋된 변경은 다음 조회에서 반영한다.
- ROOM 스냅샷에는 ROOM과 현재 관계를 읽는 데이터베이스 작업만 둔다. Game·User 조회와 최종 DTO 조립은 스냅샷 밖에서 수행하며, 거대한 단일 projection이나 `FOR UPDATE`·`FOR SHARE` 조회 락을 사용하지 않는다.
- 목록 `PublicRoomResponse`와 내 모임 `MyRoomListItem` 조립은 저장 `Room`을 바꾸지 않고 별도 `effectiveStatus`, Room 파생값과 하나의 공통 availability로 `status`, `participantCount`, `remainingRecruitmentSeats`, `joinable`, `waitlistable`을 조립한다. `chatAvailable`은 `MyRoomListItem`에만 추가로 조립한다. 상세 `PublicRoomResponse`, `ParticipantRoomResponse`는 대상 ROOM 보정 뒤 저장 상태를 사용한다. 의미 입력은 목록 `Room·effectiveStatus·GameSummary·availability`, 내 모임 `Room·effectiveStatus·GameSummary·availability·role·participationStatus`, 상세 공개 `Room·저장 상태·GameSummary·availability`, 상세 참가자 `Room·저장 상태·GameSummary·availability·myRole·host·participants`다.
- `ParticipantRoomResponse`의 기존 역할·주최자·참가자 표시 정보와 `MyRoomListItem`의 역할·참가 상태를 유지한다. 주최·참가 ROOM만 반환하는 내 모임의 두 행동 가능성 값과 주최자·현재 참가자의 상세 응답 값은 모두 `false`다.
- `RoomParticipationResponse.from(Room, ParticipationStatus)`는 Room 파생값만 사용하고 `joinable`·`waitlistable`을 추가하지 않는다. 방 생성·수정 응답도 별도 상수 규칙이 아니라 같은 공통 availability 결과를 사용한다.
- 조회의 행동 가능성은 안내값이며 좌석 예약이나 참가 허가가 아니다. 실제 참가 명령은 최신 Room·참가·대기 관계·상태·시간·카운터·정원·중복을 다시 검증하고, 직접 참가 실패나 `CAPACITY_EXCEEDED`를 WAITING 생성·대기 신청 성공으로 바꾸지 않는다.
- 요청자 관계를 받는 `Room.isJoinableBy(...)`, 행동 가능성을 위한 새 `RoomStatus`나 행동 enum, Factory·Assembler·범용 mapper·추가 policy 계층을 도입하지 않는다. 응답의 `participantCount`·`remainingRecruitmentSeats`와 행동 가능성 판정에 전달하는 잔여석은 Room의 순수 파생 메서드로 계산하며 Service·DTO에 계산식을 복제하지 않는다. SEARCH-02가 `minRemainingSeats`를 페이지네이션 전에 적용하는 `RoomRepository.findPublicRooms(...)` SQL의 `room.capacity - room.activeParticipantCount` 표현은 검색 포함 여부 판정 경계로 유지하며 응답 파생 계산 책임을 가져오지 않는다.

### 완료 기준

- `ROOM-08-AC1` 기존 `RoomStatus` 값과 P0 상태 전이 규칙을 변경하지 않으며, Room의 두 순수 파생 메서드가 저장 필드만으로 전체 표시 인원과 남은 모집 좌석을 정의된 계산식 그대로 반환한다.
- `ROOM-08-AC2` 비인증·주최자·현재 `ACTIVE`·현재 `WAITING`·과거 취소 관계·RoomStatus·남은 좌석·시작 경계를 포함한 모든 판정 조합에서 하나의 evaluator가 정의된 `joinable/waitlistable`을 반환하고 `true/true`를 생성하지 않는다.
- `ROOM-08-AC3` 목록·상세·내 모임 QueryService가 필요한 사실만 수집해 같은 evaluator를 사용하며, 내 모임에 불필요한 WAITING 조회를 추가하거나 주최자·현재 참가자 결과를 별도 규칙으로 복제하지 않는다.
- `ROOM-08-AC4` 목록 `PublicRoomResponse`와 내 모임 `MyRoomListItem`은 저장 `Room`을 변경하지 않은 별도 유효 상태, Room 파생값과 공통 availability로 `status`·필수·non-null `waitlistable`을 포함한 응답을 조립하고, `MyRoomListItem`에만 `chatAvailable`을 추가한다. 상세 `PublicRoomResponse`, `ParticipantRoomResponse`는 대상 ROOM 보정 뒤 저장 상태와 각 응답의 기존 표시 정보를 유지하며, `RoomParticipationResponse`에는 행동 가능성 필드를 추가하지 않는다.
- `ROOM-08-AC5` 공개 목록·내 모임 조회는 전역 저장 보정 없이 고정된 `requestTime`의 유효 상태와 현재 `ACTIVE`·`WAITING`·역할 사실을, 상세 조회는 대상 ROOM 보정 뒤 필요한 현재 관계 사실을 독립 `REPEATABLE_READ` 읽기 트랜잭션에서 같은 PostgreSQL 스냅샷으로 읽는다. 조회 락·거대 projection 없이 Game·User 조회와 DTO 조립을 그 밖에 둔다.
- `ROOM-08-AC6` 조회 행동 가능성을 참가 허가로 사용하지 않고 실제 참가 명령이 최신 상태를 다시 검증하며, 직접 참가 실패를 WAITING 생성으로 바꾸지 않고 생성·수정 응답에도 같은 공통 availability를 사용한다.
- `ROOM-08-AC7` [최신 전체 ROOM-08-T1~T8](https://github.com/bamsongi-club/albam-mate/issues/303#issuecomment-5177036311)로 AC1~AC6의 성공·실패 경로, 무 I/O·금지 구조와 기존 생성·수정·참가·취소·Controller 회귀를 단위·HTTP·PostgreSQL 테스트와 전체 CI에서 검증한다.

| 완료 기준 | 필수 테스트 계약 |
| --- | --- |
| `ROOM-08-AC1` | `ROOM-08-T1`, `ROOM-08-T8` |
| `ROOM-08-AC2` | `ROOM-08-T2` |
| `ROOM-08-AC3` | `ROOM-08-T4`, `ROOM-08-T8` |
| `ROOM-08-AC4` | `ROOM-08-T3`, `ROOM-08-T5`, `ROOM-08-T8` |
| `ROOM-08-AC5` | `ROOM-08-T6` |
| `ROOM-08-AC6` | `ROOM-08-T7` |
| `ROOM-08-AC7` | `ROOM-08-T1`~`ROOM-08-T8` |

### 제외 범위

- 모집 가능 상태를 나타내는 신규 enum
- `RoomStatus`에 대기·진행·연장 상태 추가
- 요청자 관계를 받는 `Room.isJoinableBy(...)`
- Factory·Assembler·범용 mapper·추가 policy 계층
- WAITING 물리 저장 구조와 상태 전이 구현
- 거대한 단일 조회 projection과 조회 락
- 참가 실패 후 자동 대기 전환
- 예상 소요 시간과 변동 가능성 표시
- 룰마스터 프로필, 진행 게임과 주최 이력
- 게임·ROOM 검색과 필터 고도화
- 프론트엔드 버튼, 새로고침과 화면 표시 규칙

---

## PART-04 선착순 대기열과 자동 승격

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기능 ID | `PART-04` |
| 현행 참가 계약 | [PART-01 방 참가·재참가](../API.md#part-01-방-참가재참가), [PART-02 참가 취소](../API.md#part-02-참가-취소) |
| API 계약 | [대기 등록·재신청](../API.md#part-04-대기-등록재신청), [본인 대기 상태 조회](../API.md#part-04-본인-대기-상태-조회), [대기 취소](../API.md#part-04-대기-취소), [참가 취소](../API.md#part-02-참가-취소) |
| 고도화 이유 | 정원이 찬 인기 모임을 기다릴 방법과 참가 취소로 생긴 빈자리의 배정 순서가 없다. |
| 가능 여부 | [ROOM-08 방 상태와 직접 참가·대기 가능 여부 분리](#room-08-방-상태와-직접-참가대기-가능-여부-분리) |
| 공통 규칙 | [P0 계약 상속](../P1-spec.md#p0-계약-상속), [RoomStatus](../API.md#roomstatus), [ROOMS](../ERD.md#rooms), [PARTICIPATIONS](../ERD.md#participations) |
| 저장 계약 | [ROOM_WAITLISTS 목표 저장 계약](../ERD.md#room_waitlists) |
| 필수 ADR | [ADR-0005 방 참가 동시성 제어](../adr/participation/0005-room-participation-optimistic-locking.md), [ADR-0046 ROOM 대기열 단일 최신 상태·조건부 전이·등록 재시도](../adr/participation/0046-room-waitlist-persistence-conditional-transition-retry.md) |

### 기능 규칙

- 대기 신청은 직접 참가와 분리된 명시적 요청이다. 직접 참가 실패를 대기 신청 성공으로 바꾸지 않는다.
- 대기 순서는 서버가 대기 신청을 성공으로 확정한 순서를 기준으로 하는 FIFO다.
- 전역 sequence 순번, 유스케이스 최초 고정 request time과 PART-04 전용 총 3회 재시도 경계는 [ADR-0046](../adr/participation/0046-room-waitlist-persistence-conditional-transition-retry.md)을 따른다.
- 대기열에는 제품 정책상 최대 인원 상한을 두지 않는다. 이는 무제한 규모의 성능을 보장한다는 뜻이 아니다.
- 같은 ROOM과 사용자 조합의 대기는 하나의 레코드로 관리한다. 신청·취소·승격·만료·ROOM 취소는 같은 레코드의 최신 상태를 변경하며, ROOM 데이터가 유지되는 동안 최신 결과를 보존한다. 신청과 취소가 반복될 때마다 별도 이력 레코드를 추가하지 않는다.
- 같은 사용자는 같은 방에 하나의 `WAITING` 관계만 가질 수 있다. 중복 신청은 새 관계를 만들지 않고 기존 순서를 유지한 채 최신 순번을 정상 응답으로 반환한다.
- 본인은 ROOM별 대기 상태를 조회해 `WAITING`, `PROMOTED`, `CANCELED`, `EXPIRED`, `ROOM_CANCELED`를 구분할 수 있다. 현재 순번은 `WAITING`일 때만 1 이상의 값이다.
- 앞 순번 사용자가 취소되거나 승격되면 뒤 사용자의 현재 순번이 일관되게 당겨진다.
- 대기를 취소하면 상태는 `CANCELED`가 된다. 최신 대기 자격을 충족한 사용자는 `CANCELED` 또는 `PROMOTED`에서 다시 신청할 수 있으며, 같은 레코드를 `WAITING`으로 바꾸고 새 순번·신청 시각을 기록해 대기열 맨 뒤에 들어간다. 최초 생성 시각은 보존하며 `EXPIRED`와 `ROOM_CANCELED`는 재활성화하지 않는다.
- 신규 대기와 재신청으로 대기 관계를 `WAITING`으로 활성화할 때는 같은 트랜잭션에서 ROOM 버전을 증가시킨다. 버전 충돌 시 대기 관계 변경을 포함한 전체 요청을 제한 재시도하고, 최신 ROOM이 더 이상 대기 가능하지 않으면 `WAITING`을 남기지 않는다.
- 이미 `WAITING`인 중복 신청과 `WAITING → CANCELED` 대기 취소는 ROOM 버전을 강제로 증가시키지 않는다. 대기 취소와 자동 승격이 경합하면 현재 상태를 조건으로 한 갱신 중 하나만 성공한다.
- 시작 전 현재 `ACTIVE` 참가자가 참가를 취소했을 때 활성 대기자가 있으면 첫 번째 대기자를 자동으로 참가자로 승격하고 대기 상태를 `PROMOTED`로 바꾼다. 승격 후 방은 정원이 찬 `CLOSED` 상태를 유지한다.
- 시작 전 참가 취소 시 활성 대기자가 없을 때만 P0 규칙대로 방을 `RECRUITING`으로 되돌린다.
- 대기 활성화와 참가 취소가 경합하면 ROOM 버전 충돌 뒤 전체 요청을 최신 상태에서 다시 판단한다. 대기 활성화가 먼저 확정되면 참가 취소가 그 대기자를 승격하고, 참가 취소가 먼저 빈자리를 확정하면 대기 활성화는 최신 `RECRUITING` 상태에서 대기 관계를 만들지 않는다. 활성 대기가 있는 ROOM을 `RECRUITING`으로 확정하지 않는다.
- 자동 승격 시점에도 참가 자격, 정원과 활성 대기 관계를 최신 상태로 다시 확인한다.
- 자동 승격은 처리 시점의 첫 `WAITING` 대기자를 대상으로 한다. 앞선 대기 관계가 동시 취소 등으로 이미 다른 상태가 됐다면 그 최신 상태를 유지하고 별도 `SKIPPED` 상태 없이 다음 현재 `WAITING` 대기자를 계속 검사한다.
- 참가 취소만 반영되고 승격이 누락되거나 한 빈자리에 둘 이상이 승격되는 부분 성공 상태를 허용하지 않는다.
- `now >= startsAt`이면 새 대기 신청과 자동 승격을 허용하지 않는다. 남은 `WAITING → EXPIRED` 실행은 [ROOM-09](#room-09-시간-기반-room-상태-자동-전환의-대량-처리-고도화)가 ROOM 상태 보정과 같은 일관성 경계에서 소유한다.
- 주최자가 ROOM을 취소하면 PART-04c의 `RoomStatusChangeExecutor`가 같은 트랜잭션에서 남은 `WAITING` 관계를 `ROOM_CANCELED`로 바꾼다.

### 완료 기준

- `PART-04-AC1` 대기 가능한 사용자의 별도 신청만 하나의 최신 상태 레코드를 `WAITING`으로 만들고, 중복 신청은 순서를 바꾸지 않은 정상 응답을 반환하며 같은 ROOM·사용자 조합의 레코드를 중복 생성하지 않는다.
- `PART-04-AC2` 본인은 최신 대기 상태를 조회하고, `WAITING`이면 앞선 대기자의 취소·승격 뒤 변경된 1 이상의 현재 순번을 확인한다.
- `PART-04-AC3` 대기 취소 상태는 `CANCELED`이며, 허용된 `CANCELED` 또는 `PROMOTED` 재신청은 같은 레코드에 새 순번·신청 시각을 기록하되 최초 생성 시각을 보존해 현재 대기열의 마지막 순번을 받는다. `EXPIRED`와 `ROOM_CANCELED`에서는 재신청하지 않는다.
- `PART-04-AC4` 시작 전 참가 취소로 한 자리가 생기고 활성 대기자가 있으면 처리 시점의 첫 `WAITING` 대기자 한 명만 `ACTIVE` 참가자이자 `PROMOTED` 대기 결과가 되고 ROOM은 `CLOSED`를 유지한다. 앞선 대기자가 동시 취소됐다면 그 상태를 덮어쓰지 않고 다음 현재 `WAITING` 대기자를 승격한다.
- `PART-04-AC5` 시작 전 참가 취소 시 활성 대기자가 없을 때만 ROOM이 `RECRUITING`으로 돌아간다.
- `PART-04-AC6` 참가 취소·대기 취소·자동 승격이 동시에 실행돼도 정원을 초과하지 않고, 사용자별 활성 참가·활성 대기 관계와 FIFO 순서가 일관된다.
- `PART-04-AC7` 시작 시각에 도달하면 남은 대기가 `EXPIRED`, ROOM이 취소되면 남은 대기가 `ROOM_CANCELED`가 되고 이후 승격이 발생하지 않는다.
- `PART-04-AC8` 신규 대기·재신청과 참가 취소의 두 커밋 순서, 대기 취소·승격과 시작·ROOM 취소 경계의 저장 불변식은 PostgreSQL 기반 통합 테스트로 검증된다.
- `PART-04-AC9` 신규·중복·재신청, 상태 조회와 취소의 HTTP 상태·응답·오류가 [API 명세의 PART-04 계약](../API.md#part-04-대기-등록재신청)과 일치한다.
- `PART-04-AC10` 신규 대기·재신청과 참가 취소가 동시에 실행되면 먼저 커밋된 결과에 따라 다른 요청이 전체 재시도한다. 어느 커밋 순서에서도 활성 대기가 있는 `RECRUITING` ROOM이 남지 않는다.

`PART-04-AC7`의 시작 경계 `EXPIRED`는 ROOM-09의 실행·검증 책임이다. 따라서 #302·#325·#326이 모두 병합되더라도 PART-04 전체 구현·검증·배포를 완료로 표시하지 않으며, ROOM-09가 시작 경계 종료와 사용자 조회 결과를 검증한 뒤 전체 완료를 판정한다.

### 제외 범위

- 참가 실패 사용자의 자동 대기 등록
- 대기 인원에 대한 고정 최대 상한
- 주최자 승인, 우선순위·가중치·추첨 방식의 대기열
- 대기 순번 변경 알림과 실시간 전달
- 다른 사용자의 신원이나 전체 대기자 목록 공개
- 본인이 대기 중인 ROOM 목록 조회와 `GET /api/users/me/rooms` 통합
- 운영자용 대기 이력 조회 기능
- 주최자의 방 이탈과 주최권 양도
- 참가자가 있는 ROOM의 내용 수정과 참가자 재수락
- 노쇼·신뢰도·후기·제재 정책

---

## ROOM-09 시간 기반 ROOM 상태 자동 전환의 대량 처리 고도화

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기능 ID | `ROOM-09` |
| 현행 상태 보정 계약 | [방 변경 구조](../ARCHITECTURE.md#방-변경), [ADR-0055 조회 유효 상태와 저장 상태 보정 책임 분리](../adr/room/0055-room-query-effective-status-and-persistence-correction.md) |
| 고도화 이유 | 시간 경계를 지난 ROOM Entity 전체를 한 트랜잭션에서 처리해 대상 증가 시 메모리·트랜잭션 범위가 커지고, 한 ROOM의 실패가 전체 작업에 영향을 주며 실패 대상을 식별하기 어렵다. |
| 상태·시간 규칙 | [P0 계약 상속](../P1-spec.md#p0-계약-상속), [RoomStatus](../API.md#roomstatus), [ROOMS](../ERD.md#rooms) |
| 승인 ADR | [ADR-0055 조회 유효 상태와 저장 상태 보정 책임 분리](../adr/room/0055-room-query-effective-status-and-persistence-correction.md), [ADR-0005 방 참가 동시성 제어](../adr/participation/0005-room-participation-optimistic-locking.md), [ADR-0036 제한 ID·ROOM별 독립 처리](../adr/room/0036-bounded-room-state-transition-processing.md), [ADR-0038 다중 인스턴스 스케줄 실행 조정](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md) |
| 현재 구현 기준선 | [`RoomRepository.findDueRooms`](../../src/main/java/cloud/bamsongi/albammate/room/repository/RoomRepository.java), [`RoomStatusCorrectionExecutor`](../../src/main/java/cloud/bamsongi/albammate/room/statuscorrection/RoomStatusCorrectionExecutor.java), [ROOM-09c 현행 일괄 처리 기준선 측정](../measurements/room-09-bounded-processing-baseline.md) |
| 제한 처리 구현 | [`RoomStatusCorrectionCandidateSelector`](../../src/main/java/cloud/bamsongi/albammate/room/statuscorrection/RoomStatusCorrectionCandidateSelector.java)가 ROOM ID projection을 논리적 due 순서로 합치고, [`RoomStatusCorrectionCoordinator`](../../src/main/java/cloud/bamsongi/albammate/room/statuscorrection/RoomStatusCorrectionCoordinator.java)가 ROOM별 Executor·cursor CAS를 조정한다. |
| 연결 기능 | [PART-04 선착순 대기열과 자동 승격](#part-04-선착순-대기열과-자동-승격) |
| 진행 상태 저장 | [`ROOM_STATUS_CORRECTION_PROGRESS`](../ERD.md#room_status_correction_progress) 단일 행 |
| 착수 전 확정 | 없음 |
| 구현·측정 후 확정 | [ROOM-09d 후보 측정](../measurements/room-09-bounded-processing-baseline.md)으로 한 번당 ID 수 `100`, 실행시간 경고 `180s`, `lockAtMostFor` `10m`을 확정했고 실행 주기는 기존 `15m`(jitter `3m`)을 유지한다. [#504](https://github.com/bamsongi-club/albam-mate/issues/504)에서 실행당 최대 batch 수 `100`을 확정해 한 실행은 최대 `10,000` ROOM을 시도한다. 상한 뒤 같은 turn cutoff의 후보가 실제로 남으면 마지막 cursor를 보존해 다음 claim이 재개하고 ROOM 전용 WARN을 한 번 남긴다. 후보가 비었을 때만 cursor를 wrap한다. `lockAtMostFor`는 여전히 한 실행의 최장 시간을 보장하는 값이 아니며, 재시도는 공용 낙관적 잠금 재시도를 그대로 쓴다. |
| 측정 후 사용자 결정 | 실패 backoff·격리 비교와 제한 범위의 조건부 DB 직접 갱신 비교 여부. 기준선 결과를 `DECISION_NEEDED`로 제시하고 승인 전에는 비교 구현에 착수하지 않음 |

### 실행·진행 상태 계약

- ROOM Scheduler 전용 잠금 이름은 `room-status-correction`이다. 병합된 [PR #366](https://github.com/bamsongi-club/albam-mate/pull/366)이 제공하고 [#289](https://github.com/bamsongi-club/albam-mate/issues/289)가 소유하는 공용 `global/scheduling` port와 PostgreSQL ShedLock adapter를 읽기 전용으로 사용하며, ROOM은 공용 `SHEDLOCK` 스키마·adapter를 수정하지 않는다.
- `lockAtMostFor`와 실행시간 경고 기준은 서로 독립된 ROOM 전용 명시 입력이다. 둘 중 하나라도 없으면 기동에 실패하며, 잠금을 얻은 ROOM 실행이 경고 기준을 초과할 때만 ROOM 전용 WARN 관측 신호를 한 번 남긴다.
- `ROOM_STATUS_CORRECTION_PROGRESS`는 `job_name = 'room-status-correction'`인 행 하나만 가진다. `turn_cutoff`, 마지막으로 시도한 선별 키인 `cursor_due_at`·`cursor_room_id`, 모든 진행 상태 변경의 CAS 값인 `progress_version`, 실행 주체 fencing 값인 `execution_generation`, `updated_at`을 저장한다. cursor 두 컬럼은 함께 `NULL`이거나 함께 값이 있어야 하고, 값이 있으면 `cursor_due_at <= turn_cutoff`여야 한다.
- 제한 후보 수는 `application.yml`의 운영 기본값 `100`으로 설정한다. 실행당 최대 batch 수도 양수 필수 운영값 `100`으로 설정해 한 실행의 최대 시도량을 `10,000` ROOM으로 제한한다. `max-batches-per-run`이 없거나 0 이하이면 기동에 실패하고, `candidate-limit`이 없으면 스케줄러는 전체 Entity 조회로 대체하지 않고 실행을 건너뛴다.
- 전진 Flyway 마이그레이션은 이 단일 행을 `turn_cutoff = NULL`, cursor 두 컬럼 `NULL`, `progress_version = 0`, `execution_generation = 0`으로 생성한다. 행이 없거나 둘 이상인 상태를 런타임에서 자동 복구하지 않고 설정 오류로 실패시킨다.
- ShedLock을 얻은 Scheduler 실행은 후보를 읽기 전에 짧은 독립 트랜잭션에서 진행 행을 `FOR UPDATE`로 읽고 `execution_generation`과 `progress_version`을 각각 1 증가시켜 실행 세대를 점유한다. 최초 실행처럼 `turn_cutoff`이 `NULL`이면 이번 Scheduler의 고정 `requestTime`으로 초기화한다. cursor가 `NULL`인 완료된 순회를 새 실행이 이어받으면 `turn_cutoff`을 기존 값과 `requestTime` 중 뒤 시각으로 전진시킨다.
- 후보 선별 뒤 cursor 전진과 wrap-around는 각각 별도의 짧은 독립 트랜잭션에서 `job_name`, 실행 세대와 기대 `progress_version`이 모두 일치할 때만 갱신하고 `progress_version`을 1 증가시킨다. 조건부 갱신이 0건이면 늦은 실행 주체로 판정해 이후 ROOM을 처리하지 않고 실행을 끝낸다.
- cursor는 ROOM 처리 성공·무변경·격리된 실패와 관계없이 해당 후보를 시도한 뒤 선별에 사용한 `(논리적 처리 예정 시각, roomId)`로 전진한다. ROOM 트랜잭션이 커밋된 뒤 cursor 커밋 전에 프로세스가 종료되면 같은 ROOM을 다시 선별할 수 있는 at-least-once 방식이며, 최신 상태 재판정과 멱등 전이로 같은 결과에 수렴한다. cursor를 먼저 전진시켜 미처리 ROOM을 건너뛰는 방식은 허용하지 않는다.
- cursor 뒤 후보가 없으면 같은 CAS 경계에서 cursor를 `NULL`로 회전하고 이번 실행을 끝낸다. 같은 cutoff를 다시 여는 즉시 반복은 금지하고 다음 Scheduler 실행에 맡긴다. 한 실행은 최대 `100` batch만 처리한다. 상한에 도달하면 마지막 cursor 뒤 후보를 한 건만 다시 확인해, 실제 잔여 후보가 있으면 cursor를 보존하고 다음 Scheduler 실행에 맡기며 ROOM 전용 WARN을 한 번 남긴다. 남은 후보가 없을 때만 wrap한다.
- Scheduler의 제한 선별·진행 상태는 API 요청 경계 상태 보정에 사용하지 않는다. 공개 목록·내 모임은 [ADR-0055](../adr/room/0055-room-query-effective-status-and-persistence-correction.md)의 고정된 `requestTime` 유효 상태를 사용하고 전역 저장 보정을 수행하지 않는다. 상세·상태 의존 명령·대기·채팅 접근은 대상 ROOM 보정과 오류 계약을 유지하며, ShedLock 미획득이나 Scheduler cursor 때문에 현재 상태 판정을 생략하지 않는다.

### 기능 규칙

- `RECRUITING → CLOSED → FINISHED`의 시간 조건과 순서는 P0 규칙을 그대로 유지한다. `CANCELED`, `FINISHED`와 동시에 확정된 다른 최종 상태를 덮어쓰지 않는다.
- API 요청 경계에서 현재 상태를 사용하는 역할과 요청이 없는 ROOM을 내부 스케줄러가 정리하는 역할을 유지하며 두 경로는 같은 전이 규칙을 사용한다.
- 모든 애플리케이션 인스턴스가 ROOM 상태 자동 전환 Spring Scheduler를 등록하되, 유효한 잠금 임대 동안에는 현재 PostgreSQL ShedLock을 얻은 실행 주체만 새 후보 선별을 시작한다. 잠금을 얻지 못한 인스턴스는 기다리지 않고 해당 실행을 건너뛰며, 이전 실행 종료 전에 임대가 만료돼 이전·신규 실행 주체가 겹치면 영속 진행 상태 불변식을 따른다.
- 스케줄 잠금 획득·해제 트랜잭션은 cursor 진행 상태와 각 ROOM의 독립 처리 트랜잭션에 결합하지 않는다. 잠금은 ROOM 상태·버전 검사나 참가·대기 불변식을 대체하지 않는다.
- 자동 전환 대상은 한 번에 모든 ROOM Entity를 읽지 않고, 제한된 수의 ROOM ID 단위로 선별한다.
- 제한 ID는 순회 시작 때 고정한 순회 기준 시각(`turn cutoff`) 이하의 due ROOM만 대상으로, 논리적 처리 예정 시각과 `roomId`를 오름차순으로 비교하는 결정적 순서와 cursor 회전으로 선별한다. 이 키는 불변값으로 저장하지 않고 각 선별 시점의 남은 작업에서 재계산하며, `roomId`로 동률을 해소한다. 논리적 처리 예정 시각은 `RECRUITING` 상태 전환과 시작 경계 대기열 종료의 `startsAt`, `CLOSED → FINISHED` 전환의 `startsAt + 24시간`을 모두 포괄하며, 이미 `CLOSED`여도 시작 경계 대기열 종료가 남아 있으면 `startsAt`을 사용한다. 한 ROOM의 시간 기반 작업을 완료한 뒤 남은 다음 논리적 처리 예정 시각은 직전 선별에 사용한 시각과 같거나 뒤로만 이동하거나 더 이상 존재하지 않아야 한다.
- 한 순회에서는 `(논리적 처리 예정 시각, roomId)`가 cursor 뒤이면서 논리적 처리 예정 시각이 순회 기준 시각 이하인 ROOM만 선별한다. cursor는 ROOM 처리 뒤 재계산한 키가 아니라 처리 성공 여부와 무관하게 마지막으로 시도한 선별 위치 다음으로 전진한다. 이 범위에서 선별 결과가 비면 현재 순회를 마치고 cursor를 처음으로 회전하며, 다음 선별을 시작할 때 새로운 순회 기준 시각을 고정한다.
- 순회 시작 뒤 새로 due가 된 ROOM은 현재 순회를 연장하지 않고 다음 순회부터 선별한다. 스케줄러 실행이 계속되고 한 순회 기준 시각의 due ROOM 집합이 유한하면, 신규 due ROOM이 계속 유입되거나 반복 실패 ROOM이 선별 한도를 점유해도 현재 순회는 유한하게 끝나고 cursor 앞의 due ROOM도 다음 순회에서 다시 처리 기회를 얻는다. cursor와 진행 중인 순회의 기준 시각은 애플리케이션 메모리에만 두지 않고 재시작과 ShedLock 실행 주체 변경 뒤에도 같은 순회를 이어가는 영속 저장 경계에 보관한다.
- ShedLock 임대 만료로 이전·신규 실행 주체가 겹쳐도 순회 기준 시각은 뒤로 이동하지 않고, 같은 순회 안의 cursor는 마지막으로 확정된 위치보다 앞으로만 이동한다. wrap-around는 최신 진행 상태에서 현재 순회의 남은 선별 대상이 없음을 확인한 뒤 더 뒤의 순회 기준 시각과 처음 cursor를 원자적으로 확정할 때만 허용한다. 각 실행 주체는 선별 때 읽은 progress version 또는 실행 generation을 갱신 조건으로 제출한다. 신규 실행 주체가 진척을 확정한 뒤 도착한 이전 실행 주체의 cursor 전진·순회 전환은 적용하지 않고 거절해 진척 유실·역행·조기 wrap-around를 막는다. 행 잠금으로 쓰기를 직렬화해도 최신 진행 상태와 기대 version·generation 비교 없이 stale 갱신을 커밋해서는 안 된다.
- 선별된 각 ROOM은 독립된 처리 단위에서 최신 상태와 버전을 다시 읽고 전이 여부를 판단한다.
- 한 ROOM의 실패가 이미 성공한 다른 ROOM의 전환을 롤백하거나 같은 실행에서 아직 처리하지 않은 다른 선별 ROOM의 처리를 중단하지 않는다. 실패한 ROOM ID와 원인을 식별할 수 있고 해당 ROOM만 다시 처리할 수 있어야 한다.
- `startsAt`에 도달한 ROOM은 상태 전환과 함께 대기열을 종료한다. 정원 충족으로 이미 `CLOSED`였던 ROOM도 시작 경계에서 대기열 종료 대상이 된다.
- ROOM 상태 전환만 반영되고 대기열이 활성 상태로 남거나, 대기열만 종료되고 P0 상태 판정이 어긋나는 부분 성공 상태를 허용하지 않는다.
- 제한 처리의 후보 수, 성공·실패 수, 처리량, 실행시간과 데이터베이스 비용을 측정한다.
- 한 번당 ID 수와 반복·재시도·실행 주기의 운영 고정값은 측정과 사용자 승인으로 확정한다. 현재 구현을 비교 기준선으로 남기고 제한 처리의 후보값을 같은 조건에서 측정한 뒤 초기 운영값을 확정한다.
- 제한된 ROOM별 처리가 측정된 병목으로 확인된 뒤에만 제한 범위의 조건부 DB 직접 갱신 비교 여부를 사용자에게 묻는다. 에이전트가 비교·채택을 자동으로 결정하지 않는다.
- 직접 갱신 비교 또는 채택이 승인되면 상태 조건, 버전, 대기열 일관성, 변경 ROOM 식별과 실패 관측을 같은 수준으로 증명하고 ADR에 기록한다.
- 실패 backoff·격리는 cursor 회전 기준선에서 영구 실패 ROOM의 반복 시도 비용이나 재시도 폭주가 측정된 경우에만 사용자에게 비교 여부를 묻는다. 승인 전에는 실패 상태·다음 시도 시각·격리 해제 정책을 구현하지 않는다.
- 동적 Trigger·Misfire·영속 Job 복구 요구가 생기기 전에는 Quartz 클러스터나 외부 작업 큐를 도입하지 않고, 다중 인스턴스라는 이유로 ROOM 업무 락을 Redis 분산 락으로 교체하지 않는다.

### 완료 기준

- `ROOM-09-AC1` 시작 시각과 시작 24시간 후의 ROOM 상태 판정이 P0 규칙과 같고, 오래된 `RECRUITING` ROOM도 한 처리에서 허용된 순서로 최종 상태까지 전환된다.
- `ROOM-09-AC2` 목록·상세·내 모임 조회와 상태 의존 명령이 제한 처리 방식에서도 기준 시각의 현재 상태와 참가·대기 가능 여부를 사용한다.
- `ROOM-09-AC3` 자동 전환 작업이 상한 없는 전체 ROOM Entity 목록을 한 번에 적재하지 않고 제한된 ID 단위로 진행된다.
- `ROOM-09-AC4` 각 ROOM이 독립적으로 커밋되며, 특정 ROOM의 실패 대상과 원인을 식별하고 다른 ROOM의 성공 결과를 유지한 채 재시도할 수 있다. 한 ROOM의 실패 뒤에도 같은 실행에서 남은 선별 ROOM 처리를 계속한다.
- `ROOM-09-AC5` 시작 시각에 도달한 ROOM은 기존 상태가 `RECRUITING` 또는 정원 충족 `CLOSED`인지와 관계없이 활성 대기열이 종료되고 이후 자동 승격이 발생하지 않는다.
- `ROOM-09-AC6` 자동 전환과 참가·대기 변경이 동시에 실행돼도 최종 상태를 덮어쓰거나 정원·참가·대기열 불변식을 깨지 않는다.
- `ROOM-09-AC7` 현재 구현과 제한 처리 후보별 후보 수, 성공·실패, 처리량, 실행시간과 데이터베이스 비용을 같은 조건에서 재현 가능하게 측정하고, 결과를 근거로 초기 운영값을 확정한다. 직접 DB 갱신 비교가 필요해 보이면 결과와 질문을 `DECISION_NEEDED`로 제시하고 승인 전에는 비교 구현에 착수하지 않는다.
- `ROOM-09-AC8` 순회 시작 때 고정한 기준 시각과 각 선별 시점에 재계산한 `(논리적 처리 예정 시각, roomId)` 순서에서 cursor가 성공 여부와 무관하게 전진한다. cursor보다 큰 키의 신규 due ROOM이 계속 유입돼도 현재 순회가 유한하게 끝나고 cursor가 처음으로 회전하여, cursor 앞의 실패 ROOM을 포함한 다음 due ROOM에 다음 순회의 처리 기회가 돌아간다.
- `ROOM-09-AC9` 영속 cursor와 순회 기준 시각의 저장·갱신·wrap-around와 장애 재선별·복구를 PostgreSQL 기반 통합 테스트로 검증한다. 연속 신규 due ROOM 유입, 인스턴스 재시작, ShedLock 실행 주체 변경, cursor·순회 경계 갱신과 ROOM 처리 사이의 장애 뒤에도 특정 due ROOM을 영구히 건너뛰거나 현재 순회를 무한히 연장하거나 같은 앞순번 반복으로 다른 ROOM을 무기한 지연시키지 않는다.
- `ROOM-09-AC10` `local`의 애플리케이션 두 대가 같은 스케줄을 등록해도 유효한 잠금 임대 동안에는 현재 ShedLock을 얻은 실행 주체만 새 ROOM 후보 선별을 시작한다. 잠금 미획득 인스턴스는 해당 실행을 건너뛰고, 잠금 보유 인스턴스 종료·임대 만료 뒤 다른 인스턴스가 영속 cursor와 순회 기준 시각을 이어서 처리한다. 임대가 이전 실행 종료보다 먼저 만료되는 중첩 경로는 `ROOM-09-AC11`로 검증한다.
- `ROOM-09-AC11` `local`에서 잠금 임대가 만료된 이전 실행 주체와 잠금을 새로 얻은 실행 주체가 같은 영속 진행 상태를 동시에 갱신해도, 신규 실행 주체가 진척을 확정한 뒤 도착한 이전 실행 주체의 cursor 전진과 wrap-around 순회 전환은 기대 progress version·실행 generation 불일치로 적용되지 않고 거절된다. 행 잠금을 선택한 구현도 잠금 획득 뒤 이 조건을 다시 확인하며 단순 직렬화만으로 테스트를 통과할 수 없다. 이 경쟁에서도 순회 기준 시각과 cursor가 역행하거나 갱신을 잃거나 조기에 wrap-around하지 않고, 중첩 실행이 해소된 뒤 현재 순회가 유한하게 끝나 모든 due ROOM에 처리 기회가 돌아간다.

### 제외 범위

- `RoomStatus` 전이 규칙과 자동 종료 24시간 기준 변경
- 정확한 시각의 물리적 DB 갱신 SLA
- 외부 스케줄링 시스템·작업 큐와 Quartz 클러스터
- Redis 분산 락과 기존 `Room.version` 낙관 락 교체
- 측정 근거 없는 전체 조건부 DB 직접 갱신 전환
- 방 상태 자동 전환과 무관한 데이터 정리 작업

---

## ROOM-10 동시성과 락 전략 실증

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기능 ID | `ROOM-10` |
| 현행 기준 | [ADR-0005 방 참가 동시성 제어](../adr/participation/0005-room-participation-optimistic-locking.md) |
| 고도화 이유 | 낮은 충돌 빈도는 P0의 가정이며, 대기 신청·취소·승격까지 명령이 늘어난 뒤의 충돌률·재시도·응답시간은 측정되지 않았다. |
| 저장 불변식 | [ERD 필수 제약과 계산 규칙](../ERD.md#필수-제약과-계산-규칙) |
| 오류 계약 | [`ROOM_CONCURRENT_MODIFICATION`](../API.md#104-방-오류) |
| 검증 환경 | [ADR-0010 H2와 PostgreSQL 테스트 경계](../adr/platform/0010-h2-postgresql-test-boundary.md) |
| 측정 계약·원자료 | [ROOM-10a·10b 동시성 기준선 측정 계약](../measurements/room-10-measurement-contract.md)과 그 문서가 연결하는 보존 JSON |
| 연결 기능 | [PART-04 선착순 대기열과 자동 승격](#part-04-선착순-대기열과-자동-승격), [ROOM-09 시간 기반 상태 자동 전환](#room-09-시간-기반-room-상태-자동-전환의-대량-처리-고도화) |
| 착수 전 확정 | 기준선 측정의 데이터 규모, 동시 사용자 수, 반복 횟수, 측정 도구와 로그·메트릭 정의. 측정 전 임의의 성능 합격 수치를 만들지 않음 |
| 측정 후 사용자 결정 | 확정됨. [#495](https://github.com/bamsongi-club/albam-mate/issues/495)에서 낙관적 락 기준선 결과를 제시하고, 비관적 락 비교와 최종 잠금 전략 ADR을 배포 후로 이관하기로 결정했다. 재검토 조건은 참가 취소·자동 승격 경로에서 재시도 소진 `409`가 서로 다른 ROOM에서 반복 관측되는 경우이며, 현재 관측 근거는 `RoomOptimisticLockRetrier`의 기존 WARN 로그에 남는 `event`·`roomId`·`attempt`다. 별도 운영 metric은 대시보드·알림·집계 자동화 같은 구체적인 요구가 생길 때 별도 이슈로 재검토한다. |

### 기능 규칙

- 현재 낙관적 락과 제한 재시도를 기준선으로 삼고, 측정 전에 잠금 전략을 교체하지 않는다.
- 마지막 좌석의 동시 참가, 참가와 대기 신청의 경합, 참가 취소와 자동 승격, 대기 취소와 자동 승격, 시작 경계의 자동 전환과 참가·대기 변경을 최소 대표 시나리오로 검증한다.
- 시나리오별 전체 요청 수, 성공·업무 실패·동시성 실패 수, 낙관적 락 충돌 수와 충돌률, 재시도 횟수, 응답시간과 데이터베이스 비용을 수집한다.
- 모든 측정 뒤 `active_participant_count = ACTIVE 참가 관계 수`, 정원 초과 금지, 사용자별 중복 활성 참가·활성 대기 금지, 빈자리당 한 명 승격과 ROOM 상태 규칙을 검증한다.
- 측정 데이터, 동시 요청 조건, 실행 방법과 결과는 다른 팀원이 같은 환경에서 재현할 수 있도록 남긴다.
- 이번 단계의 완료 여부는 임의의 응답시간·충돌률 합격선을 통과했는지가 아니라 현재 낙관적 락의 성능과 저장 불변식을 재현 가능하게 측정했는지로 판정한다.
- [#495](https://github.com/bamsongi-club/albam-mate/issues/495)의 결정에 따라 낙관적 락 기준선 결과를 제시한 뒤에도 비관적 락 비교는 P1에서 착수하지 않는다. 배포 후 참가 취소·자동 승격 경로에서 재시도 소진 `409`가 서로 다른 ROOM에서 반복될 때만 기존 WARN 로그의 `event`·`roomId`·`attempt`를 근거로 별도 비교를 재검토하며, 에이전트가 비교를 자동으로 시작하지 않는다.
- 낙관적 락 유지 또는 다른 전략 채택의 결론, 근거, 트레이드오프와 재검토 조건은 비교를 다시 착수할 때 ADR에 기록한다. 별도 운영 metric은 구체적인 대시보드·알림·집계 자동화 요구가 생길 때 별도 이슈로 다룬다.

### 완료 기준

- `ROOM-10-AC1` 현재 낙관적 락 기준선에서 확정한 대표 동시 요청 시나리오를 반복 실행할 수 있다.
- `ROOM-10-AC2` 시나리오별 충돌률, 재시도, 결과 분포, 응답시간과 데이터베이스 비용이 재현 가능한 결과로 기록된다.
- `ROOM-10-AC3` 모든 실행 뒤 ROOM·참가·대기열 저장 불변식이 PostgreSQL에서 유지됨을 검증한다.
- `ROOM-10-AC4` 재시도 소진과 업무 규칙 위반이 서로 구분되고 기존 오류 우선순위를 깨지 않는다.
- `ROOM-10-AC5` 낙관적 락 기준선 결과를 제시하고 비관적 락 비교를 P1에서 착수하지 않는다. [#495](https://github.com/bamsongi-club/albam-mate/issues/495)의 결정에 따라 배포 후 참가 취소·자동 승격 경로에서 재시도 소진 `409`가 서로 다른 ROOM에서 반복될 때만 기존 WARN 로그의 `event`·`roomId`·`attempt`를 근거로 비교를 재검토한다.
- `ROOM-10-AC6` 최종 잠금 전략의 유지·변경 판단과 다시 검토할 조건은 [#495](https://github.com/bamsongi-club/albam-mate/issues/495)에 기록한다. P1에서 비관적 락 비교와 ADR을 진행하지 않으며, 배포 후 재검토 조건이 충족되어 비교를 다시 착수할 때 ADR을 후속으로 작성한다.

### 제외 범위

- 측정 전 비관적 락으로 교체
- Redis·분산 락과 외부 메시지 브로커 도입
- 절대적인 선착순 실행 순서와 무제한 부하 성능 보장
- ROOM 외 도메인의 부하·동시성 검증
- 알림·실시간·검색·캐시 성능 검증
