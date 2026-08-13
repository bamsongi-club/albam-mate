# ADR-0053: 대기자 자동 승격 알림의 원인 이벤트와 수신자를 확정

- 상태: 승인됨
- 작성일: 2026-08-07
- 결정일: 2026-08-07
- 관련: [NOTI-01 모임 변경 알림 생성](../../archive/p1/notification.md#noti-01-모임-변경-알림-생성), [P1 알림 생성과 조회](../../archive/p1/P1-spec.md#알림-생성과-조회), [PART-04 선착순 대기열과 자동 승격](../../archive/p1/room.md#part-04-선착순-대기열과-자동-승격), [API NotificationType](../../API.md#notificationtype), [이슈 #499](https://github.com/bamsongi-club/albam-mate/issues/499)
- 대체 대상: [ADR-0029](0029-room-integration-event-transactional-outbox.md)의 공개 이벤트 종류와 참가 취소 뒤 자동 승격 알림 부재 범위
- 후속 ADR: 없음

## 맥락

PART-04는 시작 전 참가자가 취소하면 첫 `WAITING` 대기자 한 명을 같은 ROOM 일관성 경계에서 `PROMOTED`와 `ACTIVE`로 전환한다. 그러나 기존 알림 계약은 이 경우 실제 빈자리가 남지 않는다는 이유로 주최자용 참가 취소 이벤트만 생략했고, 승격된 사용자가 자신의 참가 확정을 알림함에서 확인하는 경로는 제공하지 않았다.

자동 승격은 사용자가 별도의 요청을 보내지 않은 상태에서 대기 관계와 참가 자격이 바뀌는 중요한 결과다. 따라서 승격된 사용자에게는 결과를 알려야 하지만, 같은 참가 취소를 근거로 주최자 빈자리 알림까지 만들면 실제 빈자리가 없는 상태와 문구가 어긋난다. 승격 경쟁에서 조건부 전이에 실패한 후보나 전체 트랜잭션이 롤백된 시도에 알림이 남아서도 안 된다.

이번 결정은 다음 기준을 만족해야 한다.

- 실제로 `PROMOTED`와 `ACTIVE` 전이를 커밋한 사용자 한 명만 수신자로 확정할 것
- 참가 취소·ROOM 인원·대기·참가·Outbox를 하나의 트랜잭션 결과로 유지할 것
- 승격 성공과 자동 승격 없이 빈자리가 남는 결과를 서로 다른 알림으로 명확히 구분할 것
- 기존 Outbox relay의 멱등성·재시도·보존·polling·읽음 처리 계약을 재사용할 것
- 대기열의 다른 상태와 외부 전달 채널까지 범위를 넓히지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 실제 승격 사용자에게만 승격 알림 생성 | 사용자에게 비동기 상태 변경을 직접 알리고 실제 빈자리 여부와 알림 의미가 일치한다. 기존 Outbox·relay·웹 알림 흐름을 그대로 사용할 수 있다. | 공개 이벤트와 두 저장 CHECK, 클라이언트 문구를 함께 확장해야 한다. | 선택 |
| 승격 사용자와 주최자 모두에게 알림 생성 | 양쪽이 참가자 변경을 즉시 안다. | 주최자에게 기존 빈자리 문구를 보내면 사실과 다르고, 별도 주최자용 승격 유형을 만들면 현재 사용자 요구보다 범위가 커진다. | 제외 |
| 기존처럼 자동 승격 알림을 생성하지 않음 | 공개 타입과 저장 계약을 바꾸지 않는다. | 사용자가 직접 조회하기 전에는 참가 확정 사실을 알 수 없어 자동 승격의 사용자 가치가 약해진다. | 제외 |
| 이메일·모바일 푸시까지 함께 전달 | 웹을 열지 않은 사용자에게도 알릴 수 있다. | 사용자 설정·외부 실패·재시도·개인정보 정책이 새로 필요하며 P1 서비스 내 웹 알림 범위를 벗어난다. | 제외 |

## 결정

`room.contract`에 `WaitlistPromotedEvent(roomId, occurredAt)`을 공개하고, Outbox `eventType`과 Notification `type`에 모두 `WAITLIST_PROMOTED`를 추가한다. 이벤트에는 승격 사용자 ID를 넣지 않는다. 최종 참가 취소 Executor가 기록 포트의 별도 수신자 목록에 실제 승격된 사용자 ID 한 개만 전달한다.

참가 취소 트랜잭션의 결과와 기록은 다음과 같이 고정한다.

| 최종 결과 | 수신자 | 기록할 원인 이벤트 |
| --- | --- | --- |
| 첫 `WAITING` 사용자가 실제로 `PROMOTED`·`ACTIVE`가 됨 | 실제 승격 사용자 한 명 | `WAITLIST_PROMOTED` |
| 승격할 활성 대기자가 없어 실제 빈자리가 남음 | 방 주최자 | 기존 `PARTICIPATION_CANCELED` |
| 조건부 승격 경쟁에서 후보가 탈락하거나 참가 취소 트랜잭션이 실패·롤백됨 | 없음 | 없음 |

승격 성공 시에는 실제 빈자리가 없으므로 주최자용 `PARTICIPATION_CANCELED` 이벤트를 함께 만들지 않는다. 반대로 승격이 없으면 `WAITLIST_PROMOTED`를 만들지 않고 기존 주최자 빈자리 알림을 유지한다. Outbox 기록 실패는 참가 취소, ROOM 활성 참가자 수, 대기 상태, 참가 관계와 함께 전체 트랜잭션을 롤백한다.

relay는 명시적 매핑으로 `WAITLIST_PROMOTED` Outbox를 같은 이름의 Notification으로 변환한다. `(sourceEventId, recipientUserId)` 유일 제약, at-least-once 처리, 90일 알림 보존, polling과 단건·일괄 읽음 계약은 변경하지 않는다.

기존 `V4__create_p1_notification_schema.sql`은 수정하지 않는다. 새 전진 Flyway 마이그레이션에서 `notification_outbox_events.event_type`과 `notifications.type`의 이름 있는 CHECK를 기존 세 값과 `WAITLIST_PROMOTED`를 허용하도록 교체한다. 과거 승격 결과를 소급해 Outbox나 Notification으로 만들지 않으며, 혼합 버전용 feature flag도 도입하지 않는다.

웹 알림함은 `WAITLIST_PROMOTED`를 `'<방 제목>' 모임 대기에서 참가자로 확정됐어요.`로 일반 텍스트 렌더링한다. 선택 시 기존 알림 흐름과 동일하게 읽음 처리를 시작하고 현재 권한으로 방 상세를 다시 조회한다. 채팅으로 직접 이동하지 않는다.

## 결과

- 얻는 것:
    - 자동 승격된 사용자가 기존 웹 알림함에서 참가 확정을 확인할 수 있다.
    - 실제 빈자리와 자동 승격 결과가 서로 다른 원인 이벤트·수신자·문구로 표현된다.
    - 경쟁·재시도·롤백 시도는 기존 동일 트랜잭션 Outbox 경계 안에서 잘못된 알림을 남기지 않는다.
- 감수할 비용·위험:
    - 공개 이벤트와 API enum 값이 늘어나므로 서버·DB·클라이언트 계약을 한 변경에서 배포해야 한다.
    - 이전 서버가 새 저장값을 읽는 혼합 버전 배포는 지원하지 않는다.
- 후속 작업:
    - `NOTI-01g` 완료 기준과 API·ERD를 새 타입에 맞춘다.
    - 참가 취소 Executor, relay 매핑, PostgreSQL CHECK와 프론트엔드 문구를 구현하고 회귀 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - 주최자용 자동 승격 알림
    - 대기 순번 변경, 대기 만료와 방 취소 시 대기자 알림
    - 채팅 직행과 이메일·모바일 푸시·Web Push·SMS
    - 과거 자동 승격 결과 backfill과 혼합 버전 feature flag
- 보류 이유:
    - 현재 확인된 사용자 가치는 실제 승격 사용자가 참가 확정을 아는 것이며, 다른 수신자·상태·채널은 별도 정책과 검증이 필요하다.
- 다시 검토할 조건:
    - 주최자가 자동 승격 결과를 별도 유형으로 확인해야 한다는 사용자 요구가 검증될 때
    - 대기 만료·방 취소가 사용자 이탈의 주요 원인으로 측정돼 별도 알림이 필요할 때
    - 외부 채널의 사용자 동의·실패 복구·보안 정책이 승인될 때

## 참고 자료

- [ADR-0029](0029-room-integration-event-transactional-outbox.md)
- [ADR-0039](0039-notification-presentation-and-bulk-read-snapshot.md)
- [이슈 #499의 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/499#issuecomment-5213954731)

## 검증

- 상태: 검증됨
- 근거:
    - 구현: `WaitlistPromotedEvent`와 참가 취소 Executor가 실제 승격 사용자 한 명을 같은 트랜잭션의 Outbox 수신자로 기록하고, 알림 enum·recorder·V25가 `WAITLIST_PROMOTED`를 연결한다. 웹 알림함은 확정 문구를 기존 선택 흐름으로 렌더링한다.
    - 계약: `NOTI-01g-AC1`~`NOTI-01g-AC6`, API `NotificationType`, ERD의 두 CHECK와 수신자 스냅샷 설명이 이 결정의 성공·미발생·실패 분기와 일치한다.
    - 테스트: [이슈 #499의 승인된 T1~T9](https://github.com/bamsongi-club/albam-mate/issues/499#issuecomment-5213954731)를 H2·PostgreSQL·프론트엔드 자동화로 검증하고, 문서 링크 검사와 통합 JaCoCo 래칫을 통과했다.

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
