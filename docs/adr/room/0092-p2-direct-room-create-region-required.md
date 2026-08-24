# ADR-0092: P2 일반 Room 생성 region 필수화와 홍대 fallback 폐기

- 상태: 승인됨
- 작성일: 2026-08-24
- 결정일: 2026-08-24
- 관련: [#1077](https://github.com/bamsongi-club/albam-mate/issues/1077), [#1077 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/1077#issuecomment-5395172677), [ADR-0076](0076-p2-room-region-closed-set-and-compatibility.md), [ROOM-03 API](../../API.md#room-03-방-생성), [Room ERD](../../ERD.md)
- 대체 대상: ADR-0076의 일반 직접 `POST /api/rooms` region 생략·홍대 fallback 호환 범위
- 후속 ADR: 없음

## 맥락

ADR-0076은 Room 지역을 `홍대`, `강남`, `건대`, `잠실`의 닫힌 집합으로 제한하고, 기존 클라이언트 호환을 위해 일반 직접 Room 생성의 `region` 생략을 허용하며 홍대로 해석하도록 결정했다. AI 초안·확인형 생성도 호환 기간에는 지역을 생략할 수 있게 했다.

그러나 일반 모임 생성 화면은 게임 중심과 사람 중심 모두 지역을 선택할 수 있어야 하며, 사용자가 선택한 지역을 서버가 홍대로 바꾸면 화면·요청·저장값의 의미가 달라진다. `region`은 생활권이고 `place`는 상세 장소이므로 두 값도 별도로 전달·저장해야 한다.

이 결정은 first-party가 사용하는 일반 직접 `POST /api/rooms` 계약만 다룬다. AI 초안·확인형 생성과 기존 Room 행의 호환 경계까지 한 번에 바꾸면 별도의 데이터·클라이언트 전환 계획이 필요하다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 직접 생성에서도 `region` 생략을 허용하고 홍대로 해석 | 기존 요청과 즉시 호환됨 | 사용자의 지역 선택이 유실되고 잘못된 지역이 저장될 수 있음 | 제외 |
| 직접 API만 `region` 필수로 전환하고 DB default·factory·AI 호환은 유지 | 선택값을 보존하면서 변경 범위를 API 경계로 제한하고 기존 내부·AI fixture를 보호함 | region을 보내지 않는 기존 직접 client는 400을 받으므로 배포 전 client 전환이 필요함 | 선택 |
| DB default 제거와 기존 fixture·데이터 전환을 함께 수행 | SQL 직접 입력도 누락을 숨기지 않음 | AI·내부 factory·기존 fixture와 운영 데이터 전환 범위가 커지고 별도 backfill·rollback 계획이 필요함 | 보류 |
| 네 지역 밖의 자유 문자열도 허용 | 지역 확장이 쉬움 | API·DB·UI·검색의 값 집합이 흔들림 | 제외 |

## 결정

- 일반 직접 `POST /api/rooms`의 `region`은 필수다. `null`, 누락, 허용되지 않은 값은 `VALIDATION_ERROR`로 요청 경계에서 거절하며 Room을 저장하지 않는다.
- 직접 생성에서 허용하는 wire value는 `홍대`, `강남`, `건대`, `잠실`이다. `Region` enum과 기존 DB `CHECK`의 닫힌 집합을 그대로 사용한다.
- 일반 생성 화면은 `GAME_FOCUSED`와 `PERSON_FOCUSED` 모두 네 지역을 선택할 수 있게 한다. 생성 화면에는 홍대를 초기 선택값으로 넣지 않고, 모임 유형을 전환해도 선택한 지역을 유지한다.
- 일반 직접 생성 payload에서는 선택한 `region`과 상세 `place`를 독립 필드로 전달하고, 응답·저장값도 각각 보존한다.
- 직접 API의 서비스는 요청의 유효한 `region`을 Room 생성 factory에 명시적으로 전달한다. 직접 API 경로에서 region을 홍대로 대체하지 않는다.
- 기존 `rooms.region` DB default와 Room factory overload는 기존 내부 fixture·AI 경계를 위해 유지한다. 이 default는 직접 API의 누락 입력을 허용하는 계약이 아니며, API validation을 통과한 직접 요청만 생성 서비스에 도달한다.
- AI 초안·확인형 Room 생성의 region optional·홍대 정규화 규칙은 ADR-0076과 AI 계약을 따른다. 이 ADR에서 변경하지 않는다.
- region 수정 `PATCH`, 지역 검색 filter, 기존 Room 행 backfill, 지역 집합 추가와 자유 텍스트는 범위에서 제외한다.

## 결과

- 얻는 것:
  - 두 일반 생성 유형에서 사용자가 선택한 네 지역이 UI·요청·응답·저장값에 일관되게 남는다.
  - `region`과 `place`의 생활권·상세 장소 의미를 분리한다.
  - 기존 DB default와 AI·내부 fixture 호환을 건드리지 않고 직접 API의 fallback만 폐기한다.
- 감수할 비용·위험:
  - region을 보내지 않는 기존 직접 client는 배포 뒤 `400 VALIDATION_ERROR`를 받는다. first-party client와 API 문서를 함께 배포해야 한다.
  - DB에 남아 있는 default는 SQL 직접 입력이나 다른 경로에서 계속 사용될 수 있으므로, 직접 API 외 경로의 의미를 이 ADR로 확장해서 해석하면 안 된다.
  - 지역을 추가하려면 enum·API validation·UI 선택지·DB CHECK와 테스트를 함께 검토해야 한다.
- 적용:
  - `CreateRoomRequest`에서 region을 필수·닫힌 집합으로 검증하고 `RoomCreateService`가 명시값을 전달한다.
  - `RoomFormFields`와 일반 생성 payload에 네 지역 선택을 추가하고 edit/PATCH 경로에는 region을 추가하지 않는다.
  - `docs/API.md`와 `docs/ERD.md`에 직접 생성의 필수 region 계약을 반영한다. `docs/archive/p0/room.md`는 역사 기록으로 보존한다.

## 적용·rollback

- 적용 순서:
  1. first-party 일반 생성 UI가 region을 선택·전송하도록 배포한다.
  2. 같은 변경에서 일반 `POST /api/rooms`의 required validation과 API·ERD 문서를 배포한다.
  3. 400 `VALIDATION_ERROR`에서 region 누락·허용 밖 값의 발생을 관찰한다.
- rollback:
  - 이 변경은 Flyway migration이나 DB schema를 변경하지 않으므로 코드·문서 변경을 이전 버전으로 되돌릴 수 있다.
  - rollback 시 직접 API DTO·서비스의 required validation과 생성 UI의 필수 선택을 함께 되돌려 요청 계약과 화면을 일치시킨다.
  - rollback을 위해 DB default를 제거하거나 기존 Room 행을 재작성할 필요는 없다.

## 검증

- 상태: 검증됨
- 근거:
  - 구현: 일반 생성 DTO·서비스가 region을 필수·닫힌 집합으로 검증하고, 생성 UI가 두 유형 모두에서 네 지역을 선택·유지한다. AI·PATCH·migration은 변경하지 않았다.
  - 계약: [#1077 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/1077#issuecomment-5395172677)의 T1~T6를 최신 전체 필수 테스트로 사용했다.
  - 테스트: T1·T2·T3·T6 H2 service tests, T4·T5 controller tests, T3 frontend Vitest, T1·T2 PostgreSQL 저장 경계 테스트가 통과했다.
  - 산출물: backend test manifest가 PostgreSQL required와 T1~T6 exact selector를 검증했고, frontend test·build와 Markdown 링크 검사가 통과했다.

## 보류 및 재검토

- 지금 하지 않는 것: DB default 제거, 기존 Room 행 backfill, AI 초안·확인형 region optional 변경, PATCH·검색 filter, 자유 문자열과 새 지역 추가.
- 다시 검토할 조건: region을 보내지 않는 외부·legacy client의 전환 계획이 확정될 때, AI 경로와 일반 직접 생성의 계약을 통합할 때, DB default 제거 또는 기존 데이터 정규화가 필요해질 때, 국제화나 허용 지역 추가 요구가 생길 때.

## 참고 자료

- [ADR-0076](0076-p2-room-region-closed-set-and-compatibility.md)
- [Issue #1077](https://github.com/bamsongi-club/albam-mate/issues/1077)
- [승인된 T1~T6 코멘트](https://github.com/bamsongi-club/albam-mate/issues/1077#issuecomment-5395172677)
- [ROOM-03 방 생성 API](../../API.md#room-03-방-생성)
- [Room ERD](../../ERD.md)
