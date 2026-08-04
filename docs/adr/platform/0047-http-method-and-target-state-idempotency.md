# ADR-0047: 수정·관계 설정 API의 HTTP 메서드와 방 종료 멱등성을 확정

- 상태: 승인됨
- 작성일: 2026-08-04
- 결정일: 2026-08-04
- 관련: [API 명세](../../API.md), [P1 명세](../../P1-spec.md), [SEARCH-03 정본](../../p1/search.md#search-03-사용자별-해-본-게임), [ADR-0012](../room/0012-room-request-boundary-state-reconciliation.md), [ADR-0028](../game/0028-explicit-user-played-game-state.md), [결정 이슈 #308](https://github.com/bamsongi-club/albam-mate/issues/308), [PR #363](https://github.com/bamsongi-club/albam-mate/pull/363)
- 대체 대상: [ADR-0022](0022-p0-update-api-http-method-and-finish-idempotency.md)
- 후속 ADR: 없음

## 맥락

ADR-0022는 기존 리소스의 일부를 수정하는 P0 API에 `PATCH`를 유지하고, 클라이언트가 리소스 전체 표현을 결정해 교체할 때만 `PUT`을 사용하도록 정했다. 또한 방 종료 목표가 이미 달성된 경우 같은 종료 요청을 무변경 성공으로 처리하도록 정했다.

이후 P1 `SEARCH-03`은 경로로 식별한 본인의 해 본 게임 관계를 직접 표시하고 취소한다. 결정 이슈 #308은 `PUT /api/users/me/played-games/{gameId}`와 같은 경로의 `DELETE`를 채택하고, 두 요청 모두 request body 없이 반복해도 같은 최종 상태와 `200 OK`로 수렴하도록 확정했다. 관계 표시 `PUT`은 전체 표현을 request body로 보내지 않으므로 ADR-0022의 기존 `PUT` 기준과 충돌한다.

이번 결정의 기준은 다음과 같다.

- 기존 리소스의 일부 수정에는 엔드포인트마다 같은 `PATCH` 기준을 적용할 것
- 경로와 메서드만으로 관계 리소스의 전체 목표 상태를 알 수 있을 때 안전한 재시도를 지원할 것
- 요청 본문이 없는 관계 설정을 임의의 토글 명령으로 해석하지 않을 것
- ADR-0022의 방 종료 멱등성과 트랜잭션 경계를 유지할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 경로로 식별한 관계의 존재를 bodyless `PUT`, 부재를 `DELETE`로 설정 | URI와 메서드가 전체 목표 상태를 결정하고 반복 요청이 같은 결과로 수렴한다 | 일반적인 표현 교체 `PUT` 외에 관계 설정 기준을 함께 알아야 한다 | 선택 |
| `PATCH`와 boolean request body로 관계 상태 변경 | 목표 상태를 본문에 명시할 수 있다 | 부분 수정과 관계 리소스의 존재 설정이 섞이고 #308의 공개 계약을 변경한다 | 제외 |
| 등록을 `POST`, 취소를 `DELETE`로 처리 | 생성 요청 형태가 익숙하다 | 클라이언트 지정 관계를 반복 설정하는 멱등성이 메서드만으로 드러나지 않고 #308의 공개 계약을 변경한다 | 제외 |

## 결정

기존 리소스의 일부 필드만 수정하는 API는 `PATCH`를 사용한다. 클라이언트가 request body로 리소스 전체 표현을 결정해 교체할 때는 `PUT`을 사용한다.

추가로, 경로가 사용자와 대상의 관계 리소스를 완전히 식별하고 메서드만으로 그 리소스의 전체 목표 상태가 결정될 때는 request body 없는 `PUT`을 사용할 수 있다. 이 규칙을 적용하는 `PUT`은 관계가 없으면 생성하고 이미 있으면 추가 쓰기 없이 같은 성공 응답을 반환한다. 같은 경로의 `DELETE`는 관계가 있으면 삭제하고 없어도 같은 성공 응답을 반환한다. 등록과 취소를 토글로 해석하지 않는다.

`SEARCH-03`은 다음 계약을 따른다.

- `PUT /api/users/me/played-games/{gameId}`는 본인과 게임의 관계를 존재 상태로 설정한다.
- `DELETE /api/users/me/played-games/{gameId}`는 같은 관계를 부재 상태로 설정한다.
- 둘 다 request body가 없고 반복 요청도 `200 OK`와 동일한 최종 `playedByMe` 값으로 수렴한다.

ADR-0022의 방 종료 결정도 그대로 유지한다. `PATCH /api/rooms/{roomId}/status`는 인증과 주최자 권한을 확인한 뒤 하나의 기준 시각으로 상태를 정합화하고 다음 규칙을 적용한다.

- 상태 정합화 후 `FINISHED`이면 종료 목표가 달성된 것으로 보고 `200 OK`와 현재 `RoomStatusResponse`를 반환한다.
- 요청 전부터 `FINISHED`였다면 상태, 버전과 갱신 시각을 변경하지 않는다.
- 같은 요청의 시간 기반 정합화가 `FINISHED`로 변경했다면 성공 응답과 함께 그 변경을 같은 쓰기 트랜잭션에서 커밋한다.
- 정합화 후 `CLOSED`이고 `now >= startsAt`이면 수동으로 `FINISHED`로 전환하고 성공한다.
- `CANCELED`이거나 `now < startsAt`이라 종료 목표에 도달할 수 없으면 기존 `INVALID_ROOM_STATUS_TRANSITION` 409를 반환한다.

상태 정합화와 업무 판정의 트랜잭션 경계, 낙관적 잠금 재시도는 ADR-0012와 기존 참가 동시성 계약을 유지한다.

## 결과

- 얻는 것: 부분 수정, 전체 표현 교체, 경로로 식별한 관계 상태 설정의 메서드 기준이 분리되고, SEARCH-03 등록·취소와 방 종료를 안전하게 재시도할 수 있다.
- 감수할 비용·위험: request body 없는 관계 `PUT`은 일반적인 표현 교체와 다른 기준이므로 API 명세와 해당 기능 정본에서 관계 리소스임을 명시해야 한다.
- 후속 작업: #356에서 SEARCH-03 관계 설정의 신규·반복 요청과 동시 요청을 자동 검증하고, 기존 ROOM-05 회귀 검증을 유지한다.

## 보류 및 재검토

- 지금 하지 않는 것:
  - 관계 상태를 임의 값으로 바꾸는 범용 action API
  - 별도 멱등성 키
  - 해 본 게임의 날짜·횟수 이력
- 보류 이유: SEARCH-03은 경로와 메서드로 존재·부재의 목표 상태가 완전히 결정되며, 추가 상태와 이력은 별도 제품 결정이 필요하다.
- 다시 검토할 조건:
  - 관계 생성에 중복 실행을 별도로 막아야 하는 외부 부수 효과가 추가될 때
  - 존재·부재 외의 관계 상태를 한 요청에서 표현해야 할 때
  - 상태만으로 동일 요청의 목표 달성을 판별할 수 없을 때

## 참고 자료

- [RFC 9110 HTTP Semantics: PUT](https://www.rfc-editor.org/rfc/rfc9110.html#name-put)
- [RFC 9110 HTTP Semantics: Idempotent Methods](https://www.rfc-editor.org/rfc/rfc9110.html#name-idempotent-methods)
- [RFC 5789 PATCH Method for HTTP](https://www.rfc-editor.org/rfc/rfc5789.html)

## 검증

- 상태: 미검증
- 근거:
    - 계약:
        - 결정 이슈 #308과 API·SEARCH-03 정본이 bodyless 관계 `PUT`·`DELETE`의 목표 상태와 반복 요청 응답을 고정한다.
        - ADR-0022와 ROOM-05의 기존 검증 근거가 방 종료 멱등성의 승계 규칙을 뒷받침한다.
- 미검증:
    - #356의 SEARCH-03 운영 코드·Flyway·자동 검증은 아직 반영되지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
