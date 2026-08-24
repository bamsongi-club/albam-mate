# ADR-0082: 채팅 목록 갱신을 위한 사용자 단위 실시간 채널 신설

- 상태: 승인됨
- 작성일: 2026-08-20
- 결정일: 2026-08-20
- 관련: [CHAT-08 문서 이슈 #913](https://github.com/bamsongi-club/albam-mate/issues/913), [CHAT-07 명세](../../p2/chat.md#chat-07-채팅-목록-마지막-메시지방별-미읽음-상태), [ADR-0032](0032-http-send-websocket-receive.md), [ADR-0033](0033-postgresql-source-after-commit-delivery.md), [ADR-0079](0079-chat-room-read-cursor-and-derived-unread-count.md), [ADR-0038 공용 세션·스케줄 조정](../platform/0038-multi-instance-session-and-scheduler-coordination.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

CHAT-07([ADR-0079](0079-chat-room-read-cursor-and-derived-unread-count.md))은 채팅 목록의 마지막 메시지·미읽음 상태를 조회 시점 값으로만 제공하기로 결정했다. 그 결과 사용자가 채팅 목록 화면에 머무는 동안 다른 사용자가 새 메시지를 보내도 화면이 갱신되지 않고, 재진입하거나 새로고침해야 반영된다. 제품 목표를 "목록 화면에 머무는 동안에도 마지막 메시지 미리보기·미읽음 배지·방 정렬이 즉시 갱신"되는 수준으로 올리기로 하면서 이 지연이 목표를 벗어났다.

[ADR-0032](0032-http-send-websocket-receive.md)는 "공용 WebSocket 한 개와 다중 방 구독"을 P1 범위에서 명시적으로 제외하며 재검토 조건으로 "한 사용자가 여러 방을 동시에 구독해야 할 때"를 남겼다. [ADR-0079](0079-chat-room-read-cursor-and-derived-unread-count.md)도 "조회 시점 갱신만으로 사용자가 새 메시지 도착을 인지하는 지연이 제품 목표를 벗어날 때" 재검토하라고 명시했다. 지금이 두 ADR이 예정한 재검토 시점이다.

기존 알림(Notification) 도메인도 사용자 단위 실시간 push 인프라가 없다([ADR-0039](../notification/0039-notification-presentation-and-bulk-read-snapshot.md)는 10초 polling 기반 목록·미확인 조회를 전제한다). 즉 재사용할 기존 사용자 단위 실시간 채널이 저장소 전체에 없고, 이번이 그런 채널의 첫 도입이다.

판단 기준은 다음과 같다.

- 기존 방별 WebSocket([ADR-0032](0032-http-send-websocket-receive.md))의 handshake·권한 검사·재연결·catch-up 계약을 재설계하지 않고 그대로 유지할 것
- 커밋 뒤 Redis 신호·PostgreSQL 정본이라는 기존 전달 모델([ADR-0033](0033-postgresql-source-after-commit-delivery.md))과 일관되게, 새 채널도 at-most-once 신호 + 클라이언트 재조회로 정합성을 보장할 것
- 새 채널에 메시지 본문·발신자 같은 개인정보를 싣지 않고, 최신값은 기존 CHAT-07 인가된 조회 계약으로만 얻게 할 것
- 멀티 인스턴스 환경([ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md))에서 특정 인스턴스에 연결된 사용자에게 다른 인스턴스가 처리한 메시지 이벤트도 전달할 수 있을 것
- 방 참가자 수 증가에 따른 팬아웃 비용이 감당 가능한 수준일 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 기존 방별 WebSocket을 공용 다중 방 구독으로 확장 | 사용자당 연결이 하나로 통합된다 | [ADR-0032](0032-http-send-websocket-receive.md)가 승인한 handshake·권한 검사·`ConnectionRegistry`(방 단위 키) 전체를 사용자 단위로 재설계해야 하고, 방 안 화면의 기존 재연결·catch-up 로직과 얽혀 회귀 위험이 크다 | 제외 |
| 신규 사용자 단위 WebSocket을 기존 방별 WebSocket과 병렬로 추가 | 기존 방별 WS의 승인된 계약을 전혀 재설계하지 않는다. 목록 화면은 서버 발신만 필요해 프로토콜이 단순하다 | 사용자가 방 안 화면과 목록 화면을 동시에 열면 WebSocket 연결이 두 개 생길 수 있고, 신규 사용자 단위 `ConnectionRegistry`가 필요하다 | 선택 |
| 사용자 단위 채널을 SSE로 구현 | 서버 발신 전용에 더 단순한 프로토콜이다 | [ADR-0032](0032-http-send-websocket-receive.md)가 이미 SSE를 검토해 "채팅 전용 연결을 WebSocket으로 통일"하기로 제외했고, 그 이유(향후 양방향 확장, 기존 세션 재사용)가 여전히 유효하다. 검증된 적 없는 새 프로토콜 인프라를 하나 더 추가하는 위험만 생긴다 | 제외 |
| Polling 간격 단축(짧은 주기 재조회) | 새 인프라가 필요 없고 Notification의 기존 10초 polling과 일관된다 | 목표로 삼은 "머무는 동안 즉시 반영" 자체를 만족하지 못하고, 사용자 수에 비례해 요청량이 늘어난다 | 제외 |

## 결정

새 사용자 단위 WebSocket 채널을 기존 방별 WebSocket과 **병렬로** 추가한다. 기존 방별 WebSocket(`/api/rooms/{roomId}/chat/ws`, [ADR-0032](0032-http-send-websocket-receive.md))의 handshake·권한 검사·재연결·catch-up 계약은 바꾸지 않는다.

1. 신규 엔드포인트 `GET /api/users/me/chat/ws`를 추가한다. handshake는 기존 `JSESSIONID` 세션([ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md)의 공용 Spring Session Redis 인증 상태)을 그대로 사용하며, 별도 토큰을 만들지 않는다. 검증은 "로그인한 사용자 본인"만 확인하고, 방 단위 권한 검사는 하지 않는다.
2. 각 인스턴스는 연결을 인스턴스 로컬 사용자 단위 `ConnectionRegistry`(userId 키)에 등록한다. 기존 방 단위 `ConnectionRegistry`(roomId 키)와는 별개 자료구조로 둔다.
3. 메시지 커밋 뒤(`MessageCommitted`, [ADR-0033](0033-postgresql-source-after-commit-delivery.md)) 기존 방 단위 팬아웃과 별개로, 해당 방의 현재 참가자 user id 목록을 조회해 각 참가자에게 최소 이벤트 `ROOM_UPDATED { roomId, messageId }`를 전송한다. 메시지 본문·발신자 식별 정보는 이 이벤트에 담지 않는다. 발신자 본인도 참가자이므로 동일하게 이벤트를 받는다(본인이 다른 탭·기기에서 목록 화면을 보고 있을 수 있다).
4. 멀티 인스턴스 팬아웃은 기존 Redis 채널([ADR-0033](0033-postgresql-source-after-commit-delivery.md))의 신호를 그대로 재사용한다. 각 인스턴스는 신호를 받으면 로컬에 연결된 참가자만 찾아 전달한다. 신호는 at-most-once이며 신뢰 가능한 정본은 아니다.
5. 클라이언트는 `ROOM_UPDATED` 수신 시 이벤트가 담은 데이터를 직접 반영하지 않고, 기존 CHAT-07 배치 조회 API로 해당 방(들)의 최신 마지막 메시지·미읽음 상태를 재조회한 뒤 미리보기·배지·방 정렬을 갱신한다. 신호 유실·중복·순서 역전이 있어도 최종 화면은 항상 재조회 결과로 수렴한다.
6. 채팅방 "안" 화면(기존 방별 WebSocket 연결 보유)에 있는 사용자도 참가자이므로 이 이벤트를 함께 받을 수 있다. 서버는 "지금 방 안에 있는지"를 판별해 억제하지 않는다. 클라이언트는 목록 화면이 아닐 때 이 이벤트를 무시한다.
7. 이 채널은 서버 발신 전용이며 클라이언트가 보내는 애플리케이션 프레임은 지원하지 않는다([ADR-0032](0032-http-send-websocket-receive.md)와 동일한 자세).

## 결과

- 얻는 것: 채팅 목록 화면이 머무는 동안에도 실시간으로 갱신되며, 기존 방별 WebSocket·CHAT-07 조회 계약([ADR-0032](0032-http-send-websocket-receive.md), [ADR-0079](0079-chat-room-read-cursor-and-derived-unread-count.md))은 전혀 재설계하지 않는다. 새 채널의 신호가 최소 payload(`roomId`, `messageId`)만 실어 개인정보 노출 표면을 늘리지 않는다.
- 감수할 비용·위험: 사용자당 최대 두 개의 WebSocket 연결(방 안 + 목록)이 동시에 열릴 수 있다. 신규 userId 단위 `ConnectionRegistry`와 handshake 구현이 추가 인프라다. 메시지 커밋마다 방 참가자 목록 조회가 추가되어, 참가자 수에 비례하는 팬아웃 비용이 기존 방 단위 팬아웃(연결된 접속자만 대상)보다 커진다.
- 후속 작업: `CHAT-08` 명세 작성(사용자 문제·흐름·완료 기준·제외 범위), API·아키텍처 문서에 신규 엔드포인트·이벤트 계약 반영, userId `ConnectionRegistry`·handshake·참가자 조회 fan-out 구현, 프런트엔드 구독·재조회·재정렬 구현, 멀티 인스턴스 전달·재연결 PostgreSQL 통합 검증.

## 보류 및 재검토

- 지금 하지 않는 것: 방별 WebSocket과 사용자 단위 WebSocket의 단일 연결 통합, 타이핑 표시 등 클라이언트 발신 양방향 신호, 이 채널을 Notification 도메인이 함께 쓰도록 공용화하는 것, 방 안 화면에 있는 사용자에 대한 서버 측 이벤트 억제.
- 보류 이유: 기존 방별 WS와 CHAT-07 조회 계약을 안전하게 보존하면서 재검토 조건만 좁게 충족하는 데는 병렬 채널로 충분하다. Notification은 아직 실시간 요구가 제품 목표로 확정되지 않았다.
- 다시 검토할 조건: 사용자당 다중 WebSocket 연결의 운영 비용(메모리·연결 수)이 감당하기 어려워지거나, Notification도 동일한 실시간 요구가 제품 목표가 되어 사용자 단위 채널을 공용화해야 할 때, 또는 방 참가자 조회 기반 팬아웃 비용이 방 규모 확대로 감당하기 어려워질 때.

## 참고 자료

- 이 문서의 맥락·대안으로 갈음.

## 검증

- 상태: 미검증
- 근거:
    - 구현: userId `ConnectionRegistry`, handshake, 참가자 fan-out, Redis subscriber 백엔드와 `useChatListRealtimeRefresh` 프런트엔드 경로가 구현돼 있다.
    - H2 테스트: `ChatUserWebSocketHandlerTest`, `ChatUserWebSocketHandshakeIntegrationTest`가 최소 payload·인증·참가자 전달·비참가자 차단을 통과했다.
    - PostgreSQL 테스트: `ChatUserWebSocketCrossInstanceDeliveryPostgresTest`가 멀티 인스턴스 사용자 채널 전달과 기존 방 WebSocket 회귀를 통과했다.
    - 프런트엔드 테스트: `frontend/src/ChatEntry.test.jsx`가 신호 수신·재조회·재연결·화면 갱신 경계를 검증한다.
- 미검증:
    - production 배포 후 사용자 단위 연결 수·팬아웃 지연·참가자 조회 비용 측정

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
