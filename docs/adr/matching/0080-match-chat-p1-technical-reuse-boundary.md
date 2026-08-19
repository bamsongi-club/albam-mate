# ADR-0080: MATCH 채팅 실시간 전달의 P1 채팅 기술 기반 재사용 경계

- 상태: 승인됨
- 작성일: 2026-08-19
- 결정일: 2026-08-19
- 관련: [Issue #744](https://github.com/bamsongi-club/albam-mate/issues/744) / [아키텍처](../../ARCHITECTURE.md#p2-match-모듈-계약) / [ADR-0032](../chat/0032-http-send-websocket-receive.md) / [ADR-0033](../chat/0033-postgresql-source-after-commit-delivery.md) / [ADR-0067](0067-match-shared-contract-boundary.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

`#744` Stage A(저장·HTTP)는 승인되어 병합됐다([PR #859](https://github.com/bamsongi-club/albam-mate/pull/859)). Stage B(WebSocket·Redis)의 원래 소유표는 `MatchChatConnectionRegistry`, `MatchChatWebSocketHandler`, `MatchChatMessageDeliveryService`, `MatchChatMessageRateLimiter`, `RedisMatchChatMessageRateLimiter`, `RedisMatchChatRealtimePublisher`, `RedisMatchChatRealtimeSubscriber` 등 거의 모든 클래스를 P1 ROOM chat과 별도로 새로 만드는 것으로 계획돼 있었다. P1 ROOM chat은 이미 같은 문제(HTTP 저장 + 방별 WebSocket 실시간 수신[ADR-0032], PostgreSQL을 정본으로 두고 커밋 뒤 Redis로 fan-out[ADR-0033], 고정 창 Redis rate limit)를 검증된 방식으로 풀어놓았는데, 이를 재사용하지 않고 클래스명만 바꿔 다시 구현하면 두 위험이 있다: (1) Redis Lua 기반 원자 rate-limit reserve/release, 재연결 catch-up→live 전환의 dedup 알고리즘처럼 정확성이 중요한 로직이 두 벌로 갈라져 한쪽만 버그 수정되는 drift, (2) 불필요한 개발 비용 중복.

동시에 `#744`는 P1 ROOM chat의 API·저장·권한·보존 정책과 실제 운영 안정성을 바꾸지 않아야 한다는 제약이 있다. P1의 WebSocket 연결 관리(`ChatConnectionRegistry`, `ChatWebSocketHandler`, `ChatMessageDeliveryService`)는 이미 프로덕션에서 동작 중인 실시간 채팅 경로이므로, 이를 일반화하는 리팩터링 자체가 회귀 위험을 만든다.

판단 기준은 세 가지다. 첫째, 재사용이 ROOM(`roomId`)과 MATCH(`partyId`) 사이의 식별자·네임스페이스 충돌을 만들지 않을 것. 둘째, 정확성이 중요하고 검증 비용이 큰 로직(원자 rate-limit, dedup·catch-up 알고리즘)일수록 재사용을 우선하고, 도메인 결합이 강한 로직(접근 판정, Entity 조회)일수록 복제를 허용할 것. 셋째, P1의 실제 운영 중인 실시간 배달 경로 자체를 제네릭화 리팩터링 대상으로 삼지 않을 것.

## 검토한 대안

아래 네 축은 판단 기준이 서로 달라 같은 표에서 비교하지 않는다. 축마다 그 축의 기준으로만 대안을 비교한다.

### 축 1. Redis 고정 창 rate limit을 어떻게 공유하는가

판단 기준은 원자성이 중요한 Lua 스크립트의 정확성 유지 비용과, ROOM·MATCH가 서로 다른 quota 수치(ROOM: 사용자 50건/방 100건/10초, MATCH: 사용자 5건/Party 30건/10초)를 가져야 한다는 제품 요구다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `RedisChatMessageRateLimiter`의 Lua reserve/release 스크립트와 원자 이중 버킷 알고리즘을 별도 클래스로 추출해 ROOM·MATCH가 각자의 key-prefix·quota로 감싸 재사용한다 | 정확성이 중요한 원자 로직(반쪽 실패 rollback, 예약 ID 추적)이 한 곳에만 존재해 버그 수정이 양쪽에 동시 적용된다. ROOM·MATCH는 자기 quota·key 네임스페이스만 소유한다 | `RedisChatMessageRateLimiter.java`(P1)를 내부 구현만 위임하도록 리팩터링해야 한다. 외부 동작(bean, `ChatMessageRateLimitProperties`, 기존 namespace)은 바꾸지 않지만 P1 파일 수정 자체가 회귀 위험을 수반한다 | 선택 |
| `RedisChatMessageRateLimiter`를 그대로 두고 `RedisMatchChatMessageRateLimiter`에 같은 Lua 스크립트를 그대로 복사해 `:party:` key만 다르게 쓴다 | P1 파일을 전혀 건드리지 않는다 | 반쪽 실패 rollback·예약 ID 추적 같은 미묘한 원자성 로직이 두 벌로 갈라진다. ROOM 쪽에서 발견된 버그가 MATCH에 반영되지 않을 수 있다 | 제외 |
| 두 도메인이 완전히 다른 rate-limit 구현(예: 애플리케이션 메모리 카운터)을 쓴다 | 설계가 단순해 보인다 | 여러 인스턴스에 걸친 정확한 fail-closed 판정을 요구하는 CHAT-T5·기존 P1 계약과 맞지 않고, 이미 검증된 Redis 원자 방식을 버리는 손해가 크다 | 제외 |

### 축 2. 커밋 후 실시간 신호를 어떤 Redis 채널·컨테이너로 전달하는가

판단 기준은 ROOM·MATCH 메시지가 서로 다른 채널로 격리되어야 한다는 요구(namespace 충돌 금지)와, Redis 구독 재시도·bootstrap처럼 도메인과 무관한 배관을 다시 만들 비용이다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `MatchChatMessageCommitted`(Stage A가 이미 만든 별도 타입)를 유지하고, 별도 Redis 채널(`albam-mate:{env}:match-chat:events`)에 발행·구독하되, 그 구독은 `RedisChatRealtimeListenerConfiguration`이 이미 관리하는 같은 `RedisMessageListenerContainer`에 두 번째 topic으로 추가 등록한다 | ROOM·MATCH 메시지가 채널 이름부터 격리되어 namespace 충돌이 없다. 재시도·bootstrap 같은 도메인 무관 배관을 재사용해 새로 만들 필요가 없다 | `RedisChatRealtimeListenerConfiguration.java`(P1)에 topic 등록을 하나 추가해야 한다(기존 ROOM topic·리스너는 그대로 둔 채 추가만 하므로 행위 보존형) | 선택 |
| `MessageCommitted` record에 도메인 구분자(`kind`)를 추가해 ROOM·MATCH가 완전히 같은 타입·같은 채널·같은 `ChatRealtimePublisher`/`ChatRealtimeSignalGateway`/`ChatMessageCommittedListener`를 공유한다 | 커밋 이벤트 타입과 채널이 하나로 줄어든다 | `MessageCommitted`의 compact constructor와 `ChatRealtimePublisher`·`ChatRealtimeSignalGateway`·`ChatMessageCommittedListener`·`ChatWebSocketHandler.onMessageCommitted`까지, 이미 프로덕션에서 도는 P1 클래스 5개 이상을 모두 고쳐야 한다. 3개 필드짜리 record 하나를 아끼려고 P1의 커밋 후 배달 경로 전체를 건드리는 비용이 이득보다 크다 | 제외 |
| MATCH 전용 `RedisMessageListenerContainer`·구독 재시도 bootstrap을 처음부터 새로 만든다 | P1 파일을 전혀 건드리지 않는다 | `RedisChatRealtimeListenerConfiguration`의 재시도·bootstrap 로직(도메인 무관, 이미 검증됨)을 그대로 다시 구현하게 되어 순수 낭비다 | 제외 |

### 축 3. WebSocket 연결 관리·전달을 어떻게 나누는가

판단 기준은 P1의 실제 운영 중인 실시간 배달 경로의 회귀 위험과, 접근 판정·Entity 조회가 두 도메인에서 근본적으로 다르다는 사실이다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `ChatConnectionRegistry`·`ChatWebSocketHandler`·`ChatMessageDeliveryService`·`ChatRoomConnection`은 손대지 않고 MATCH 전용으로 새로 만들되, `ChatWebSocketConfig`가 이미 공개한 `HandshakeHandler`·`TaskScheduler` bean과 `ChatWebSocketProperties`(허용 Origin·재검증 주기)는 그대로 주입받아 재사용한다. catch-up→live 전환·dedup 알고리즘은 P1과 같은 설계를 MATCH Entity에 맞춰 다시 구현한다 | P1의 실제 운영 중인 배달 경로를 전혀 건드리지 않는다. 두 도메인의 접근 판정(`room.contract.ChatAccessGuard` vs `matching.contract.MatchPartyChatWriteGuard`)과 Entity(`ChatRoomRepository` vs `MatchChatRoomRepository`)가 근본적으로 달라, 강제로 하나의 클래스로 합치면 오히려 두 도메인 모두에 특수 케이스가 쌓인다 | catch-up→live 전환·dedup 알고리즘은 두 벌로 존재한다. 이 로직은 `partyId`/`roomId`와 두 Repository 타입에 강하게 결합돼 있어, 공유하려면 두 도메인의 Repository·접근 판정을 모두 인터페이스로 추상화하는 큰 리팩터링이 필요하다 | 선택 |
| `ChatConnectionRegistry`·`ChatWebSocketHandler`·`ChatMessageDeliveryService`를 Repository·접근 판정 포트로 매개변수화해 ROOM·MATCH가 완전히 같은 클래스를 쓴다 | 코드량이 가장 적다 | P1의 실제 운영 중인 실시간 배달 경로 3개 클래스를 모두 제네릭·포트 기반으로 재작성해야 한다. 리팩터링 범위가 커서 회귀 위험이 가장 크고, `#744`의 "P1 안정성 유지" 제약과 충돌한다 | 제외 |
| MATCH 전용 클래스를 완전히 새로 만들고 `ChatWebSocketConfig`의 bean도 재사용하지 않고 별도로 만든다 | 개념적으로 가장 독립적이다 | `HandshakeHandler`·`TaskScheduler`는 P1과 다를 이유가 없는 순수 기술 컴포넌트라, 별도로 만드는 것은 불필요한 복제다 | 제외 |

### 축 4. WebSocket 메트릭을 공유하는가

판단 기준은 결합 여부와 재사용 비용이다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| `ChatWebSocketMetrics`는 사용자·방 식별자를 태그로 쓰지 않는 순수 계측이므로 패턴만 복제해 `match.chat.websocket.*` 메트릭 이름으로 `MatchChatWebSocketMetrics`를 새로 만든다 | ROOM·MATCH 연결 수·지연·실패를 운영에서 따로 관찰할 수 있다. P1 파일을 건드리지 않는다 | 클래스 하나가 늘어난다(비용 낮음) | 선택 |
| 같은 `ChatWebSocketMetrics` bean을 공유하고 태그로 ROOM/MATCH를 구분한다 | 클래스 수가 늘지 않는다 | `ChatWebSocketMetrics.java`(P1)를 태그 지원하도록 고쳐야 하며, 이미 운영 중인 대시보드·알람의 메트릭 이름 의미가 바뀔 위험이 있다 | 제외 |

## 결정

Stage B는 아래 두 가지만 P1 파일을 행위 보존형으로 확장하고, 나머지는 MATCH 전용으로 새로 만들거나 P1 bean을 그대로 주입받는다.

**공유 — P1 파일을 행위 보존형으로 확장**

- `RedisChatMessageRateLimiter`의 Lua reserve/release 스크립트와 원자 이중 버킷 알고리즘을 `infra.redis`의 새 파라미터화된 클래스(예: `RedisFixedWindowDualBucketRateLimiter`)로 추출한다. `RedisChatMessageRateLimiter`는 이 클래스에 위임하도록 내부 구현만 바꾸고, 외부에 노출된 bean·`ChatMessageRateLimitProperties`·기존 namespace(`albam-mate:{env}:ratelimit:room:*`)는 그대로 유지한다. `RedisMatchChatMessageRateLimiter`는 같은 추출 클래스를 `albam-mate:{env}:ratelimit:party:*` namespace와 CHAT-T5의 quota(사용자 5건/Party 30건/10초)로 감싼다.
- `RedisChatRealtimeListenerConfiguration`이 관리하는 하나의 `RedisMessageListenerContainer`에 MATCH 채널(`albam-mate:{env}:match-chat:events`)과 `RedisMatchChatRealtimeSubscriber`를 두 번째 topic으로 추가 등록한다. 기존 ROOM topic·리스너·재시도 bootstrap 로직은 그대로 둔다.

**재사용 — P1 파일 수정 없이 기존 bean만 주입**

- `ChatWebSocketConfig`가 공개하는 `HandshakeHandler`·`TaskScheduler`(`chatWebSocketTaskScheduler`) bean을 MATCH의 handshake controller·handler가 그대로 주입받는다.
- `ChatWebSocketProperties`(허용 Origin, 접근 재검증 주기)를 MATCH WebSocket 진입점이 그대로 사용한다.

**복제 — MATCH 전용 신규, P1 미접촉**

- `MatchChatConnectionRegistry`, `MatchChatWebSocketHandler`, `MatchChatMessageDeliveryService`, `MatchChatPartyConnection`을 새로 만든다. catch-up→live 전환과 `afterMessageId` dedup 알고리즘은 P1 `ChatConnectionRegistry`/`ChatMessageDeliveryService`와 같은 설계를 따르되 `MatchChatRoomRepository`/`MatchChatMessageRepository`와 `matching.contract.MatchPartyAccessQuery`/`MatchPartyChatWriteGuard`에 맞춰 새로 구현한다.
- `MatchChatWebSocketMetrics`를 `match.chat.websocket.*` 메트릭 이름으로 새로 만든다.
- Stage A가 이미 만든 `MatchChatMessageCommitted`·`MatchChatRealtimePublisher`는 유지한다. `RedisMatchChatRealtimePublisher`/`RedisMatchChatRealtimeSubscriber`를 새로 만들어 위 공유 컨테이너에 연결한다.

## 결과

- 얻는 것:
    - 정확성이 중요한 Redis 원자 rate-limit 로직과 Pub/Sub 구독 재시도·bootstrap 배관이 ROOM·MATCH에 각 한 벌만 존재해, 버그 수정이 양쪽에 함께 적용된다.
    - P1의 실제 운영 중인 WebSocket 연결·전달 경로(`ChatConnectionRegistry`/`ChatWebSocketHandler`/`ChatMessageDeliveryService`)는 전혀 수정하지 않아 회귀 위험이 없다.
    - ROOM·MATCH의 Redis 채널·rate-limit key namespace가 처음부터 분리되어 `roomId`/`partyId` 값 충돌이 구조적으로 불가능하다.
- 감수할 비용·위험:
    - catch-up→live 전환·dedup 알고리즘, WebSocket handler·connection registry는 두 벌로 존재한다. 이 알고리즘에 버그가 발견되면 양쪽을 각각 고쳐야 한다.
    - `RedisChatMessageRateLimiter.java`·`RedisChatRealtimeListenerConfiguration.java`(P1) 수정이 필요하며, 두 파일 모두 기존 P1 회귀 테스트로 외부 동작 불변을 확인해야 한다.
    - `infra.redis`에 새 공유 클래스(`RedisFixedWindowDualBucketRateLimiter` 등)가 하나 늘어 `infra` 계층의 책임이 넓어진다.
- 후속 작업:
    - Stage B 구현 패킷은 이 ADR이 결정한 공유·재사용·복제 경계를 그대로 `allowedPaths`·`forbiddenPaths`에 반영한다.
    - `RedisChatMessageRateLimiter`·`RedisChatRealtimeListenerConfiguration` 수정 PR은 변경 전후 P1 ROOM chat의 기존 회귀 테스트(rate limiter·Redis pub/sub 관련 테스트)를 재실행해 외부 동작이 그대로임을 증거로 남긴다.

## 보류 및 재검토

- 지금 하지 않는 것: `MessageCommitted`에 도메인 구분자를 추가해 ROOM·MATCH가 완전히 같은 커밋 이벤트 타입을 쓰게 하는 것, `ChatConnectionRegistry`/`ChatWebSocketHandler`/`ChatMessageDeliveryService`를 포트 기반으로 제네릭화해 완전히 공유하는 것.
- 보류 이유: 둘 다 이미 프로덕션에서 동작 중인 P1 클래스 여러 개를 동시에 고쳐야 해서, 지금 얻는 이득(record 필드 몇 개·클래스 몇 개 절약)보다 회귀 위험이 크다.
- 다시 검토할 조건: P1 ROOM chat과 MATCH chat 외에 세 번째 채팅 도메인을 또 만들어야 하는 요구가 생기거나, catch-up→live·dedup 알고리즘에서 한쪽에만 반영된 버그가 실제로 발견되어 두 벌 유지 비용이 재사용 리팩터링 비용을 넘어설 때.

## 참고 자료

이 문서의 맥락·대안으로 갈음

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 공유 클래스(`RedisFixedWindowDualBucketRateLimiter` 등)와 MATCH 전용 클래스, `RedisChatMessageRateLimiter`·`RedisChatRealtimeListenerConfiguration`의 행위 보존형 수정이 아직 없다. Stage B 구현·테스트로 확인한다.
    - P1 ROOM chat의 기존 회귀 테스트가 이 확장 이후에도 그대로 통과하는지 아직 확인하지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
