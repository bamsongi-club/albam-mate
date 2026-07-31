# ADR-0033: PostgreSQL을 정본으로 두고 커밋 뒤 Redis로 fan-out

- 상태: 승인됨
- 작성일: 2026-07-31
- 결정일: 2026-08-01
- 관련: [P1 방 채팅 명세](../../p1/chatting.md), [채팅 저장 계약](../../ERD.md#chatmessages), [아키텍처](../../ARCHITECTURE.md#채팅-흐름), [ADR-0002 PostgreSQL](../platform/0002-postgresql-primary-database.md), [ADR-0021 AWS 배포 기준선](../platform/0021-p0-aws-ec2-rds-deployment-baseline.md), [ADR-0031 커서 조회](0031-chat-history-cursor-pagination.md), [ADR-0032 실시간 전달](0032-http-send-websocket-receive.md), [ADR-0038 공용 세션·스케줄 조정](../platform/0038-multi-instance-session-and-scheduler-coordination.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

메시지 저장과 실시간 전달은 함께 보이지만 실패 경계가 다르다. 저장되지 않은 메시지가 WebSocket으로 보이면 안 되고, WebSocket 연결 하나의 실패가 이미 커밋할 메시지를 롤백해서도 안 된다.

[ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md)의 다중 인스턴스 환경에서는 메시지를 저장한 인스턴스가 수신자의 WebSocket을 보유하지 않을 수 있으므로 프로세스 메모리만으로는 fan-out할 수 없다. 반면 Redis Pub/Sub은 at-most-once이므로 메시지 정본이나 재처리 큐로 사용할 수 없다.

메시지 전송은 참가 취소나 방 최종 상태 전환과 동시에 실행될 수 있다. 권한 확인 뒤 상태가 바뀐 메시지가 커밋되지 않도록 두 변경의 순서를 데이터베이스에서 확정해야 한다. 또한 `messageId` 재연결 커서가 늦게 커밋된 더 작은 ID를 놓치지 않도록 같은 방의 append 순서를 보장해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 저장 트랜잭션 안에서 WebSocket 전송 | 저장과 실시간 호출을 한 흐름으로 볼 수 있다. | 느리거나 실패한 연결이 DB 트랜잭션을 오래 잡고, 외부 전달 실패가 저장을 롤백시킬 수 있다. | 제외 |
| PostgreSQL 저장 후 AFTER_COMMIT 인메모리 전달 | 단일 프로세스에서는 신규 서비스 없이 커밋된 메시지만 전달한다. | 다른 인스턴스의 WebSocket 연결에 도달하지 않는다. | 제외 |
| Transactional Outbox | 커밋과 전달 대상을 함께 저장해 재처리할 수 있다. | 메시지마다 추가 쓰기와 폴러·정리 작업이 필요하고 작은 RDS의 I/O·운영 범위를 늘린다. | 보류 |
| PostgreSQL 저장 후 AFTER_COMMIT Redis Pub/Sub 신호 | 공용 Redis로 모든 인스턴스를 깨우고 실제 메시지는 PostgreSQL에서 복구한다. | Pub/Sub 신호는 영속되지 않아 누락될 수 있고 Redis 운영이 추가된다. | 선택 |
| Redis Streams·RabbitMQ·Kafka | ACK·재처리 또는 여러 소비자 그룹과 장기 replay를 제공할 수 있다. | P1이 요구하지 않는 broker 운영과 전달 계약이 추가된다. | 제외 |

## 결정

PostgreSQL의 `CHAT_MESSAGES`를 메시지 최종 정본으로 사용하고, 성공적으로 커밋된 뒤에만 Redis Pub/Sub으로 `eventType`, `roomId`, `messageId`를 담은 전달 신호를 발행한다. `MESSAGE_CREATED`는 `messageId`를 필수로 사용하고 관계·방 상태 변경 신호는 `messageId`를 비워 해당 방의 로컬 연결 전체가 권한을 다시 확인하게 한다. 메시지 본문, 세션 식별자와 사용자 식별자는 Redis 신호에 포함하지 않는다.

메시지 전송은 하나의 일반 `@Transactional` 트랜잭션으로 처리하며 `REQUIRES_NEW`와 낙관 락 재시도를 사용하지 않는다.

1. `room.contract`의 채팅 접근 guard가 `ROOMS` 행에 짧은 공유 잠금을 얻고 현재 주최자·`ACTIVE` 참가 관계와 `RECRUITING`·`CLOSED` 상태를 검증한다.
2. 같은 잠금 순서에서 `CHAT_ROOMS` 행에 쓰기 잠금을 얻는다. 메시지 ID 할당 전에 방별 append를 직렬화해 같은 방의 `messageId` 순서와 커밋 가시성 순서를 맞춘다.
3. `senderUserId`와 `clientMessageId`로 기존 메시지를 확인한다. 같은 키와 같은 본문이면 최초 결과를 반환하고, 다른 본문이면 검증 오류로 거절한다.
4. `CHAT_MESSAGES`를 저장하고 `MessageCommitted` 애플리케이션 이벤트를 등록한다.
5. DB 커밋이 성공하면 `@TransactionalEventListener`의 `AFTER_COMMIT`, `fallbackExecution=false` 경계에서 `chat.contract.ChatRealtimePublisher`를 호출한다.
6. `local-multi`와 운영의 Redis adapter는 환경별 채널에 전달 신호를 발행하고, 각 인스턴스 subscriber는 자신이 WebSocket 연결을 보유한 방만 처리한다.
7. subscriber는 신호 payload를 그대로 사용자에게 보내지 않는다. 연결별 마지막 전달 `messageId`보다 큰 PostgreSQL 메시지를 조회해 ID 오름차순으로 전달하고 이미 전달한 ID는 제거한다.
8. 참가 취소와 방 최종 상태 변경이 커밋된 뒤 관계 변경 신호를 발행해 해당 방의 로컬 연결이 현재 관계·상태를 다시 확인하도록 촉진한다. 세션 만료는 Spring Session 이벤트로 해당 연결을 종료한다. 어떤 신호가 누락되어도 메시지 전달 직전에 PostgreSQL의 현재 관계와 상태를 다시 확인한다.

잠금 순서는 `ROOMS` 다음 `CHAT_ROOMS`로 고정한다. 메시지마다 `Room.version`을 증가시키지 않는다. 참가 취소·방 상태 변경은 `ROOMS`를 갱신하므로 메시지 전송의 공유 잠금과 충돌해 둘 중 먼저 얻은 트랜잭션이 커밋된 뒤 다른 요청이 최신 권한·상태를 다시 확인한다.

Redis Pub/Sub의 신호 누락·중복·순서 역전을 정상 실패 모델로 받아들인다. 같은 방의 다음 신호 또는 클라이언트의 이력 조회와 `afterMessageId` 재연결이 PostgreSQL catch-up을 수행한다. 따라서 실시간 전달은 best effort이며 exactly-once나 지속 큐를 보장하지 않는다.

Redis 발행·구독 또는 WebSocket 전달 실패는 저장 결과를 바꾸지 않는다. `AFTER_COMMIT` 실패를 기록하고 단계별 실패 메트릭을 증가시키되 이미 커밋된 메시지는 성공 응답과 이력에 남긴다.

공용 Redis의 용도 분리, 세션·전송 제한 장애와 fallback 정책은 [ADR-0038](../platform/0038-multi-instance-session-and-scheduler-coordination.md)이 소유한다. 이 ADR은 채팅 Pub/Sub 신호의 payload, 발행 시점과 실패 복구만 소유한다.

## 결과

- 얻는 것: 롤백된 메시지는 전달되지 않고, 저장 인스턴스와 WebSocket 인스턴스가 달라도 실시간 신호가 전달된다. Redis 장애가 저장된 이력을 지우지 않으며, 순서와 누락 복구는 PostgreSQL `messageId` 계약 하나로 유지한다.
- 감수할 비용·위험: Redis Pub/Sub은 at-most-once라 다음 신호나 재연결 전까지 실시간 표시가 늦을 수 있다. 공용 Redis의 비용·보안·장애 대응과 구독 연결 운영이 추가되고, 방별 메시지 쓰기는 짧게 직렬화된다.
- 후속 작업: 채팅 channel 이름을 ADR-0038의 namespace 계약 안에서 확정한다. 잠금 순서와 권한 경쟁 PostgreSQL 테스트, AFTER_COMMIT 발행·롤백·Redis 실패 테스트, 두 인스턴스 교차 전달·순서 역전·신호 누락 catch-up 테스트와 단계별 지연·실패 메트릭을 구현한다.

## 보류 및 재검토

- 지금 하지 않는 것: Transactional Outbox, Redis Streams, RabbitMQ, Kafka, 메시지 전송용 `REQUIRES_NEW`
- 보류 이유: P1은 영속 메시지를 PostgreSQL에서 조회할 수 있고, 재연결 없이 모든 실시간 신호를 자동 재처리해야 한다는 요구가 없다.
- 다시 검토할 조건:
    - 재접속 없이도 모든 실시간 이벤트의 자동 재전송을 보장해야 하거나 커밋 후 발행 누락이 허용 범위를 넘을 때 Outbox를 결정한다.
    - 독립 worker의 ACK·재시도·DLQ가 필요할 때 RabbitMQ를, 여러 소비자 그룹과 장기 replay가 필요할 때 Kafka를 결정한다.
    - 방별 append 잠금 대기시간이 실제 사용자 지연의 병목으로 측정될 때 순서·커서 전략을 함께 재검토한다.

## 참고 자료

- [Spring 트랜잭션 바운드 이벤트](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [Redis Pub/Sub 전달 의미](https://redis.io/docs/latest/develop/pubsub/)
- [ADR-0021 P0 AWS 배포 기준선](../platform/0021-p0-aws-ec2-rds-deployment-baseline.md)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 채팅 저장·잠금·AFTER_COMMIT Redis publisher와 subscriber 구현 및 PostgreSQL 경쟁 테스트가 없다.
    - 로컬 두 인스턴스 교차 전달과 Redis 신호 누락·순서 역전 복구를 확인하지 않았다.
    - 실제 AWS의 Redis·ASG 운영 부하는 후속 OPS 검증이 필요하다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
