# ADR-0022: P0 수정 API에 PATCH를 유지하고 방 종료 명령을 멱등 처리

- 상태: 대체됨
- 작성일: 2026-07-28
- 결정일: 2026-07-28
- 관련: [API 명세](../../API.md), [P0 명세](../../archive/p0/P0-spec.md), [ROOM-05 정본](../../archive/p0/room.md#room-05-방-취소종료), [ADR-0012](../room/0012-room-request-boundary-state-reconciliation.md), [결정 이슈 #101](https://github.com/bamsongi-club/albam-mate/issues/101), [구현 이슈 #32](https://github.com/bamsongi-club/albam-mate/issues/32), [PR #98](https://github.com/bamsongi-club/albam-mate/pull/98)
- 대체 대상: ADR-0016
- 후속 ADR: [ADR-0047](0047-http-method-and-target-state-idempotency.md)

## 맥락

P0의 내 프로필 수정, 방 수정, 방 종료 API는 클라이언트가 전체 리소스를 교체하지 않고 허용된 일부만 변경하므로 `PATCH`를 사용한다. 이 메서드 선택은 [ADR-0016](0016-p0-update-api-http-method.md)에서 결정했지만, 방 종료 요청을 반복하면 `INVALID_ROOM_STATUS_TRANSITION`을 반환한다고 함께 명시했다.

방 종료 명령은 [ADR-0012](../room/0012-room-request-boundary-state-reconciliation.md)에 따라 원래 업무 규칙을 평가하기 전에 같은 기준 시각으로 상태를 정합화한다. 자동 종료 시각이 지난 방은 이 과정에서 먼저 `FINISHED`가 될 수 있다. 기존 계약대로라면 뒤이은 수동 종료 검사에서 직접 `CLOSED → FINISHED` 전이가 발생하지 않았다는 이유로 409를 반환하고, 같은 쓰기 트랜잭션에서 발생한 정합화까지 롤백한다. 요청 전에 이미 `FINISHED`였던 방에 같은 종료 요청을 반복해도 종료 목표가 달성된 상태인데 409를 반환한다.

이번 결정의 기준은 다음과 같다.

- 종료 요청의 목표 상태가 이미 달성됐다면 안전하게 재시도할 수 있을 것
- 시간 기반 정합화와 명령 판정은 ADR-0012의 같은 쓰기 트랜잭션 경계를 유지할 것
- 이미 `FINISHED`인 방에는 상태, 버전과 갱신 시각을 다시 쓰지 않을 것
- `CANCELED`와 시작 전 종료 요청, 인증·주최자 권한 오류는 기존 계약을 유지할 것
- 부분 수정 API의 HTTP 메서드 기준은 엔드포인트마다 달라지지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 종료 목표가 이미 달성됐으면 멱등 성공 | 자동 정합화 결과를 커밋하고 동일 요청을 안전하게 재시도할 수 있다. 이미 `FINISHED`면 추가 쓰기가 없다. | 명령 성공이 항상 새로운 상태 전이를 뜻하지 않으므로 전이 여부와 목표 달성을 구분해야 한다. | 선택 |
| 직접 `CLOSED → FINISHED` 전이만 성공 | 상태가 실제로 바뀐 요청만 성공한다는 기존 의미를 유지한다. | 자동 정합화로 목표가 달성돼도 409와 롤백이 발생하고, 반복 요청이 안전하지 않다. | 제외 |
| 정합화를 먼저 별도 커밋하고 수동 종료는 409 | DB 상태는 시간 규칙과 일치시키면서 기존 오류 계약을 유지한다. | 한 명령에 쓰기 트랜잭션을 나눠 ADR-0012의 명령 원자성을 바꾸고, 최종 상태인데 실패를 반환하는 문제는 남는다. | 제외 |

## 결정

P0의 내 프로필 수정, 방 수정, 방 종료 API는 계속 `PATCH`를 사용한다. 클라이언트가 리소스 전체 표현을 결정해 교체할 때만 `PUT`을 사용한다.

`PATCH /api/rooms/{roomId}/status`의 인증과 주최자 권한을 확인한 뒤, 하나의 기준 시각으로 상태를 정합화하고 다음 규칙을 적용한다.

- 상태 정합화 후 `FINISHED`이면 종료 목표가 달성된 것으로 보고 `200 OK`와 현재 `RoomStatusResponse`를 반환한다.
- 요청 전부터 `FINISHED`였다면 상태, 버전과 갱신 시각을 변경하지 않는다.
- 같은 요청의 시간 기반 정합화가 `FINISHED`로 변경했다면 성공 응답과 함께 그 변경을 같은 쓰기 트랜잭션에서 커밋한다.
- 정합화 후 `CLOSED`이고 `now >= startsAt`이면 수동으로 `FINISHED`로 전환하고 성공한다.
- `CANCELED`이거나 `now < startsAt`이라 종료 목표에 도달할 수 없으면 기존 `INVALID_ROOM_STATUS_TRANSITION` 409를 반환한다.

이미 `FINISHED`인 방의 성공은 최종 상태를 다시 변경하는 전이가 아니라 같은 목표에 대한 무변경 성공이다. `CANCELED`를 `FINISHED`로 바꾸거나 취소·종료를 철회하는 기능은 추가하지 않는다. 상태 정합화와 업무 판정의 트랜잭션 경계, 낙관적 잠금 재시도는 기존 ADR-0012와 참가 동시성 계약을 유지한다.

## 결과

- 얻는 것: 자동 종료 경계와 수동 종료가 겹쳐도 API 결과와 저장 상태가 일치하고, 응답을 받지 못한 클라이언트가 같은 종료 요청을 안전하게 재시도할 수 있다.
- 감수할 비용·위험: 성공 응답만으로 이번 요청에서 전이가 발생했는지는 구분할 수 없다. 필요한 경우 현재 버전과 상태를 사용하며, 반복 요청에서 별도 상태 변경 이력이나 이벤트를 만들지 않는다.
- 후속 작업: ROOM-05 계약과 구현을 변경하고, 자동 정합화가 발생한 성공 요청의 DB 커밋 및 기존 `FINISHED` 요청의 무변경 성공을 통합 테스트로 검증한다. ROOM-06c 소비자 통합 검증에서 같은 계약을 사용한다.

## 보류 및 재검토

- 지금 하지 않는 것:
  - 취소 명령의 멱등 성공
  - 별도 멱등성 키
  - 상태 변경 이력 테이블
  - 정합화와 명령의 트랜잭션 분리
- 보류 이유: 이번 문제는 종료 목표가 상태로 명확히 확인되는 요청에 한정되며, 다른 명령과 별도 이력은 독립적인 제품 결정이 필요하다.
- 다시 검토할 조건:
  - 종료 시 외부 알림이나 결제처럼 중복 실행을 별도로 차단해야 할 부수 효과가 추가될 때
  - 상태만으로 동일 요청을 판별할 수 없을 때
  - 실패 명령에서도 모든 시간 정합화를 선커밋해야 한다는 요구가 생길 때

## 참고 자료

- [RFC 9110 HTTP Semantics: Idempotent Methods](https://www.rfc-editor.org/rfc/rfc9110.html#name-idempotent-methods)
- [RFC 5789 PATCH Method for HTTP](https://www.rfc-editor.org/rfc/rfc5789.html)
- [결정 이슈 #101](https://github.com/bamsongi-club/albam-mate/issues/101)

## 검증

- 상태: 검증됨
- 근거:
    - 테스트:
        - PR #98의 `room.service.command.RoomStatusChangeExecutorIntegrationTest`는 자동 정합화 상태의 커밋·버전 증가와 기존 `FINISHED` 상태·버전·갱신 시각의 불변을 확인한다.
        - Windows에서 `.\gradlew.bat build`를 통과했다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
