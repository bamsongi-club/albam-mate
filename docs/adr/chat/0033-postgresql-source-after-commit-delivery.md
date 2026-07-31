# ADR-0033: PostgreSQL을 정본으로 두고 커밋 뒤 인메모리 전달

- 상태: 제안됨
- 작성일: 2026-07-31
- 결정일: 미정
- 관련: [P1 방 채팅 명세](../../p1/chatting.md), [ERD](../../ERD.md) — 구현 전 채팅 저장 계약 추가 필요, [아키텍처](../../ARCHITECTURE.md) — 구현 전 채팅 흐름 추가 필요, [ADR-0002 PostgreSQL](../platform/0002-postgresql-primary-database.md), [ADR-0021 AWS 배포 기준선](../platform/0021-p0-aws-ec2-rds-deployment-baseline.md), [ADR-0031 커서 조회](0031-chat-history-cursor-pagination.md), [ADR-0032 실시간 전달](0032-http-send-websocket-receive.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

메시지 저장과 실시간 전달은 함께 보이지만 실패 경계가 다르다. 저장되지 않은 메시지가 WebSocket으로 보이면 안 되고, WebSocket 연결 하나의 실패가 이미 커밋할 메시지를 롤백해서도 안 된다.

현재 운영 기준선은 단일 EC2 애플리케이션과 PostgreSQL RDS다. Redis, Kafka, RabbitMQ와 Outbox 구현은 없고 다중 인스턴스 요구도 확인되지 않았다. 작은 RDS에서 Outbox 행·폴링 부하를 먼저 추가하거나 별도 Redis 서비스를 운영할 근거가 없다.

메시지 전송은 참가 취소나 방 최종 상태 전환과 동시에 실행될 수 있다. 권한 확인 뒤 상태가 바뀐 메시지가 커밋되지 않도록 두 변경의 순서를 데이터베이스에서 확정해야 한다. 또한 messageId 재연결 커서가 늦게 커밋된 더 작은 ID를 놓치지 않도록 같은 방의 append 순서를 보장해야 한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 저장 트랜잭션 안에서 WebSocket 전송 | 저장과 실시간 호출을 한 흐름으로 볼 수 있다. | 느리거나 실패한 연결이 DB 트랜잭션을 오래 잡고, 외부 전달 실패가 저장을 롤백시킬 수 있다. | 제외 |
| PostgreSQL 저장 후 AFTER_COMMIT 인메모리 전달 | 신규 서비스 비용 없이 커밋된 메시지만 전달하고, 실패를 DB 이력으로 복구한다. | 커밋과 발행 사이 프로세스가 종료되면 실시간 이벤트가 누락될 수 있다. | 선택 |
| Transactional Outbox | 커밋과 전달 대상을 함께 저장해 재처리할 수 있다. | 메시지마다 추가 쓰기와 폴러·정리 작업이 필요하고 작은 RDS의 I/O·운영 범위를 늘린다. | 보류 |
| Redis 또는 외부 broker | 여러 인스턴스에 이벤트를 fan-out하거나 전달 작업을 분리할 수 있다. | 별도 비용·장애 지점·운영 절차가 생기며 P1 단일 인스턴스에는 필요하지 않다. | 보류 |

## 결정

PostgreSQL의 CHAT_MESSAGES를 메시지 최종 정본으로 사용하고, 성공적으로 커밋된 뒤에만 인메모리 WebSocket adapter로 전달한다.

메시지 전송은 하나의 일반 Transactional 트랜잭션으로 처리하며 REQUIRES_NEW와 낙관 락 재시도를 사용하지 않는다.

1. room.contract의 채팅 접근 guard가 ROOMS 행에 짧은 공유 잠금을 얻고 현재 주최자·ACTIVE 참가 관계와 RECRUITING·CLOSED 상태를 검증한다.
2. 같은 잠금 순서에서 CHAT_ROOMS 행에 쓰기 잠금을 얻는다. 메시지 ID 할당 전에 방별 append를 직렬화해 같은 방의 messageId 순서와 커밋 가시성 순서를 맞춘다.
3. senderUserId와 clientMessageId로 기존 메시지를 확인한다. 같은 키와 같은 본문이면 최초 결과를 반환하고, 다른 본문이면 검증 오류로 거절한다.
4. CHAT_MESSAGES를 저장하고 MessageCommitted 애플리케이션 이벤트를 등록한다.
5. DB 커밋이 성공하면 TransactionalEventListener의 AFTER_COMMIT, fallbackExecution=false 경계에서 인메모리 WebSocket publisher를 호출한다.

잠금 순서는 ROOMS 다음 CHAT_ROOMS로 고정한다. 메시지마다 Room.version을 증가시키지 않는다. 참가 취소·방 상태 변경은 ROOMS를 갱신하므로 메시지 전송의 공유 잠금과 충돌해 둘 중 먼저 얻은 트랜잭션이 커밋된 뒤 다른 요청이 최신 권한·상태를 다시 확인한다.

WebSocket 전달 실패는 저장 결과를 바꾸지 않는다. 예외를 경계 안에서 기록하고 전달 실패 메트릭을 증가시키며, 클라이언트는 이력 조회와 afterMessageId 재연결로 복구한다.

## 결과

- 얻는 것: 롤백된 메시지는 전달되지 않고, 실시간 장애가 저장된 이력을 지우지 않는다. 새 AWS 서비스 없이 현재 EC2·RDS 기준선에서 시작할 수 있다.
- 감수할 비용·위험: DB 커밋과 인메모리 발행 사이에 프로세스가 종료되면 연결 중 사용자가 이벤트를 즉시 받지 못할 수 있다. 방별 메시지 쓰기는 짧게 직렬화된다.
- 후속 작업: 잠금 순서와 권한 경쟁 PostgreSQL 테스트, AFTER_COMMIT 전달·롤백·발행 실패 테스트, 지연·실패 메트릭을 구현한다.

## 보류 및 재검토

- 지금 하지 않는 것: Transactional Outbox, Redis Pub/Sub, RabbitMQ, Kafka, 메시지 전송용 REQUIRES_NEW
- 보류 이유: P1은 단일 인스턴스이고 PostgreSQL cursor 복구가 있으며, 별도 전달 보장 요구와 부하 측정이 없다.
- 다시 검토할 조건:
    - ASG 또는 수동 증설로 애플리케이션 인스턴스를 둘 이상 운영할 때 Redis나 공용 broker를 결정한다.
    - 재접속 없이도 모든 실시간 이벤트의 자동 재전송을 보장해야 하거나 커밋 후 발행 누락이 허용 범위를 넘을 때 Outbox를 결정한다.
    - 방별 append 잠금 대기시간이 실제 사용자 지연의 병목으로 측정될 때 순서·커서 전략을 함께 재검토한다.

## 참고 자료

- [Spring 트랜잭션 바운드 이벤트](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)
- [ADR-0021 P0 AWS 배포 기준선](../platform/0021-p0-aws-ec2-rds-deployment-baseline.md)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 채팅 저장·잠금·AFTER_COMMIT publisher 구현과 PostgreSQL 경쟁 테스트가 없다.
    - WebSocket 전달 실패·프로세스 재시작 복구와 운영 부하를 확인하지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
