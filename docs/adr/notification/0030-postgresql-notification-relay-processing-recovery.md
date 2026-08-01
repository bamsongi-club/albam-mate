# ADR-0030: PostgreSQL polling relay의 처리와 복구 정책을 확정

- 상태: 승인됨
- 작성일: 2026-07-31
- 결정일: 2026-07-31
- 관련: [현재 알림 운영 파라미터 정본](../../guides/NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본), [P1 알림 구현 명세](../../p1/notification.md#noti-01-모임-변경-알림-생성), [P1 알림 생성과 조회](../../P1-spec.md#알림-생성과-조회), [P1 알림 저장 계약](../../ERD.md#p1-알림-저장-계약), [ADR-0002](../platform/0002-postgresql-primary-database.md), [ADR-0009](../platform/0009-utc-time-standard.md), [ADR-0029](0029-room-integration-event-transactional-outbox.md), [ADR-0039 — Notification 시각 계약 구체화](0039-notification-presentation-and-bulk-read-snapshot.md#결정)
- 대체 대상: 없음
- 후속 ADR: 없음
- 사후 보완: 2026-08-01 — [PR #242](https://github.com/bamsongi-club/albam-mate/pull/242)가 설계 검토 완료 전에 머지되어, 구현 착수 전 연속 검토에서 확정한 보존·복구·cleanup 운영 계약을 이번 한 차례 반영했다.

## 맥락

[ADR-0029](0029-room-integration-event-transactional-outbox.md)는 방 변경과 같은 트랜잭션에 원인 이벤트와 수신자 스냅샷을 남긴다. relay는 이 영속 작업을 여러 애플리케이션 인스턴스에서 중복 없이 선점하고, 수신자별 Notification을 생성한 뒤 성공·재시도·최종 실패를 복구 가능한 상태로 남겨야 한다.

현재 relay 작업은 외부 API를 호출하지 않고 같은 PostgreSQL 안에서 이벤트 한 건과 최대 10명의 수신자에 대한 짧은 INSERT·UPDATE만 수행한다. 장시간 외부 호출에 적합한 영속 lease를 지금 도입하면 만료·재선점·중복 worker와 시계 경계가 추가된다. 반대로 batch 전체를 한 트랜잭션으로 처리하면 잘못된 이벤트 한 건이 정상 이벤트까지 롤백시킨다.

이번 결정은 다음 기준을 만족해야 한다.

- 프로세스 종료 시 작업과 잠금이 자동으로 복구되고 이벤트가 유실되지 않을 것
- 여러 인스턴스가 leader election 없이 안전하게 작업을 나눌 것
- 실패 이벤트 한 건이 다른 이벤트의 처리와 커밋을 막지 않을 것
- 자동 재시도 상한, 결정적 실패, 수동 재처리와 폐기를 구분할 것
- 처리 지연과 DB 부하를 제한하고 조정·교체 조건을 측정할 수 있을 것
- 외부 채널과 broker를 현재 DB 내부 처리 트랜잭션에 섞지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| batch 전체를 한 트랜잭션에서 잠그고 처리 | 트랜잭션 수가 적고 batch 성공 시 한 번에 완료된다. | 이벤트 한 건의 오류가 batch 전체를 롤백하고 잠금·트랜잭션 시간이 길어진다. | 제외 |
| 이벤트별 짧은 트랜잭션과 `FOR UPDATE SKIP LOCKED` 사용 | PostgreSQL이 선점과 장애 시 잠금 해제를 담당하고, 여러 인스턴스가 서로 기다리지 않고 작업을 나눈다. 이벤트별 실패 격리와 원자적 Notification 생성이 가능하다. | 이벤트 수만큼 트랜잭션 비용이 들고 처리 중 worker 상태를 DB에 영속하지 않는다. 전역·방별 처리 순서를 보장하지 않는다. | 선택 |
| `PROCESSING` 상태와 `lockedUntil` lease를 먼저 커밋한 뒤 처리 | 장시간 작업과 worker 상태 관측에 적합하고 처리 중 DB 잠금을 유지하지 않는다. | 현재의 짧은 DB 작업에 만료·재선점·시계·stale worker 복구 로직을 추가한다. | 제외 |
| 하나의 leader 인스턴스에서만 relay 실행 | 유휴 polling과 동시 선점이 줄고 전역 처리 순서를 만들기 쉽다. | leader election과 장애 전환 기반이 필요하고 단일 worker 장애가 전체 처리를 멈춘다. | 제외 |
| 외부 broker consumer로 처리 | 독립 consumer와 높은 처리량을 지원할 수 있다. | 현재 요구에 broker 운영과 DB-broker 이중 쓰기 경계를 추가하며, 선택을 뒷받침하는 부하 측정이 없다. | 제외 |

실패 이벤트의 진행과 순서 정책도 같은 relay 수명주기의 일부로 비교했다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 모든 오류를 무기한 자동 재시도 | 일시 장애가 오래 지속돼도 운영자 개입 없이 언젠가 복구될 수 있다. | 결정적 오류가 DB 부하와 로그를 계속 만들고 사람의 개입이 필요한 시점을 알 수 없다. | 제외 |
| 제한된 자동 재시도 뒤 `FAILED` 격리 | 일시 오류에는 자동 복구 기회를 주고 결정적·반복 오류는 보존된 상태로 격리해 명시적으로 복구할 수 있다. | 재처리·폐기 interface와 감사 정보가 필요하며 운영자가 해결하지 않으면 알림이 지연된다. | 선택 |
| 같은 방의 이벤트를 엄격한 순서로 처리 | 참가 뒤 취소처럼 원인 순서대로 알림을 생성할 수 있다. | 앞선 poison event가 뒤의 방 취소 등 더 중요한 알림까지 막을 수 있다. | 제외 |
| 이벤트를 독립 처리하고 원인 이벤트 시각으로 표시 | 실패 이벤트가 다른 이벤트의 진행을 막지 않고 relay 처리 순서와 사용자 표시 순서를 분리한다. | 실패 이벤트가 나중에 복구되면 기존 목록의 과거 위치에 알림이 추가될 수 있다. | 선택 |

## 결정

### 선점과 처리 단위

모든 애플리케이션 인스턴스가 relay worker 하나를 실행한다. 별도 leader election은 두지 않는다. 각 worker는 5초마다 처리 가능한 이벤트를 확인하고 한 실행에서 최대 50건까지만 처리한다. 처리할 이벤트가 없으면 종료하며 무제한 drain loop는 사용하지 않는다.

이벤트 한 건은 Spring Proxy를 거친 독립 트랜잭션에서 처리한다. 트랜잭션은 처리 가능한 가장 앞선 이벤트를 `FOR UPDATE SKIP LOCKED`로 선점하고, 수신자별 Notification을 멱등 저장한 뒤 이벤트를 `PROCESSED`로 바꾸어 함께 커밋한다. 외부 네트워크 호출은 이 트랜잭션 안에서 실행하지 않는다.

여러 인스턴스는 잠긴 이벤트를 기다리지 않고 다음 이벤트를 처리한다. Notification 저장은 `(sourceEventId, recipientUserId)` 유일 제약을 정본으로 사용하며, 이미 존재하는 수신자는 성공으로 수렴시키고 아직 없는 수신자는 채운다.

처리 트랜잭션이 실패하면 Notification 변경과 완료 표시는 함께 롤백한다. 별도 독립 트랜잭션이 실패 상태를 조건부로 기록한다. 롤백과 실패 기록 사이에 프로세스가 종료되면 해당 실패 횟수는 기록되지 않을 수 있지만 이벤트는 처리 가능 상태로 남아 다시 선택된다. 다른 worker가 먼저 성공해 `PROCESSED`로 바꾼 경우 뒤늦은 실패 기록은 완료 상태를 덮어쓰지 않는다.

### 상태와 재시도

처리 상태는 `PENDING`, `RETRY_WAIT`, `PROCESSED`, `FAILED`, `DISCARDED`를 사용한다. 비관적 행 잠금 자체가 처리 중 소유권이므로 `PROCESSING`과 lease 만료 시각은 저장하지 않는다.

최초 Outbox의 `availableAt`은 같은 원인 트랜잭션이 PostgreSQL에서 고정한 `recordedAt`과 같은 `operationTime`이다. relay 선점은 SQL의 `MATERIALIZED operation` CTE에서 PostgreSQL `clock_timestamp()`를 한 번 평가한 `operationTime`에 `availableAt <= operationTime`인 이벤트만 대상으로 한다. 최초 처리까지 포함한 한 자동 처리 주기는 최대 5회다. 실패 기록 트랜잭션도 같은 함수를 한 번 평가하고 기록된 실패 횟수에 따라 `availableAt = operationTime + 재시도 간격`으로 계산한다.

| 실패 횟수 | 다음 상태와 처리 가능 시각 |
| ---: | --- |
| 1 | `RETRY_WAIT`, 10초 뒤 |
| 2 | `RETRY_WAIT`, 30초 뒤 |
| 3 | `RETRY_WAIT`, 2분 뒤 |
| 4 | `RETRY_WAIT`, 10분 뒤 |
| 5 | `FAILED` |

deadlock·lock timeout 같은 일시적 DB 오류와 분류되지 않은 런타임 오류는 위 상한까지 재시도한다. 지원하지 않는 이벤트 타입, 필수 수신자 스냅샷 누락, 복구 불가능한 데이터 제약 위반처럼 같은 입력으로 다시 실행해도 성공할 수 없는 결정적 오류는 즉시 `FAILED`로 전환한다. relay `operationTime >= occurredAt + NOTIFICATION_RETENTION`인 이벤트도 `NOTIFICATION_EXPIRED` 결정적 실패로 전환하며 Notification을 만들거나 `PROCESSED`로 바꾸지 않는다. 이벤트를 선점하기 전의 DB 연결 실패나 scheduler 실행 실패는 개별 이벤트의 실패 횟수에 포함하지 않는다.

DB에는 구조화된 실패 코드, 실패 시각, 예외 분류와 길이가 제한된 정제 설명만 저장한다. 원본 SQL·파라미터·수신자 목록·이벤트 payload·인증 정보와 stack trace는 저장하지 않는다. 상세 예외는 같은 민감 정보를 제외한 운영 로그에 남긴다.

### 지연과 순서

정상 상태의 처리 지연은 원인 업무 커밋부터 Notification 행 생성 커밋까지 측정한다. 5초 polling을 기준으로 p95 30초 이내를 목표로 하며, 가장 오래된 처리 가능 이벤트가 1분을 넘는 상태는 운영 관측이 필요한 기준으로 삼는다. 이는 브라우저 화면 갱신 시간이나 Web Push 도착 시간을 뜻하지 않는다.

relay는 전역 순서와 방별 처리 순서를 보장하지 않는다. 앞선 실패 이벤트가 뒤의 중요한 알림을 막지 않게 각 이벤트를 독립 처리한다. Notification의 `createdAt`에는 Command Coordinator가 최초 시도 전에 고정한 Outbox `occurredAt`을 복사하고, relay가 Notification 행을 저장한 시각은 `recordedAt`으로 분리한다. 같은 원인 시각은 Notification ID를 보조 정렬키로 사용한다. 앞선 이벤트가 나중에 복구되면 사용자가 이미 본 목록의 과거 위치에 새 알림이 나타날 수 있음을 감수한다.

### 보존과 운영 복구

`PENDING`과 `RETRY_WAIT` 이벤트는 자동 삭제하지 않는다. `PROCESSED` 이벤트와 수신자 행은 처리 완료 후 30일 동안 보존한 뒤 정리한다. `FAILED` 이벤트는 명시적으로 재처리하거나 폐기하기 전까지 자동 삭제하지 않는다.

Notification은 원인 이벤트 시각인 `createdAt`부터 90일 동안 읽음 여부와 관계없이 보존한다. `expiresAt = createdAt + 90일`을 저장하고 목록·미확인 개수·단건·일괄 읽음은 요청 기준 시각에 `expiresAt`이 지난 행을 물리 삭제 전에도 제외한다. 정리 작업은 만료 행을 인덱스 순서의 소량 묶음으로 삭제한다.

cleanup scheduler는 이전 실행 완료 뒤 1시간에 0~5분의 jitter를 더해 다시 실행한다. 모든 인스턴스가 실행할 수 있으며, Notification과 Outbox를 각각 한 트랜잭션에서 최대 500건씩, 한 실행에서 종류별 최대 5개 batch까지 삭제한다. 각 batch는 정리 인덱스 순서로 due ID를 `FOR UPDATE SKIP LOCKED`로 선점하고 삭제한다. 500건 미만을 삭제하면 해당 종류의 이번 실행을 끝낸다.

Notification cleanup과 Outbox cleanup은 서로 독립된 batch 트랜잭션을 사용한다. 한 batch가 실패하면 해당 트랜잭션을 롤백하고 같은 실행에서 무제한 재시도하지 않으며 실패 로그를 남긴 뒤 다음 주기에서 다시 시작한다. `PENDING`, `RETRY_WAIT`, `FAILED`는 cleanup 대상이 아니고, `DISCARDED`의 수신자 행은 폐기 트랜잭션에서 즉시 삭제한다. Outbox 이벤트의 정기 삭제는 남은 수신자 행을 FK cascade로 함께 제거한다.

수동 재처리는 `FAILED`이면서 복구 트랜잭션이 PostgreSQL에서 한 번 고정한 `operationTime`이 `occurredAt + 89일`보다 앞선 이벤트만 대상으로 하며 새로운 최대 5회 주기를 부여한다. 현재 주기의 실패 횟수는 0으로 초기화하되 전체 기록 실패 횟수, 재처리 횟수, 마지막 재처리 사유와 시각은 보존한다. 한 번의 대량 재처리는 최대 50건이다. 이벤트와 수신자 스냅샷의 존재·형식을 다시 검증한 뒤 `lastReprocessedAt = availableAt = operationTime`인 `RETRY_WAIT`로 전환한다. 89일이 지난 `FAILED` 이벤트와 `NOTIFICATION_EXPIRED` 이벤트는 재처리할 수 없고 자동 삭제하지 않으며 운영자가 `DISCARDED`로만 종결한다.

운영자가 재처리하지 않기로 결정하면 이벤트를 `DISCARDED`로 전환하고 폐기 사유와 시각을 남긴 뒤 사용자 ID가 포함된 수신자 행을 제거한다. 해결된 최소 이벤트 기록은 폐기 시점부터 30일 뒤 정리한다.

현재 복구 interface는 공개 HTTP API나 직접 SQL이 아니라 `notification-ops` 전용 one-shot profile의 운영 명령으로 제공한다. 이 profile은 웹 서버와 일반 scheduler를 시작하지 않고 `NotificationOutboxRecoveryService`를 호출한 뒤 명시적인 종료 코드로 프로세스를 끝낸다. 정확한 인자·실행 순서와 결과 해석은 [알림 Outbox 운영 런북](../../guides/NOTIFICATION_OPERATIONS.md)이 소유한다.

운영 명령은 고유한 양의 이벤트 ID를 1~50개까지 명시적으로 받으며 전체 `FAILED` 같은 무제한 selector는 제공하지 않는다. 기본은 dry-run이다. 재처리와 폐기는 1~500자의 비어 있지 않은 사유와 실행자 표기를 요구하고, 폐기는 별도 확인 문자열까지 일치해야 한다. 모든 명령은 입력 ID를 오름차순으로 정규화한다. 실제 변경 명령은 복구 트랜잭션의 PostgreSQL `operationTime`을 한 번 고정한 뒤 하나의 `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`로 모든 대상을 같은 순서에 잠금·검증한다. 하나라도 없거나 중복되거나 상태·89일 조건을 만족하지 않으면 전체를 변경하지 않는다.

명령 adapter는 트랜잭션·Repository·직접 SQL을 소유하지 않는다. 재처리·폐기 규칙과 한 명령의 원자성은 `NotificationOutboxRecoveryService`가 소유한다. 재처리는 89일 안의 `FAILED`, 폐기는 `FAILED`만 허용한다. 실행자는 구조화 로그와 배포·SSM 실행 기록에 남기며 Outbox에는 사유와 마지막 조치 시각을 보존한다. 향후 비개발 운영자, 원격 운영, 검색·승인·강한 감사 요구가 생기면 관리자 API나 백오피스 adapter가 같은 서비스를 재사용할 수 있으나, 관리자 인증·인가·CSRF·감사 경계는 별도 후속 결정으로 다룬다.

### 관측과 확장 경계

현재 relay는 batch 처리 수, 성공·재시도·실패 수, 오래된 처리 가능 이벤트, `FAILED` 전환과 scheduler 실패를 구조화된 로그로 남긴다. 로그에는 `sourceEventId`와 구조화된 실패 코드만 사용하고 수신자 ID·payload·원본 SQL을 넣지 않는다. metrics 수집, 상태 조회, dashboard와 외부 경고 전송은 특정 기능 ID에 선행 배정하지 않고 추후 운영 관측 범위에서 결정한다. 현재 알림 구현에 관측 프레임워크를 미리 추가하지 않는다.

이 비관적 선점은 PostgreSQL 안에서 끝나는 서비스 내 웹 알림 생성에만 사용한다. 이메일·모바일 푸시·Web Push·SMS가 추가되면 현재 트랜잭션에서 외부 호출하지 않고 채널별 별도 전달 작업으로 분리한다. 장시간 외부 호출은 짧은 선점 뒤 영속 lease를 사용하는 방식을 포함해 별도 ADR에서 결정한다. 새 채널이 생긴다는 이유만으로 현재 앱 알림 relay를 교체하지 않는다.

다음 조건 중 하나가 측정되면 PostgreSQL relay 조정 또는 외부 broker를 후속 ADR에서 비교한다.

- 독립 consumer가 둘 이상이고 consumer별 처리 위치·재시도·보존이 필요할 때
- 일부 consumer를 별도 프로세스나 서비스로 독립 배포해야 할 때
- batch·worker 조정 뒤에도 p95 30초 또는 가장 오래된 이벤트 1분 목표를 지속적으로 위반할 때
- polling과 relay 트랜잭션이 DB 부하나 connection pool 병목의 측정된 원인일 때
- 알림 처리 장애를 업무 DB와 운영상 분리해야 할 때

조건이 충족돼도 broker를 바로 채택하지 않는다. PostgreSQL relay 개선, 채널별 Outbox, RabbitMQ와 Kafka를 같은 요구와 측정 근거로 비교한다.

## 결과

- 얻는 것:
    - PostgreSQL의 트랜잭션과 행 잠금으로 여러 인스턴스의 작업 분담·프로세스 종료 복구·수신자별 멱등 생성을 단순하게 유지한다.
    - 이벤트별 독립 트랜잭션과 bounded retry로 poison event를 격리하고 정상 이벤트의 진행을 보존한다.
    - 자동 실패, 수동 복구, 폐기와 정리 수명주기를 명시적으로 관리한다.
- 감수할 비용·위험:
    - 이벤트마다 트랜잭션을 열고 처리하는 동안 DB 연결과 행 잠금을 점유한다.
    - 처리 중 worker 상태를 영속하지 않으며 전역·방별 처리 순서를 보장하지 않는다.
    - 처리 롤백과 실패 기록 사이의 프로세스 종료는 실패 횟수 하나를 누락시킬 수 있다.
    - 인스턴스마다 유휴 polling이 발생하고 전체 주기 처리 상한은 인스턴스 수에 비례한다.
- 후속 작업:
    - ERD에 확정된 상태·실패·재시도·재처리·폐기·정리 필드와 relay·알림 조회·90일 정리 인덱스를 전진 Flyway 마이그레이션과 Entity로 구현한다.
    - PostgreSQL 동시성 테스트로 `SKIP LOCKED`, 다중 worker, 중복 처리, 프로세스 실패에 해당하는 롤백과 실패 격리를 검증한다.
    - 구현·부하 검증에서 이 ADR의 산식·표본·실행 환경 기록으로 처리 지연, 적체, DB 연결 사용과 재시도 수를 측정한다.
    - one-shot 복구 명령과 bounded cleanup을 운영 런북의 dry-run·오류·재실행 절차까지 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것:
    - 영속 `PROCESSING` 상태와 lease 복구
    - leader election과 전용 relay 서비스
    - 관리자 HTTP API·백오피스·관측 dashboard
    - 이메일·모바일 푸시·Web Push·SMS 전달
    - Kafka·RabbitMQ와 외부 broker
- 보류 이유:
    - 현재 작업은 최대 10명의 Notification을 같은 PostgreSQL에서 생성하는 짧은 트랜잭션이며, 더 복잡한 운영 기반을 요구하는 측정 근거가 없다.
- 다시 검토할 조건:
    - 결정 절의 관측·확장 조건이 충족될 때
    - 외부 호출이나 장시간 작업이 relay 안에 필요해질 때
    - 운영자가 CLI 대신 원격·상시 복구 interface를 필요로 할 때
    - 보존·감사 규제가 현재 Outbox 30일·Notification 90일 정리와 실패 이벤트 보존 정책을 바꿀 때

## 참고 자료

- [P1 알림 구현 명세](../../p1/notification.md)
- [P1 공통 명세](../../P1-spec.md)
- [ADR-0002](../platform/0002-postgresql-primary-database.md)
- [ADR-0029](0029-room-integration-event-transactional-outbox.md)
- [알림 Outbox 운영 런북](../../guides/NOTIFICATION_OPERATIONS.md)

## 검증

- 상태: 미검증
- 근거:
    - 계약:
        - P1 알림 구현 명세는 PostgreSQL polling relay의 at-least-once 처리, 수신자별 멱등성, 재시도·최종 실패와 broker 재검토 근거를 요구한다.
        - ERD는 relay 상태별 nullable·CHECK, 실패·재처리·폐기·정리 필드와 선점·조회 인덱스를 정의하고 수치는 운영 파라미터 키로 참조한다.
        - 운영 런북의 `현재 운영 파라미터 정본` 표가 이 ADR에서 채택한 relay·재시도·측정·보존·복구·cleanup 수치의 현재 값을 유일하게 소유한다. 이 결정 본문의 숫자는 결정일 당시 기준을 보존하며 후속 ADR 없이 현재 값만 바꾸는 근거가 아니다.
        - 운영 런북은 전달 지연 산식·구조화 로그, one-shot 복구 명령과 cleanup 실행·증거 기록도 정의한다.
        - ADR-0039는 이 ADR의 Notification `recordedAt` 애플리케이션 `Clock` 하위 가정을 PostgreSQL `clock_timestamp()` 기반 `operationTime`으로 구체화하고 단건·일괄 `readAt`과 같은 DB 시계로 비교하게 한다.
- 미검증:
    - `FOR UPDATE SKIP LOCKED` 선점과 이벤트별 독립 트랜잭션 구현
    - 다중 인스턴스·중복 처리·실패 격리·재시도·수동 복구와 운영 정본의 보존·정리 파라미터 PostgreSQL 테스트
    - `notification-ops` profile의 dry-run·일괄 원자성·폐기 확인·종료 코드, 겹치는 역순 ID 명령의 오름차순 잠금과 무교착 완료 검증
    - relay가 PostgreSQL `clock_timestamp()` 기반 `operationTime`을 한 번 고정해 같은 이벤트의 Notification `recordedAt`에 사용하는 검증
    - 운영 정본의 polling·최소 표본·p95·oldest age 파라미터와 DB 부하 측정

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
