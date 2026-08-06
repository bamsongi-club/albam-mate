# P1 방 채팅 기능 명세

이 문서는 P1에서 기존 방의 주최자와 현재 `ACTIVE` 참가자가 모임을 조율하는 `CHAT-01`~`CHAT-05`의 구현 규칙과 완료 기준을 정의한다. 현재 계약 준비·생산 코드·자동 검증·운영 배포와 실측 상태는 [P1 기능별 상태 정본](README.md#기능별-현재-상태)을 따른다. 이 문서에서 **채팅 관계자**는 방의 주최자 또는 현재 `ACTIVE` 참가자를 뜻한다.

채팅 접근·생명주기와 메시지 공통 규칙은 [P1 명세](../P1-spec.md#채팅-접근과-생명주기), 요청·응답·오류와 실시간 연결은 [API 명세](../API.md#채팅-공통-계약), 저장 계약은 [ERD](../ERD.md)를 따른다. 신규 API와 저장 개념은 구현 예정 계약이며, 메시지 ID cursor·실시간·저장·보관 방식은 승인된 [ADR-0031](../adr/chat/0031-chat-history-cursor-pagination.md)·[ADR-0032](../adr/chat/0032-http-send-websocket-receive.md)·[ADR-0033](../adr/chat/0033-postgresql-source-after-commit-delivery.md)·[ADR-0049](../adr/chat/0049-chat-message-retention-lock-section-boundary.md), 공용 세션·스케줄 실행은 [ADR-0038](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md), 모듈·인프라 경계는 [아키텍처](../ARCHITECTURE.md)를 따른다. 채팅방 스키마와 기존 ROOM backfill의 실행 경계는 승인된 [ADR-0045](../adr/chat/0045-chat-room-schema-and-backfill-boundary.md)의 production schema-only 및 local callback 결정을 따른다. 실시간 공통 기반은 [FND-10](foundation.md#fnd-10-실시간-전달과-재연결-기반)이 소유한다.

본 명세는 기존 오프라인 방 흐름에 방별 그룹 채팅을 추가하며 새로운 온라인 방 유형이나 실시간 자동 매칭을 도입하지 않는다. 메시지의 정본은 실시간 연결이 아니라 PostgreSQL 이력이다.

## 실행 환경과 실패 경계

- `local-single`은 실제 Spring profile `local`을 사용하는 빠른 단일 서버 개발 환경이며 인메모리 세션·fan-out을 허용하지만 다중 인스턴스 검증 근거가 아니다.
- P1 필수 검증 환경인 `local-multi`는 로컬 프록시, Spring 애플리케이션 두 대, 공용 PostgreSQL과 Redis로 구성한다.
- `production`의 목표 운영 토폴로지는 ALB·ASG 애플리케이션 인스턴스와 공용 RDS PostgreSQL·Redis로 구성한다. 이 목표는 현재 운영 배포 완료를 뜻하지 않으며, 배포·실측 상태는 [P1 기능별 상태 정본](README.md#기능별-현재-상태)의 `운영 배포·실측` 열을 따른다. 실제 AWS scale-out·WebSocket Upgrade·연결 draining 검증은 후속 OPS이며 채팅 구현 완료를 막지 않는다.
- `local-multi`와 `production`은 Spring Session, Pub/Sub과 사용자·방 단위 전송 제한에 각자 공용 Redis 하나를 사용하되 key prefix, TTL과 channel namespace를 프로필별로 분리한다. Redis가 없을 때 인메모리 구현으로 자동 fallback하지 않는다.
- 세션 또는 전송 제한을 확인할 수 없으면 API 정본의 `503 SERVICE_UNAVAILABLE`로 실패한다. PostgreSQL 커밋 뒤 Redis Pub/Sub 발행·구독이 실패하면 저장 성공은 유지하고 이력 조회·다음 신호·재연결로 복구한다.
- 운영 Redis 제품, HA, TLS, 접근 제어, 비밀 주입과 비용은 후속 OPS에서 확정한다.
- 채팅 전송 제한의 사용자·방 임계값, 고정 창·TTL, 원자 판정, `Retry-After`와 Redis 장애 시 503 경계는 [#288 승인 댓글](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930)에서 승인했고 이 문서와 [API 정본](../API.md#전송-제한-계약)에 반영한다. 공용 Redis namespace의 분리와 #360에서 확정한 `local-multi` session namespace는 아래 계약과 [FND-10](foundation.md#fnd-10-실시간-전달과-재연결-기반)을 따른다.
- `local-multi`와 `production`의 세션 TTL은 30분이며 JSON 직렬화에 `SecurityJacksonModules`와 `CurrentUserPrincipal` mixin을 사용한다. session namespace는 각각 `albam-mate:local-multi:session`, `albam-mate:production:session`이다.

## CHAT-01 채팅방 생성·접근

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 기존 권한 계약 | [ParticipantRoomResponse](../API.md#48-participantroomresponse), [방 상세 조회](../API.md#room-02-방-상세-조회) |
| 기존 상태 계약 | [RoomStatus](../API.md#roomstatus), [ParticipationStatus](../API.md#participationstatus) |
| 기존 데이터 모델 | [ROOMS](../ERD.md#rooms), [PARTICIPATIONS](../ERD.md#participations) |
| 인증·인가 | [ADR-0003 서버 세션](../adr/auth/0003-p0-server-session-spring-security.md), [ADR-0020 엔드포인트 인가 정책](../adr/auth/0020-api-endpoint-authorization-policy-registry.md) |
| 동시성 | [ADR-0005 방 참가 낙관 락](../adr/participation/0005-room-participation-optimistic-locking.md) |
| 관련 정본 | [채팅 API](../API.md#채팅-공통-계약), [ERD](../ERD.md#chat_rooms), [아키텍처](../ARCHITECTURE.md#모듈-관계), [채팅 ADR](../adr/chat/README.md) |

### 기능 규칙

- `V6__create_p1_chat_room_schema.sql`은 `CHAT_ROOMS` 테이블·제약만 생성하며 기존
  `ROOMS`를 조회하거나 `CHAT_ROOMS` 행을 삽입·갱신하지 않는다.
- [#279의 최신 승인 테스트 계약](https://github.com/bamsongi-club/albam-mate/issues/279#issuecomment-5161788285)은
  기존 ROOM backfill·상태별 초기화·ROOM 생성·상태 전환과의 경합·최종 보정·배포 절체를
  [#281](https://github.com/bamsongi-club/albam-mate/issues/281)의 후속 범위로 분리한다.
  [ADR-0045](../adr/chat/0045-chat-room-schema-and-backfill-boundary.md)은 production
  스키마 기동과 local callback 초기화의 경계를 승인한다. production Flyway 자동 실행에는
  기존 ROOM 데이터 작업이 없고, local profile에서만 `db/local/afterMigrate.sql` callback이
  개발·검증용 초기화를 수행한다.
- 방 생성이 성공한 트랜잭션에서 해당 방의 채팅방을 함께 생성한다.
- 채팅방 생성 여부는 모집 인원과 참가자 수에 의존하지 않는다.
- 채팅 접근 여부는 저장된 채팅 회원 목록이 아니라 현재 방 주최자·참가 관계를
  기준으로 매 요청마다 판정한다.
- 채팅 구현은 방·참가 Entity나 Repository를 직접 소유하지 않는다. 현재 관계를
  확인하는 공개 계약은 `room`이 제공하고 채팅 모듈은 그 계약만 사용한다.

### 완료 기준

- `CHAT-01-AC1` 채팅 활성화 전에 존재한 방과 이후 생성된 방마다 채팅방이 정확히
  하나 존재한다. 첫 운영 배포에 채팅을 포함하는 현재 계획에서는 활성화 전에 존재한
  방이 없으므로 이후 생성된 방만 검증 대상이다.
- `CHAT-01-AC2` 방 생성 트랜잭션이 롤백되면 채팅방도 생성되지 않는다.
- `CHAT-01-AC3` 방 생성자는 다른 참가자가 없어도 채팅방에 메시지를 작성할 수
  있다.
- `CHAT-01-AC4` 새 `ACTIVE` 참가자는 참가 전에 방 생성자가 작성한 메시지를
  포함한 기존 이력을 조회할 수 있다.
- `CHAT-01-AC5` 주최자와 현재 `ACTIVE` 참가자만 현재 상태 방의 채팅방에
  접근하며, 참가 취소·세션 만료 뒤에는 즉시 접근이 거절된다.
- `CHAT-01-AC6` `CANCELED` 또는 `FINISHED` 방은 메시지 조회·전송·실시간 구독이
  모두 거절된다.
- `CHAT-01-AC7` #279의 V6 적용은 기존 ROOM을 조회하거나 backfill하지 않고 `CHAT_ROOMS`
  스키마·제약만 생성한다.
- `CHAT-01-AC8` (보류) #281의 PostgreSQL 통합 테스트는 기존 방 backfill과 방 생성·상태 전환을
  경쟁시켜도 완료 뒤 모든 방에 채팅방이 정확히 하나 있고, backfill 행의 보관 값이 선택한
  초기화 경계의 ROOM 상태와 일치함을 검증한다. 첫 운영 배포에 채팅을 포함하는 현재 계획에서는
  backfill 대상이 없어 이 기준을 보류하며, 채팅 없이 먼저 배포하는 계획으로 바뀌면 재도입한다.

### 제외 범위

- 온라인 자동 매칭과 매칭 대기열
- 채팅방 수동 생성·초대·퇴장
- 방과 무관한 공개 채팅과 사용자 간 개인 채팅
- 채팅 회원 목록의 별도 소유

## CHAT-02 메시지 전송·이력 조회

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| HTTP 계약 | [CHAT-02 메시지 전송](../API.md#chat-02-메시지-전송), [CHAT-02 메시지 이력 조회](../API.md#chat-02-메시지-이력-조회) |
| 저장 계약 | [CHAT_ROOMS](../ERD.md#chat_rooms), [CHAT_MESSAGES](../ERD.md#chat_messages)의 유일성·조회 인덱스·보관 규칙 |
| 공통 응답·오류 | [API 공통 계약](../API.md#1-공통-계약), [오류 코드](../API.md#10-오류-코드) |
| 시간 기준 | [ADR-0009 UTC 저장과 서비스 시간대 변환](../adr/platform/0009-utc-time-standard.md) |
| 검증 환경 | [ADR-0010 H2와 PostgreSQL 테스트 경계](../adr/platform/0010-h2-postgresql-test-boundary.md) |
| 기술 결정 | [ADR-0031 메시지 ID 커서](../adr/chat/0031-chat-history-cursor-pagination.md) — 승인됨, [ADR-0033 PostgreSQL 정본·커밋 후 전달](../adr/chat/0033-postgresql-source-after-commit-delivery.md) — 승인됨, [#288 전송 제한 계약 승인](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930) |

### 기능 규칙

- 메시지 전송은 인증과 CSRF 검증 뒤 채팅 관계·채팅방 상태·본문을 순서대로
  검증한다.
- 메시지 Entity를 API 응답으로 직접 반환하지 않고 전용 응답으로 변환한다.
- 메시지 이력은 최신 구간부터 제한된 크기로 조회하며, 추가 조회는 메시지
  식별자 기반 커서를 사용한다.
- 이력은 `messageId DESC`로 반환한다. 기본 크기는 50, 최대 크기는 100이며,
  더 과거 메시지가 없으면 `200 OK`와 빈 배열을 반환한다.
- 존재하지 않는 양수 커서는 정상 경계값으로 사용하고, 0·음수·숫자가 아닌 값은
  검증 오류로 거절한다.
- 이력 항목은 메시지 식별자, 방 식별자, 작성자 표시 정보, 본문과 서버 생성 시각을
  제공한다. 내부 사용자 식별자와 참가 관계 정보는 노출하지 않는다.
- 동일한 클라이언트 메시지 식별자로 재전송된 요청은 멱등하게 처리한다.
- 인증·관계·본문·멱등성 검증을 통과한 신규 전송에만 사용자 bucket 5건/10초와 방 bucket 30건/10초를 적용한다. 사용자 bucket은 모든 방, 방 bucket은 모든 참여자의 전송을 합산한다.
- 두 bucket은 10초 고정 창으로 동작하고 TTL을 연장하지 않는다. 허용 확인과 증가는 원자적으로 처리하며 하나라도 초과하면 어느 bucket도 증가시키지 않는다. 검증 실패·권한 거부·이미 저장된 동일 payload의 멱등 재전송은 quota를 소비하지 않는다.
- 제한 초과는 429와 초과 bucket의 남은 TTL을 올림한 `Retry-After`를 반환한다. 두 bucket이 초과하면 더 큰 값을 사용하며, 이 헤더는 429에만 포함한다. Redis 제한 상태 확인 실패·결과 불명확은 저장 전 503으로 실패하고 인메모리 fallback과 `Retry-After`를 허용하지 않는다.
- 사용자가 참가를 취소하거나 방이 최종 상태로 전이되는 요청과 메시지 전송이
  겹치면, 커밋 시점의 최신 권한·상태를 만족한 메시지만 저장한다.

### 완료 기준

- `CHAT-02-AC1` 쓰기 가능한 채팅 관계자는 유효한 일반 텍스트 메시지를 전송하고
  서버가 확정한 결과를 받는다.
- `CHAT-02-AC2` 공백·길이 초과·지원하지 않는 형식은 저장하지 않고 계약된 검증
  오류를 반환한다.
- `CHAT-02-AC3` 같은 사용자의 같은 클라이언트 메시지 식별자가 반복돼도 메시지는
  하나만 저장되고 최초 결과가 유지된다.
- `CHAT-02-AC4` 채팅 이력은 서버 메시지 식별자 순서를 유지하며, 동시 저장 중에도
  중복·누락 없는 커서 조회를 제공한다.
- `CHAT-02-AC5` 다른 방 관계자, 참가 취소 사용자와 비로그인 사용자는 메시지
  전송·이력 조회가 모두 거절된다.
- `CHAT-02-AC6` PostgreSQL 통합 테스트에서 참가 취소·방 최종 상태 전이와 메시지
  전송이 겹쳐도 권한 없는 메시지가 커밋되지 않는다.

### 제외 범위

- 메시지 수정과 작성자 직접 삭제
- 이미지·파일·음성·영상 첨부
- 링크 미리보기와 외부 플랫폼 접속 정보 검증
- 전문 검색과 과거 이력 내 키워드 검색

## CHAT-03 실시간 전달·재연결 복구

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 전달 계약 | [CHAT-03 실시간 메시지 구독](../API.md#chat-03-실시간-메시지-구독)의 인증, 이벤트 식별자와 재연결 계약 |
| 공유 기반 | [FND-10 실시간 전달과 재연결 기반](foundation.md#fnd-10-실시간-전달과-재연결-기반) |
| 기술 결정 | [ADR-0038 공용 세션](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md), [ADR-0032 HTTP 저장·WebSocket 수신](../adr/chat/0032-http-send-websocket-receive.md), [ADR-0033 PostgreSQL 정본·커밋 후 전달](../adr/chat/0033-postgresql-source-after-commit-delivery.md) |
| 인증·인가 | [ADR-0003 서버 세션](../adr/auth/0003-p0-server-session-spring-security.md), [ADR-0020 엔드포인트 인가 정책](../adr/auth/0020-api-endpoint-authorization-policy-registry.md) |
| 운영 기준 | 연결 수, 저장 후 전달 지연, 전달 실패와 재연결 복구 결과를 계측 |

### 기능 규칙

- P1은 인증된 HTTP로 메시지를 전송·조회하고, 방별 WebSocket으로 커밋된 메시지를
  실시간 수신한다. WebSocket으로 메시지 저장 명령을 받지 않는다.
- WebSocket handshake는 기존 `JSESSIONID` 세션과 허용된 `Origin`을 검증하며,
  별도 JWT·WebSocket 전용 토큰을 사용하지 않는다. `local-multi`와 `production`의 세션은
  Spring Session Redis에 공유하고 ALB stickiness에 정합성을 의존하지 않는다.
- 허용 `Origin`은 `app.chat.websocket.allowed-origin` 하나로 프로필별로 주입한다.
  비운영 프로필은 프런트엔드 개발 서버 `http://localhost:5173`을 사용하고, `production`은
  운영 도메인을 하드코딩하지 않고 `ALBAM_MATE_CHAT_WEBSOCKET_ALLOWED_ORIGIN`으로 주입하며
  값이 비어 있으면 모든 handshake를 거절한다.
- 실시간 연결을 열거나 유지하는 동안에도 현재 채팅 관계를 검증한다.
- 저장 성공 응답과 실시간 이벤트는 같은 메시지 식별자를 사용한다.
- PostgreSQL 커밋 뒤 Redis Pub/Sub에는 `eventType`, `roomId`, `messageId`만
  발행한다. 메시지 본문·사용자·세션 식별자는 신호에 포함하지 않는다.
- 각 인스턴스는 자신이 보유한 WebSocket 연결만 메모리에서 관리한다. Redis 신호를
  받으면 연결별 마지막 전달 ID 이후의 PostgreSQL 메시지를 `messageId ASC`로
  조회해 중복을 제거하고 전달한다.
- 실시간 전달 실패가 메시지 저장 트랜잭션을 롤백하거나 저장된 메시지를 삭제하게
  해서는 안 된다.
- 서버 재시작, 네트워크 단절과 Redis 신호 누락·중복·순서 역전은 다음 신호 또는
  마지막 `afterMessageId` 이후의 PostgreSQL 이력을 오래된 순서로 전송해 복구한다.
- 참가 취소와 방 최종 상태 신호는 방의 로컬 연결이 권한을 다시 확인하도록 하고,
  세션 만료 이벤트는 해당 연결을 종료한다. 두 경로 모두 촉진 수단이며 권한 회수의
  근거가 아니다. 전달 직전에는 PostgreSQL의 현재 관계·상태와 공용 세션의 현재
  유효성을 함께 확인해 신호 누락이 권한 우회로 이어지지 않게 한다. 세션이
  만료됐거나 세션 상태를 확인할 수 없으면 전달하지 않고 연결을 종료한다.
- 알림 기능과 실시간 연결을 공유할지는 알림 interface가 확정된 뒤 결정하며, 채팅
  명세가 알림 구현을 선행 조건으로 요구하지 않는다.

### 완료 기준

- `CHAT-03-AC1` 커밋된 메시지만 현재 연결된 채팅 관계자에게 실시간 전달된다.
- `CHAT-03-AC2` 실시간 이벤트가 중복 도착해도 메시지 식별자로 하나만 표시된다.
- `CHAT-03-AC3` 연결이 끊긴 사용자는 재연결 시 `afterMessageId` 이후의 누락
  메시지를 받은 뒤 실시간 수신으로 전환한다.
- `CHAT-03-AC4` 서버 재시작 뒤에도 기존 메시지 이력과 순서가 유지된다.
- `CHAT-03-AC5` 참가 취소·세션 만료 사용자와 최종 상태 방의 기존 연결은 더 이상
  새 메시지를 전달받지 않는다. 관계 변경 신호나 세션 만료 이벤트가 유실된 경우에도
  전달 직전 확인이 전달을 막고 연결을 종료한다.
- `CHAT-03-AC6` 저장부터 전달까지의 지연, 연결 수와 전달 실패를 사용자·방
  식별자 없는 메트릭으로 관찰할 수 있다.
- `CHAT-03-AC7` `local-multi`에서 HTTP 저장과 WebSocket 연결이 서로 다른
  애플리케이션 인스턴스에 배정되어도 공용 세션과 Redis 신호로 메시지를 수신한다.
- `CHAT-03-AC8` Redis 신호가 누락·중복·역전되거나 커밋 뒤 발행이 실패해도
  PostgreSQL 저장 결과가 유지되고 다음 신호·이력 조회·재연결에서 누락분이
  `messageId ASC`로 복구된다.
- `CHAT-03-AC9` `local-multi`에서 Redis 세션 저장소 실패와 커밋 뒤 Pub/Sub 발행
  실패를 각각 재현했을 때 인메모리로 자동 fallback하지 않고 계약된 서로 다른
  결과로 검증된다.

### 제외 범위

- 입력 중 표시, 접속 상태와 실시간 읽음 표시
- WebSocket을 통한 메시지 저장과 양방향 애플리케이션 프로토콜
- Redis Streams·RabbitMQ·Kafka 기반 영속 메시지 전달
- 커밋 후 발행 누락을 자동 재처리하는 채팅 Outbox
- 메시지 전달 순서의 전역 보장
- 실제 AWS ALB·ASG scale-out·draining과 운영 Redis 제품·HA·TLS·비용 검증

## CHAT-04 채팅 안전·운영

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 입력·오류 | [API 공통 계약](../API.md#1-공통-계약), [채팅 API 오류 계약](../API.md#채팅-공통-계약) |
| 로그 | [Logging 규칙](../CONVENTIONS.md#logging) |
| 보안 | 세션 인증, CSRF, 출력 인코딩과 Redis 사용자·방 단위 전송 제한 |
| 관련 정본 | [ADR-0049 메시지 보관·삭제와 잠금 구간 경계](../adr/chat/0049-chat-message-retention-lock-section-boundary.md), [ADR-0038 스케줄 실행 조정](../adr/platform/0038-multi-instance-session-and-scheduler-coordination.md), [#288 전송 제한 계약 승인](https://github.com/bamsongi-club/albam-mate/issues/288#issuecomment-5175338930), [CHAT_ROOMS](../ERD.md#chat_rooms), [SHEDLOCK](../ERD.md#shedlock) |

### 기능 규칙

- 메시지는 일반 텍스트로 렌더링하고 사용자 입력을 HTML로 실행하지 않는다.
- 사용자·방 단위 전송 제한은 `local-multi`와 `production`의 공용 Redis에서 프로필별로 분리한
  key prefix와 TTL로 관리한다. 사용자 bucket은 5건/10초, 방 bucket은 30건/10초의
  10초 고정 창이며 TTL을 연장하지 않는다. 두 bucket의 확인·증가는 원자적으로
  처리하고, 초과 요청은 counter를 증가시키지 않는다.
- 인증·관계·본문·멱등성 검증 실패와 동일 payload의 멱등 재전송은 제한량을 소비하지
  않는다. 초과 시 저장하지 않고 429와 초과 bucket TTL을 올림한 `Retry-After`를
  반환하며, 두 bucket이 초과하면 더 큰 값을 사용한다. Redis 제한 상태 확인 실패나
  결과 불명확은 인메모리 fallback 없이 저장 전 `503 SERVICE_UNAVAILABLE`으로
  실패하고 `Retry-After`를 포함하지 않는다.
- 메시지 본문, 세션 식별자와 내부 사용자 식별자는 로그·메트릭에 포함하지 않는다.
- 만료 삭제 작업은 성공·삭제 건수·지연·실패를 기록하고, 실패한 묶음만 다음
  스케줄에서 다시 처리한다.
- 모든 인스턴스가 만료 삭제 스케줄을 등록하되 PostgreSQL ShedLock을 얻은 하나만
  실행한다. 잠금과 별개로 삭제 작업은 재실행해도 같은 결과로 수렴하며 각 묶음은
  독립 트랜잭션을 유지한다.

### 완료 기준

- `CHAT-04-AC1` 스크립트·HTML 형태의 입력이 다른 사용자의 브라우저에서 실행되지
  않는다.
- `CHAT-04-AC2` 전송 제한을 초과한 요청은 메시지를 저장하지 않고 429와 초과
  bucket TTL에 따른 `Retry-After`를 반환한다.
- `CHAT-04-AC3` 애플리케이션 로그와 운영 메트릭에 메시지 본문·세션 식별자·내부
  사용자 식별자가 기록되지 않는다.
- `CHAT-04-AC4` 최종 상태 전환 뒤 30일이 지난 메시지는 다음 일일 스케줄에서
  ShedLock을 얻은 한 인스턴스가 소량 묶음으로 삭제하고, 실패는 메트릭·알림으로
  확인할 수 있다.
- `CHAT-04-AC5` 두 애플리케이션 인스턴스에 같은 만료 스케줄이 등록되어도 한
  실행만 작업을 소유하고, 각 삭제 묶음의 성공은 다른 묶음 실패로 롤백되지 않는다.
- `CHAT-04-AC6` `local-multi`와 `production`에서 Redis 전송 제한 상태를 확인할 수 없거나 결과가
  불명확하면 인메모리로 fallback하지 않고 메시지도 저장하지 않은 채
  `Retry-After` 없는 `503 SERVICE_UNAVAILABLE`을 반환한다.

### 제외 범위

- 자동 욕설 판정과 AI 콘텐츠 검수
- 메시지 신고·운영자 숨김
- 사용자 차단·음소거
- 법적 보존 요청과 이의제기 절차
- 운영자 화면의 구체적인 UI
- Quartz 클러스터와 전용 스케줄러 서비스

## CHAT-05 내 모임 채팅 진입

### 구현 컨텍스트

| 구분 | 정본 |
| --- | --- |
| 모임 상세 계약 | [ParticipantRoomResponse](../API.md#48-participantroomresponse), [ROOM-02 방 상세 조회](../API.md#room-02-방-상세-조회) |
| 관계·상태 | [MyRole](../API.md#myrole), [RoomStatus](../API.md#roomstatus), [ParticipationStatus](../API.md#participationstatus) |
| 접근 검증 | 화면 표시와 별개로 모든 채팅 요청에서 서버가 현재 관계·상태를 다시 검증 |
| 목록 보조 계약 | [MyRoomListItem.chatAvailable](../API.md#410-myroomlistitem)은 접근 가능성 일치 검증용이며, 내 모임의 직접 진입 버튼을 결정하지 않는다 |
| 관련 정본 | [ChatMessage.isMine](../API.md#415-chatmessage), 프론트엔드 라우팅 |

### 기능 규칙

- 모임 상세는 요청자가 주최자 또는 현재 `ACTIVE` 참가자이고, 방 상태가
  `RECRUITING` 또는 `CLOSED`일 때 채팅 진입을 표시한다.
- 방 생성자는 다른 참가자가 없어도 생성한 모임 상세에서 채팅방으로 진입할 수 있다.
- 사용자는 참가 성공 뒤 모임 상세에서 채팅방으로 진입하며, 참가 전에 저장된 기존
  메시지도 조회한다.
- 내 모임 화면에는 모임 상세와 중복되는 직접 채팅 버튼을 표시하지 않는다.
- 참가 취소, 세션 만료 또는 방의 `CANCELED`·`FINISHED` 전이 뒤에는 모임 상세에서
  채팅 진입을 표시하지 않는다.
- 화면에서 채팅 진입을 숨긴 것만으로 권한을 보장하지 않으며, 직접 URL 요청도
  서버에서 같은 관계·상태 규칙으로 거절한다.
- P1에서는 별도 채팅방 목록을 만들지 않고 게임 중심·사람 중심 모임 상세의
  채팅 진입점에서 채팅으로 진입한다.

### 완료 기준

- `CHAT-05-AC1` 방 생성자는 생성 직후 모임 상세에서 채팅방으로 진입할 수
  있다.
- `CHAT-05-AC2` 현재 `ACTIVE` 참가자는 참가한 방의 모임 상세에서 채팅방으로
  진입할 수 있다.
- `CHAT-05-AC3` 참가를 취소한 사용자의 모임 상세에는 해당 방의 채팅 진입이
  표시되지 않고 직접 접근도 거절된다.
- `CHAT-05-AC4` `CANCELED` 또는 `FINISHED` 방은 주최자·참가자 모두에게 채팅
  진입이 표시되지 않고 직접 접근도 거절된다.
- `CHAT-05-AC5` `RECRUITING`·`CLOSED`와 현재 관계의 조합별 모임 상세 표시와
  백엔드 접근 결과가 일치하고, 내 모임에는 중복 채팅 진입이 없다.

### 제외 범위

- 취소·종료된 방의 채팅 이력 화면
- 내 모임과 분리된 전체 채팅방 목록
- 읽지 않은 메시지 개수와 채팅방 정렬
