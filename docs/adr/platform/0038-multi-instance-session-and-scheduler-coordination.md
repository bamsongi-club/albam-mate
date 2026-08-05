# ADR-0038: 다중 인스턴스의 공용 세션과 스케줄 실행을 공유 인프라로 조정

- 상태: 승인됨
- 작성일: 2026-08-01
- 결정일: 2026-08-01
- 관련: [P1 실행 환경과 공용 인프라](../../P1-spec.md#실행-환경과-공용-인프라), [아키텍처의 다중 인스턴스 실행](../../ARCHITECTURE.md#다중-인스턴스-실행), [ERD의 SHEDLOCK](../../ERD.md#shedlock), [ADR-0003 서버 세션](../auth/0003-p0-server-session-spring-security.md), [ADR-0021 AWS 배포 기준선](0021-p0-aws-ec2-rds-deployment-baseline.md), [ADR-0030 알림 relay](../notification/0030-postgresql-notification-relay-processing-recovery.md), [ADR-0032 채팅 실시간 전달](../chat/0032-http-send-websocket-receive.md), [ADR-0034 채팅 보관·삭제](../chat/0034-chat-message-retention-and-deletion.md), [ADR-0036 ROOM 상태 자동 전환](../room/0036-bounded-room-state-transition-processing.md), [GitHub Issue #244](https://github.com/bamsongi-club/albam-mate/issues/244)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

P1은 채팅을 별도 서비스로 분리하지 않고 현재 Spring Boot 모듈러 모놀리스를 여러 인스턴스로 실행한다. HTTP 요청과 WebSocket 연결이 서로 다른 인스턴스에 도달할 수 있으므로 인스턴스 로컬 세션만으로는 인증 상태를 이어갈 수 없다. 모든 인스턴스에 등록된 ROOM 상태 보정과 채팅 만료 삭제 스케줄도 조정 없이 실행하면 같은 대상을 중복 조회·처리한다.

세션 공유와 스케줄 실행 조정은 채팅이나 ROOM 한 도메인만의 규칙이 아니다. 이 공통 실행 전제를 채팅·ROOM ADR에 각각 기록하면 구현자가 여러 ADR을 함께 읽어야 하고 같은 Redis·ShedLock 운영 규칙을 중복 유지하게 된다. 이 ADR은 공통 실행 구조와 기술 선택만 소유하며, 메시지 전달·보관과 ROOM 선별·상태 전이 같은 업무 규칙은 각 도메인 ADR이 소유한다.

판단 기준은 다음과 같다.

- 채팅을 별도 서비스로 분리하지 않고 현재 모듈러 모놀리스를 유지할 것
- HTTP·WebSocket이 다른 인스턴스에 도달해도 같은 로그인 상태를 확인할 것
- ALB stickiness를 인증 정합성의 필수 조건으로 만들지 않을 것
- ROOM 상태 보정과 채팅 만료 삭제는 인스턴스 수와 무관하게 같은 실행 주기에 한 주체만 작업을 소유할 것
- 병렬 worker가 서로 다른 행을 선점하는 작업까지 단일 실행으로 제한하지 않을 것
- 스케줄 잠금이 ROOM·참가·채팅의 업무 정합성 수단을 대체하지 않을 것
- 동적 Trigger·Misfire·영속 Job 복구 요구가 없는 P1에 과도한 운영 구성을 추가하지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| ALB stickiness와 인스턴스 로컬 세션 | Redis 없이 기존 메모리 세션을 유지할 수 있다. | 인스턴스 교체·장애·재연결 시 인증 상태를 잃고 stickiness가 정합성 전제가 된다. | 제외 |
| Spring Session Redis로 세션 공유 | HTTP와 WebSocket이 다른 인스턴스에 도달해도 같은 세션을 확인한다. | Redis 가용성·보안·직렬화·TTL 운영이 추가된다. | 선택 |
| ROOM 상태 보정·채팅 만료 삭제를 조정 없이 멱등 실행 | 잠금 저장소와 의존성이 없다. | 같은 후보를 인스턴스 수만큼 조회해 DB 비용·충돌과 중복 장애 신호가 증가한다. | 제외 |
| Redis 분산 락으로 스케줄 실행 조정 | 이미 사용하는 Redis에서 잠금을 관리할 수 있다. | 세션 장애와 스케줄 조정 장애가 결합되고 PostgreSQL 업무 정합성 락으로 오해될 수 있다. | 제외 |
| Spring Scheduler와 PostgreSQL 기반 ShedLock | 기존 스케줄 코드를 유지하고 공용 DB 시각으로 한 실행만 조정한다. | 잠금 테이블·임대 시간·실행시간 관측을 운영해야 한다. | 선택 |
| Quartz 클러스터 또는 전용 스케줄러 서비스 | 영속 Job·Trigger, Misfire와 복구 기능을 확장하기 쉽다. | 현재 고정 주기 작업보다 테이블·배포·운영 범위가 크다. | 제외 |

## 결정

P1은 하나의 Spring Boot 모듈러 모놀리스를 다음 세 실행 프로필로 운영한다.

- `local-single`: 빠른 단일 서버 개발용이다. 인메모리 세션·채팅 fan-out을 허용하지만 다중 인스턴스 검증 근거로 인정하지 않는다.
- `local-multi`: 로컬 프록시 뒤 애플리케이션 두 대와 공용 PostgreSQL·Redis로 구성하고 P1 필수 교차 인스턴스 검증 환경으로 사용한다.
- `prod`: ALB가 ASG 애플리케이션 인스턴스로 요청을 분산하고 모든 인스턴스가 공용 RDS PostgreSQL·Redis를 사용한다. 실제 AWS scale-out·WebSocket Upgrade·연결 draining 검증은 후속 OPS로 분리한다.

`local-multi`와 `prod`의 `JSESSIONID` 인증 상태는 Spring Session Redis로 공유한다. ALB stickiness는 연결 분산 최적화에 사용할 수 있지만 인증 정합성과 재연결 성공의 전제는 아니다. 하나의 공용 Redis를 Spring Session, 채팅 Pub/Sub과 사용자·방 단위 전송 제한에 사용하되 key prefix, TTL과 channel namespace를 논리적으로 분리한다.

`local-multi`와 `prod`는 Redis가 필요한 세션·전송 제한 경로를 인메모리 구현으로 자동 대체하지 않는다. 세션 또는 전송 제한 상태를 확인할 수 없으면 API 정본의 `503 SERVICE_UNAVAILABLE`로 실패시킨다. 이 ADR은 fallback 금지와 실패 방향만 소유하고, 어떤 엔드포인트가 이 코드를 반환하는지는 [API 정본](../../API.md#101-공통-오류)이 소유한다. 현재 API 정본에 반영된 적용 범위는 채팅 API이며, 로그인·로그아웃과 그 밖의 세션 사용 엔드포인트로의 확장은 적용 엔드포인트를 명시한 별도 계약 변경으로 승인한다. PostgreSQL 커밋 뒤 채팅 Pub/Sub만 실패한 경우의 저장 결과와 복구 방식은 [ADR-0033](../chat/0033-postgresql-source-after-commit-delivery.md)이 소유한다.

ROOM 상태 보정과 채팅 만료 삭제는 모든 인스턴스에 Spring Scheduler를 등록하고 PostgreSQL 기반 ShedLock으로 한 실행 주기의 소유자를 하나로 제한한다.

- 두 단일 실행 대상 스케줄은 모든 인스턴스가 등록한다. 잠금을 얻은 인스턴스만 작업을 시작하고 얻지 못한 인스턴스는 기다리지 않고 해당 실행을 건너뛴다.
- 잠금 시각은 애플리케이션 시계가 아니라 PostgreSQL 시각을 사용한다.
- 잠금 획득·해제 트랜잭션은 도메인의 ROOM별 처리·채팅 삭제 묶음 트랜잭션과 결합하지 않는다.
- 잠금은 중복 실행을 줄이는 운영 조정 장치다. 임대 만료나 프로세스 종료로 실행이 겹쳐도 작업 본문은 최신 조건을 다시 확인하고 같은 결과로 수렴해야 한다.
- ShedLock은 `Room.version` 낙관 락, 참가·대기 불변식과 채팅 저장 정합성을 대체하지 않는다. 다중 인스턴스라는 이유만으로 ROOM 업무 락을 Redis 분산 락으로 바꾸지 않는다.
- 알림 relay는 이 단일 실행 대상에서 제외한다. [ADR-0030](../notification/0030-postgresql-notification-relay-processing-recovery.md)에 따라 모든 인스턴스의 worker가 실행되고 `FOR UPDATE SKIP LOCKED`로 서로 다른 이벤트를 나눠 처리한다.
- 정확한 잠금 이름, `lockAtMostFor`와 실행시간 경고 기준은 각 구현 이슈에서 측정 근거와 함께 확정한다.

세션 TTL·직렬화 방식과 정확한 key·channel namespace도 후속 구현 이슈에서 확정한다. 운영 Redis 제품, HA·TLS·접근 제어·비밀 주입·비용과 실제 ALB·ASG 검증은 후속 OPS에서 결정한다.

## 결과

- 얻는 것:
    - 인스턴스 교체와 요청 분산에 관계없이 같은 서버 세션을 확인할 수 있다.
    - 채팅과 ROOM이 공통 실행 전제를 한 ADR에서 참조하고 도메인 고유 규칙만 소유한다.
    - 기존 Spring Scheduler를 유지하면서 ROOM 보정·채팅 삭제의 중복 스캔·처리 비용을 한 실행으로 제한한다.
- 감수할 비용·위험:
    - Redis가 인증·전송 제한의 필수 운영 의존성이 되고 PostgreSQL에 ShedLock 기술 테이블이 추가된다.
    - 공용 Redis 장애의 영향 범위가 넓어 용도별 메트릭·namespace와 운영 경보가 필요하다.
    - 잠금 임대가 작업보다 먼저 만료되면 실행이 겹칠 수 있어 멱등성과 실행시간 관측이 필요하다.
- 후속 작업:
    - `local-multi` 프록시·애플리케이션 두 대·PostgreSQL·Redis 실행 구성을 구현한다.
    - Spring Session Redis와 용도별 namespace·장애 정책을 구현하고 교차 인스턴스 세션을 검증한다.
    - ShedLock 마이그레이션·공통 adapter·관측을 구현하고 각 도메인 작업의 단일 실행과 임대 만료 복구를 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: 채팅 서비스 분리, Redis 업무 분산 락, Quartz 클러스터, 전용 스케줄러 서비스
- 보류 이유: 현재 단일 실행 대상 고정 주기 작업과 공용 세션은 Spring Session Redis와 PostgreSQL ShedLock으로 충족할 수 있고, 별도 서비스·영속 Job 복구 요구가 없다.
- 다시 검토할 조건: 동적 Trigger·Misfire·영속 Job 복구가 필요하거나, 운영 측정에서 공용 Redis의 장애 격리·용량 한계가 확인되거나, 채팅을 독립 배포해야 할 부하·조직 경계가 생길 때

## 참고 자료

- [Spring Session Redis](https://docs.spring.io/spring-session/reference/configuration/redis.html)
- [ShedLock README](https://github.com/lukas-krecan/ShedLock)
- [ADR-0021 P0 AWS 배포 기준선](0021-p0-aws-ec2-rds-deployment-baseline.md)

## 검증

- 상태: 미검증
- 근거:
    - 구현: [#360](https://github.com/bamsongi-club/albam-mate/issues/360)에서 `local-multi` Spring Session Redis를, [#286](https://github.com/bamsongi-club/albam-mate/issues/286)에서 같은 세션 계약의 `production` profile과 Redis Pub/Sub을 구현했다.
    - 테스트: #286의 production 두 인스턴스 Redis 세션·채팅 fan-out PostgreSQL 검증이 같은 `JSESSIONID`와 PostgreSQL catch-up 경계를 확인한다.
- 미검증:
    - PostgreSQL ShedLock 단일 실행, 잠금 보유 인스턴스 종료와 임대 만료 복구를 확인하지 않았다.
    - 실제 AWS ALB·ASG와 운영 Redis 구성은 후속 OPS 검증이 필요하다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
