# ADR-0075: P2 AI 초안·확인형 Room command와 멱등성

- 상태: 승인됨
- 작성일: 2026-08-17
- 결정일: 2026-08-18
- 관련: [#794](https://github.com/bamsongi-club/albam-mate/issues/794), [#796](https://github.com/bamsongi-club/albam-mate/issues/796), [AI-01 명세](../../p2/assistant.md), [ROOM-03 API](../../API.md#room-03-방-생성)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

현재 `POST /api/rooms`는 구조화된 요청을 받으면 Room을 즉시 만든다. AI-01은 자연어 추천과 방 생성 정보를 한 번에 처리하지만, 자연어 입력만으로 Room을 만들면 사용자의 확인·재시도·동시 요청을 안전하게 보장할 수 없다.

기존 `RoomCreated → ChatRoom` 생성 흐름과 인증·인가 경계는 유지하면서, 확인 전에는 부수효과가 없고 확인 후에는 같은 요청이 정확히 하나의 결과로 수렴해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| AI가 기존 `POST /api/rooms`를 즉시 호출 | 구현이 짧음 | 확인·멱등성·초안 소유가 없어 재시도로 중복 방이 생길 수 있음 | 제외 |
| 브라우저가 방 payload를 조립해 직접 생성 | 화면 구현이 단순함 | 서버의 game/host/권한 검증과 재시도 안전성을 우회할 위험 | 제외 |
| 사람 간 `chat`/`ChatMessage`를 초안 저장소로 사용 | 기존 저장소 재사용 | room membership·sender FK·WebSocket 수명과 결합됨 | 제외 |
| 서버 임시 초안 + 확인형 `room.contract` command | 확인·소유·만료·멱등 경계를 서버가 통제함 | 초안 저장과 확인 API가 추가됨 | 선택 |

## 결정

### 추천과 초안

- `RECOMMEND`는 검색 조건이 유효할 때 후보 집합만 반환한다. 검색 조건이 없으면 `NEEDS_INPUT`으로 추천 조건만 되묻고, 활성 초안·Room을 만들지 않는다.
- `CREATE_ROOM`으로 전환해 방 생성 정보를 채운 경우에만 서버가 15분 임시 초안을 만든다. 초안 만료는 생성 시각부터 계산하며 새 명령은 기존 활성 초안을 `DISCARDED`로 종결하고 새 초안을 만든다. 동의 철회·명시 폐기·만료도 기존 활성 초안을 종결한다.
- 사용자당 활성 초안은 하나다. `UNIQUE (user_id) WHERE status = 'ACTIVE'` 부분 유일 제약으로 보장하며 별도 `active_slot` 컬럼이나 `SUPERSEDED` 상태는 추가하지 않는다.
- 상세 장소는 확인 카드에서 사용자가 직접 입력한다. 모델 결과·raw prompt에서 장소를 추출하거나 저장하지 않는다.

### 확인·멱등성·동시성

- confirm은 `Idempotency-Key`와 draft version을 함께 사용한다. key는 원문이 아닌 SHA-256 hash로 저장한다.
- 미확인 초안의 key hash는 초안 만료 시각까지, 확인된 결과의 key hash와 Room 결과 참조는 확인 시각부터 24시간까지 보존한다. 같은 범위의 재시도는 AI 기능이 활성인 동안 이 보존 기간 안에서만 원래 결과를 재생한다. 기능이 비활성이면 재생 전에 `503`으로 fail-closed 하며, 이는 기존 Room·ChatRoom 결과를 바꾸지 않는다.
- 만료 기록의 정리 주체는 별도 scheduler가 아니라 같은 사용자의 다음 초안 생성·확인 명령이다. 명령은 초안 행을 잠근 같은 트랜잭션에서 고정한 요청 시작 시각으로 만료를 판정하고, `expires_at`이 지난 그 사용자의 기록을 모두 삭제한 뒤 새 key를 별도 행으로 등록한다. 다건 삭제와 단건 등록을 분리하므로 어느 경우에 행을 교체할지 구현이 따로 판단하지 않는다. 이 정리는 멱등성 기록만 지우고 참조하던 Room·ChatRoom과 초안 결과 참조는 삭제하지 않는다.
- 따라서 AI를 다시 쓰는 사용자에게는 보존이 끝난 key hash가 유일 제약에 남지 않는다. 반대로 이후 AI-03 명령을 다시 실행하지 않는 사용자의 만료 기록은 정리 시점이 오지 않아 남는다. 잔존량은 그 사용자가 마지막 활동 구간에서 성공한 confirm 횟수만큼이며 별도 상한을 두지 않는다. confirm은 provider를 호출하지 않으므로 [ADR-0074](../platform/0074-p2-ai-provider-consent-and-operation-boundary.md)의 provider 호출 quota가 이 횟수를 제한하지 않는다. P2는 이 잔존을 알려진 한계로 받아들이고 별도 batch를 두지 않는다.
- 보존 기간이 지난 뒤의 같은 key 재시도는 새 확인으로 처리하며 이전 Room 결과를 재생하지 않는다.
- 멱등성 범위는 최소 `(currentUserId, draft/resource, operation)`이다. 기능 gate를 통과한 요청에서 같은 범위의 이미 확인된 key는 초안 상태·만료·draft version 검사보다 먼저 같은 Room·ChatRoom 결과를 반환한다.
- 다른 사용자·draft/resource·operation의 같은 key, 다른 key, 오래된 version, 만료 초안은 새 Room을 만들지 않는다.
- 초안 생성·수정·확인은 모두 PostgreSQL에서 대상 `USERS` 행 → 초안 행 → 멱등성 기록 순서로만 잠그며, confirm은 그 안에서 version을 조건으로 갱신한다. 사용자 단위 선행 잠금은 같은 사용자의 초안 생성·수정·확인이 멱등성 기록을 동시에 정리하지 않도록 직렬화하고, 활성 초안 유일 제약을 잠금 순서나 배타성 근거로 쓰지 않는다. 같은 범위의 key에는 유일 제약을 함께 두어 재시도와 동시 요청이 방 하나로 수렴하도록 한다.
- 공개 오류 의미는 다른 key·범위 밖 key·동시성 충돌 `409`, 만료 초안 `410`, 타인 또는 없는 초안 `404`로 고정한다.
- 만료는 명령·조회·장소 입력·확인 요청의 시작 시각에 판정한다. 만료 판정은 `ACTIVE` 초안에만 적용하고, 이미 종결된 `CONFIRMED`·`DISCARDED` 초안은 상태 판정이 만료 판정보다 먼저다. 응답에 남은 시간을 노출하지 않으며 별도 cleanup scheduler는 P2 범위에 넣지 않는다.

### Room command와 원자성

- assistant는 `room.contract`의 확인형 command만 호출한다. 현재 사용자 ID는 요청 인자가 아니라 command 내부의 인증 컨텍스트에서 읽는다.
- 기존 `RoomCreated → ChatRoom` 트랜잭션을 재사용한다. Room 생성이 실패하면 ChatRoom도 남기지 않는다.
- 확인형 command는 `room.contract`가 소유하며 `room`은 `chat` Entity·Repository를 직접 참조하지 않는다. 같은 트랜잭션의 동기 `RoomCreated` handoff를 `chat` listener가 채팅방 저장 후 채우고, command는 그 `chatRoomId`를 결과에 포함한다. handoff가 완료되지 않으면 전체 생성 결과를 롤백한다.
- 기존 즉시 생성 `POST /api/rooms`는 유지하고 확인형 경로와 공존시킨다. 공개 요청·응답·오류·초안 저장 구조는 API·ERD·아키텍처 문서와 구현 이슈에서 반영한다.

## 결과

- 얻는 것:
  - 확인 전 Room·ChatRoom·참가 관계를 0개로 유지한다.
  - 동일 확인 재시도와 동시 요청이 정확히 하나의 Room·ChatRoom으로 수렴한다.
  - 기존 Room 생성 트랜잭션과 수동 Room 경로를 보존한다.
- 감수할 비용·위험:
  - 초안·멱등 record와 만료·보존 정책이 추가된다.
  - 확인 결과 key는 최대 24시간 hash와 결과 참조를 보존한다.
  - PostgreSQL row lock·version·유일 제약의 충돌 경로를 별도로 검증해야 한다.
- 후속 작업:
  - `AI-03a`에서 초안 테이블·migration·확인형 API·Room command·오류 응답을 구현한다.
  - API·ERD·아키텍처에 기존 즉시 생성과 확인형 생성의 공존 계약을 반영한다.

## 보류 및 재검토

- 지금 하지 않는 것: 자연어만으로 즉시 Room 생성, 사람 간 chat 재사용, cleanup scheduler, 기존 Room 데이터 재작성.
- 보류 이유: 구현 이슈에서 공개 endpoint·세부 schema·migration·rollback을 현재 API·ERD와 함께 확정해야 한다.
- 다시 검토할 조건: 24시간 재시도 보존이 실제 사용 흐름과 맞지 않거나, row lock·version 경합이 검증에서 병목으로 확인되거나, 재방문하지 않는 사용자의 만료 기록 잔존량이 운영에서 문제가 되는 경우.

## 참고 자료

- [AI-01 명세](../../p2/assistant.md)
- [AI-D02·D03 결정 이슈 #796](https://github.com/bamsongi-club/albam-mate/issues/796)
- [ROOM-03 방 생성 API](../../API.md#room-03-방-생성)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
  - 초안·멱등 record schema, migration, row lock/version과 동시 confirm 테스트
  - 공개 API·오류 응답·기존 Room 생성 회귀 검증
