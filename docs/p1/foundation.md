# P1 기반 작업 명세

이 문서는 P1 기능 구현에 앞서 필요한 검색 성능 검증과 실시간 전달 기반을 독립적으로 착수·검증할 단위로 정의한다. 현재 계약 준비·생산 코드·자동 검증·운영 배포와 실측 상태는 [P1 기능별 상태 정본](README.md#기능별-현재-상태)을 따른다. 두 기반 작업은 승인된 제품·HTTP·저장·아키텍처 계약의 후속 작업만 다루며, 새 제품 정책이나 ADR 수준의 기술 결정을 이 문서에서 만들지 않는다. 승인 ADR이 구현 이슈에 위임한 구성값은 [계약·구현 단일 이슈·PR 규칙](README.md#계약과-구현을-같은-이슈pr에서-처리할-때)에 따라 해당 기반 작업에서 확정할 수 있다.

정본은 다음과 같다.

- P1 범위와 공통 규칙: [P1 명세](../P1-spec.md)
- 기능 규칙: [검색](search.md), [방 채팅](chatting.md)
- HTTP·WebSocket 계약: [API 명세](../API.md)
- 저장 계약: [ERD](../ERD.md)
- 구조와 구현 규칙: [아키텍처](../ARCHITECTURE.md), [컨벤션](../CONVENTIONS.md)
- 기술 결정: 각 항목이 연결한 ADR

ERD에는 승인된 P1 알림·채팅·ShedLock 저장 계약이 구현 예정 계약으로 반영되어 있다. 그 밖의 P1 저장 변경은 [계약·구현 단일 이슈·PR 규칙](README.md#계약과-구현을-같은-이슈pr에서-처리할-때)을 따른다. 같은 기능 이슈·PR 안에서 관련 생산 코드나 스키마를 작성하기 전에 선택한 물리 저장 계약을 ERD에 반영하고, 전진 Flyway 마이그레이션과 JPA Entity를 같은 변경에서 일치시킨다. 별도 계약 PR은 요구하지 않으며 문서 반영만으로 구현 완료로 보지 않는다.

완료 기준 ID 규칙은 [P1 명세](../P1-spec.md#완료-기준-id)를 따른다. 기반 작업은 제품 기능을 직접 추가하지 않으므로 완료 기준은 산출물과 재현 가능한 검증 근거로 적는다.

## 의존 순서와 영향 범위

| ID | 기반 작업 | 선행 | 이 작업을 기다리는 범위 |
| --- | --- | --- | --- |
| [FND-09](#fnd-09-검색-성능과-인덱스-검증) | 검색 성능과 인덱스 검증 | `SEARCH-01`, `SEARCH-02`의 검색 의미·대표 쿼리 구현 | P1 검색 성능 근거와 물리 인덱스 확정 |
| [FND-10](#fnd-10-실시간-전달과-재연결-기반) | 실시간 전달과 재연결 기반 | 채팅 ADR 승인, API·ERD·아키텍처 계약 반영 | `CHAT-03`, 채팅 연결 보안·누락 복구 검증 |

- `FND-09`는 검색 결과의 정확성을 바꾸지 않고 대표 조회의 실행 계획과 비용을 측정한다. 검색 기능 구현과 함께 착수할 수 있지만 인덱스 결론은 측정 뒤 확정한다.
- `FND-10`은 [ADR-0031](../adr/chat/0031-chat-history-cursor-pagination.md)부터 [ADR-0033](../adr/chat/0033-postgresql-source-after-commit-delivery.md)까지와 공통 실행 기반 [ADR-0038](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)이 승인되고 저장·모듈 경계가 정본에 반영된 뒤 구현한다.
- 두 항목의 PostgreSQL 전용 검증은 H2 테스트만으로 완료했다고 보지 않는다.

## FND-09 검색 성능과 인덱스 검증

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기능 규칙 | [SEARCH-01 게임 조건 검색](search.md#search-01-게임-조건-검색), [SEARCH-02 방 조건 검색](search.md#search-02-방-조건-검색) |
| 공통 규칙 | [검색 조건과 결과](../P1-spec.md#검색-조건과-결과), [검색 조회 구조와 인덱스](../P1-spec.md#검색-조회-구조와-인덱스) |
| API 계약 | [게임 목록·검색](../API.md#game-01-게임-목록검색), [방 목록 조회](../API.md#room-01-방-목록-조회) |
| 데이터·검증 경계 | [ADR-0010](../adr/platform/0010-h2-postgresql-test-boundary.md), [PostgreSQL 테스트 규약](../../src/postgresTest/AGENTS.md) |
| 선행 결정 | [ADR-0026](../adr/game/0026-p1-game-search-normalized-numeric-fields.md) 승인과 P1 검색 저장 계약 반영 |

### 산출물

- 게임·방 검색의 무필터, 단일 필터, 대표 복합 필터와 페이지 전체 건수 조회 시나리오
- 데이터 규모, 값 분포, 실행 환경, 준비·반복 방법과 결과 수집 형식을 고정한 재현 절차
- 인덱스 변경 전 대표 쿼리의 실행 계획, 스캔 행 수, 실행시간과 데이터베이스 비용 기준선
- 후보 인덱스별 같은 조건의 변경 후 결과와 결과 집합·정렬·페이지 메타데이터 동일성 검증
- 게임과 방 테이블 각각의 인덱스 채택·보류 근거와 재검토 조건
- 채택한 인덱스의 전진 Flyway 마이그레이션, ERD 반영과 PostgreSQL 회귀 검증

### 완료 기준

- `FND-09-AC1` `SEARCH-01`과 `SEARCH-02`의 무필터·단일 필터·대표 복합 필터·전체 건수 조회 시나리오가 고정되고 같은 입력으로 반복 실행할 수 있다.
- `FND-09-AC2` 데이터 규모와 분포, PostgreSQL 버전·설정, 실행 환경, 준비·반복 횟수와 측정값 수집 방법이 기록된다.
- `FND-09-AC3` 인덱스 변경 전후의 실행 계획, 스캔 행 수, 실행시간과 데이터베이스 비용을 같은 조건에서 비교할 수 있다.
- `FND-09-AC4` 인덱스를 추가하거나 변경해도 필터 결과, 안정 정렬, 전체 건수와 페이지 메타데이터가 기능 계약과 일치한다.
- `FND-09-AC5` 게임과 방의 물리 인덱스를 각각 판단하고, 채택하지 않은 후보도 보류 이유와 다시 측정할 조건을 남긴다.
- `FND-09-AC6` PostgreSQL 통합 검증이 대표 쿼리와 채택 인덱스를 확인하며 H2 결과만으로 실행 계획·인덱스 효과를 판정하지 않는다.
- `FND-09-AC7` 채택한 물리 인덱스와 제약이 전진 마이그레이션과 ERD에 반영되고 관련 검증 명령이 CI에서 재현된다.

### 제외 범위

- 검색 결과 캐시, 분산 캐시와 외부 검색 엔진
- 측정 전에 모든 필터를 묶어 추가하는 추측성 복합 인덱스
- 게임과 방이 하나의 물리 인덱스를 공유한다는 가정
- 임의 응답시간 목표를 제품 완료 기준으로 만드는 작업
- 인기 랭킹 등 아직 채택하지 않은 후속 검색 기능의 성능 검증

## FND-10 실시간 전달과 재연결 기반

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기능 규칙 | [CHAT-03 실시간 전달·재연결 복구](chatting.md#chat-03-실시간-전달재연결-복구) |
| API 계약 | [채팅 공통 계약](../API.md#채팅-공통-계약), [실시간 메시지 구독](../API.md#chat-03-실시간-메시지-구독) |
| 인증·인가 | [ADR-0003](../adr/auth/0003-p0-server-session-spring-security.md), [ADR-0020](../adr/auth/0020-api-endpoint-authorization-policy-registry.md) |
| ADR | [ADR-0031](../adr/chat/0031-chat-history-cursor-pagination.md)·[ADR-0032](../adr/chat/0032-http-send-websocket-receive.md)·[ADR-0033](../adr/chat/0033-postgresql-source-after-commit-delivery.md)·[ADR-0038](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)·[ADR-0052](../adr/platform/0052-local-profile-multi-instance-default.md) — 승인됨 |
| 저장·구조 계약 | [ERD](../ERD.md), [아키텍처](../ARCHITECTURE.md) |
| 필수 검증 환경 | 로컬 프록시, Spring 애플리케이션 두 대, 공용 PostgreSQL·Redis로 구성한 `local` |
| 착수 전 확정 | [ADR-0052](../adr/platform/0052-local-profile-multi-instance-default.md)가 확정한 `local` 세션 TTL 30분·JSON 직렬화(`SecurityJacksonModules`와 `CurrentUserPrincipal` mixin)와 `albam-mate:local:session\|ratelimit\|chat:events` namespace를 따른다. `production`은 같은 세션 계약을 적용해 `albam-mate:production:session\|chat:events`를 사용한다. `test`·`postgresTest`는 인메모리 저장소를 사용하며 Redis profile은 fallback하지 않는다. |

### 산출물

- 기존 `JSESSIONID`와 허용 `Origin`을 검증하는 방별 WebSocket handshake 경계
- [ADR-0038](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md)에 따라 Spring Session Redis로 공유해 HTTP와 WebSocket이 서로 다른 인스턴스에 도달해도 유지되는 인증 경계
- 현재 방 상태와 주최자·`ACTIVE` 참가 관계를 확인하는 `room` 공개 계약과 채팅 접근 검사
- `afterMessageId` 이후 누락 이력을 ID 오름차순으로 전달하고 복구 중 새 이벤트를 버퍼링·중복 제거하는 재연결 흐름
- PostgreSQL 메시지 커밋 뒤 `eventType`·`roomId`·`messageId`만 Redis로 발행하고 각 인스턴스가 DB catch-up하는 fan-out 경로
- Redis 신호 누락·중복·순서 역전과 AFTER_COMMIT 발행 실패를 다음 신호·이력 조회·재연결로 복구하는 경로
- 세션·rate limit 실패의 `503 SERVICE_UNAVAILABLE`과 커밋 뒤 Pub/Sub 실패의 저장 성공을 구분하는 실패 경계
- 관계·방 상태·세션 변경 뒤 기존 연결의 권한을 회수하는 경로. 신호·이벤트 유실과 무관하게 전달 직전 PostgreSQL 관계·상태와 공용 세션 유효성을 확인하고, 만료 또는 확인 실패 시 전달을 막고 연결을 종료한다
- 메시지 본문·세션·내부 사용자 식별자를 포함하지 않는 연결 수, 저장 후 전달 지연, 실패와 복구 관측값

### 완료 기준

- `FND-10-AC1` WebSocket handshake가 현재 HTTP 세션과 허용 `Origin`을 검증하고 별도 JWT나 WebSocket 전용 토큰을 만들지 않는다.
- `FND-10-AC2` 연결·복구·실시간 전환 시점마다 현재 방 상태와 주최자·`ACTIVE` 참가 관계를 서버에서 확인한다.
- `FND-10-AC3` `afterMessageId` 이후의 커밋 메시지를 `messageId ASC`로 먼저 전달하고 복구 중 도착한 이벤트를 중복·누락 없이 실시간 흐름에 합친다.
- `FND-10-AC4` 커밋된 메시지만 전달하며 실시간 전달 실패가 저장 성공을 롤백하거나 저장된 메시지를 삭제하지 않는다.
- `FND-10-AC5` 참가 취소, 방 최종 상태 전환과 세션 만료 뒤 기존 연결이 새 메시지를 받지 않는다. 관계 변경 신호나 Spring Session 만료·삭제 이벤트가 유실된 상태를 재현해도 전달 직전 확인이 전달을 막고 연결을 종료한다.
- `FND-10-AC6` 연결 수, 저장 후 전달 지연, 전달 실패와 재연결 복구 결과를 민감 정보 없이 관찰할 수 있다.
- `FND-10-AC7` 승인된 채팅 ADR과 API·ERD·아키텍처 계약에 구현이 일치하고 단위·HTTP·PostgreSQL·실시간 통합 검증이 재현된다.
- `FND-10-AC8` `local`에서 HTTP 저장과 WebSocket 연결이 서로 다른 애플리케이션 인스턴스에 배정되어도 공용 세션, Redis 신호와 PostgreSQL catch-up으로 메시지를 수신·복구한다.
- `FND-10-AC9` `local`은 Redis 장애 시 인메모리로 자동 fallback하지 않으며 세션·rate limit 실패와 커밋 뒤 Pub/Sub 실패가 계약된 서로 다른 결과로 검증된다.

### 제외 범위

- WebSocket을 통한 메시지 저장 명령과 양방향 애플리케이션 프로토콜
- 알림의 실시간 도착 표시를 `FND-10` 완료의 선행 또는 필수 범위로 만드는 작업
- Redis Streams, Transactional Outbox, RabbitMQ·Kafka와 exactly-once 실시간 전달
- 실시간 연결을 메시지 정본이나 영속 제품 상태로 저장하는 설계
- 전역 메시지 순서와 exactly-once 전달 보장
- 실제 AWS Nginx 분산·장애 처리와 자체 운영 Redis의 HA·TLS·접근 제어·비용 검증
