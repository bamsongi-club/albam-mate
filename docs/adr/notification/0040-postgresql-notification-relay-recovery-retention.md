# ADR-0040: PostgreSQL 알림 relay·복구·보존 정책을 대체 확정

- 상태: 승인됨
- 작성일: 2026-08-01
- 결정일: 2026-08-01
- 관련: [현재 알림 운영 파라미터 정본](../../guides/NOTIFICATION_OPERATIONS.md#현재-운영-파라미터-정본), [P1 알림 구현 명세](../../p1/notification.md#noti-01-모임-변경-알림-생성), [P1 알림 생성과 조회](../../P1-spec.md#알림-생성과-조회), [P1 알림 저장 계약](../../ERD.md#p1-알림-저장-계약), [ADR-0029](0029-room-integration-event-transactional-outbox.md), [ADR-0030](0030-postgresql-notification-relay-processing-recovery.md), [ADR-0039](0039-notification-presentation-and-bulk-read-snapshot.md)
- 대체 대상: [ADR-0030](0030-postgresql-notification-relay-processing-recovery.md)
- 후속 ADR: 없음

## 맥락

[ADR-0030](0030-postgresql-notification-relay-processing-recovery.md)은 PostgreSQL polling relay의 이벤트별 짧은 트랜잭션, `FOR UPDATE SKIP LOCKED`, 제한된 자동 재시도와 수동 복구 경계를 채택했다. 이후 구현 전 계약 검토에서 다음 정책을 추가로 확정해야 했다.

- Outbox `availableAt`, relay due 판정, 실패 기록과 수동 재처리가 어떤 시계에서 현재 시각을 얻는지
- 원인 이벤트 시각부터 90일인 Notification 보존과 늦은 `FAILED` 복구의 관계
- 여러 인스턴스가 실행하는 bounded cleanup의 트랜잭션·선점·실패 경계
- 운영 명령의 dry-run, 입력 상한, 잠금 순서와 전체 원자성

이 항목은 단순한 구현·검증 근거가 아니라 ADR-0030의 재처리 적격 조건, 시각 소유권과 보존 결정을 바꾼다. 승인된 ADR의 결정 본문을 소급 수정하지 않는 규칙에 따라 ADR-0030을 원문으로 복원하고, 이 ADR이 아직 유효한 결정을 포함한 현행 relay·복구·보존 정책 전체를 대체한다.

[ADR-0039](0039-notification-presentation-and-bulk-read-snapshot.md)은 알림 표시 투영과 목록·미확인·읽음 만료 판정 시각을 소유한다. 이 ADR은 relay, 실패 복구와 물리 cleanup의 시각·트랜잭션을 소유하며 ADR-0039를 대체하지 않는다.

이번 결정은 다음 기준을 만족해야 한다.

- 여러 인스턴스가 leader election 없이 이벤트와 cleanup 작업을 안전하게 나눌 것
- 이벤트 한 건의 실패가 다른 이벤트의 커밋과 복구를 막지 않을 것
- due·만료·보존 판정에서 애플리케이션과 PostgreSQL 시계 오차가 지속적인 불일치나 조기 삭제를 만들지 않을 것
- 만료 직전 복구가 사용자에게 사실상 보이지 않는 Notification을 생성하지 않도록 여유를 둘 것
- 자동 처리, 운영자 조치와 물리 정리를 서로 다른 진입점·트랜잭션으로 제한할 것
- 현재 운영 수치와 구현·운영 증거의 정본을 분리하되 결정 변경은 후속 ADR로 추적할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| ADR-0030 본문에 새 정책을 사후 보완으로 추가 | 문서 수가 늘지 않고 한 파일에서 모든 내용을 읽을 수 있다. | 승인된 결정의 역사 기록을 바꾸고 어떤 내용이 원래 결정인지 구분할 수 없다. | 제외 |
| ADR-0030을 유지하고 좁은 보충 ADR만 추가 | 기존 relay 결정을 반복하지 않아도 된다. | 모든 `FAILED`를 재처리할 수 있다는 기존 결정과 89일 제한이 충돌하고 현재 정책을 읽으려면 두 결정의 우선순위를 추론해야 한다. | 제외 |
| ADR-0030을 대체하고 유효한 기존 결정과 새 정책을 후속 ADR에 함께 기록 | 승인 당시 원문을 보존하면서 현행 정책과 우선순위를 한 문서에서 확인할 수 있다. | 기존 결정 일부가 반복되고 관련 문서 링크를 새 ADR로 갱신해야 한다. | 선택 |

cleanup 만료 시각의 출처도 비교했다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| Scheduler가 애플리케이션 `Clock`으로 시각을 고정해 Executor에 전달 | 기존 Scheduler 시간 주입 방식과 단위 테스트를 재사용하기 쉽다. | 앱 시계가 DB보다 앞서면 ADR-0039 기준으로 아직 조회·읽음 가능한 Notification을 물리 삭제할 수 있다. | 제외 |
| 각 cleanup batch 트랜잭션에서 PostgreSQL `clock_timestamp()`를 한 번 조회 | 사용자 조회·읽음과 같은 DB 시계 도메인에서 만료를 판정하고 선점·삭제·로그에 같은 값을 재사용한다. | batch마다 시각 조회 SQL 한 번이 추가되고 PostgreSQL 통합 테스트가 필요하다. | 선택 |

늦은 실패 복구와 Notification 만료도 함께 비교했다.

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 만료 시점까지 `FAILED` 재처리를 허용 | 운영자가 복구할 수 있는 시간이 가장 길다. | 만료 직전 생성되어 polling·relay 지연 뒤 사용자가 거의 볼 수 없는 Notification을 만들 수 있다. | 제외 |
| 늦게 복구한 Notification의 만료를 relay 처리 시각부터 다시 계산 | 복구 알림에 항상 충분한 노출 기간을 준다. | 같은 원인 이벤트의 보존 기간이 복구 시점에 따라 달라지고 90일 보존 상한이 깨진다. | 제외 |
| 원인 시각 기반 90일 만료를 유지하고 수동 재처리는 89일 미만으로 제한 | 보존 기준을 유지하면서 relay 재시도와 사용자 노출을 위한 하루의 여유를 둔다. | 89일이 지난 `FAILED`는 복구하지 못하고 명시적으로 폐기해야 한다. | 선택 |

## 결정

이 ADR은 ADR-0030을 전부 대체한다. 아래에 다시 적지 않은 ADR-0030의 문장은 현행 정책으로 추론하지 않으며, 아래 결정과 연결된 운영 정본·ERD·ADR-0039를 함께 적용한다.

### 선점과 처리 단위

모든 애플리케이션 인스턴스가 relay worker 하나를 실행한다. 별도 leader election은 두지 않는다. 각 worker는 `RELAY_POLL_INTERVAL`마다 처리 가능한 이벤트를 확인하고 한 실행에서 `RELAY_MAX_EVENTS_PER_RUN`까지만 처리한다. 처리할 이벤트가 없으면 종료하며 무제한 drain loop는 사용하지 않는다. 결정 시점 값은 각각 5초와 50건이다.

이벤트 한 건은 Spring Proxy를 거친 독립 트랜잭션에서 처리한다. 트랜잭션은 가장 앞선 처리 가능 이벤트를 `FOR UPDATE SKIP LOCKED`로 선점하고, 수신자별 Notification을 멱등 저장한 뒤 이벤트를 `PROCESSED`로 바꾸어 함께 커밋한다. `(sourceEventId, recipientUserId)` 유일 제약으로 이미 존재하는 수신자는 성공으로 수렴시키고 누락된 수신자만 채운다. 외부 네트워크 호출은 이 트랜잭션 안에서 실행하지 않는다.

처리 트랜잭션이 실패하면 Notification 변경과 완료 표시는 함께 롤백한다. 별도 독립 트랜잭션이 실패 상태를 조건부 기록하며, 다른 worker가 먼저 `PROCESSED`로 끝낸 이벤트를 뒤늦은 실패 기록이 덮어쓰지 않는다. 롤백과 실패 기록 사이에 프로세스가 종료되면 실패 횟수 하나가 누락될 수 있지만 이벤트는 다시 처리 가능한 상태로 남는다.

### 시각, 상태와 재시도

처리 상태는 `PENDING`, `RETRY_WAIT`, `PROCESSED`, `FAILED`, `DISCARDED`를 사용한다. 행 잠금이 처리 중 소유권이므로 `PROCESSING`과 lease 만료 시각은 저장하지 않는다.

최초 Outbox의 `availableAt`은 원인 업무 트랜잭션이 PostgreSQL `clock_timestamp()`로 한 번 고정한 `recordedAt`과 같은 `operationTime`이다. relay 선점 SQL은 `MATERIALIZED operation` CTE에서 같은 함수를 한 번 평가하고 `availableAt <= operationTime`인 이벤트만 대상으로 한다. 실패 기록 트랜잭션과 수동 재처리 트랜잭션도 각각 PostgreSQL 시각을 한 번 고정해 `availableAt`과 관련 작업 시각에 사용한다.

최초 처리를 포함한 자동 처리 주기는 최대 5회다. 결정 시점 재시도 간격은 다음과 같다.

| 실패 횟수 | 다음 상태와 처리 가능 시각 |
| ---: | --- |
| 1 | `RETRY_WAIT`, 10초 뒤 |
| 2 | `RETRY_WAIT`, 30초 뒤 |
| 3 | `RETRY_WAIT`, 2분 뒤 |
| 4 | `RETRY_WAIT`, 10분 뒤 |
| 5 | `FAILED` |

deadlock·lock timeout 같은 일시적 DB 오류와 분류되지 않은 런타임 오류는 위 상한까지 재시도한다. 지원하지 않는 이벤트 타입, 필수 수신자 스냅샷 누락과 복구 불가능한 데이터 제약 위반은 즉시 `FAILED`로 전환한다. relay `operationTime >= occurredAt + NOTIFICATION_RETENTION`인 이벤트도 `NOTIFICATION_EXPIRED` 결정적 실패로 전환하고 Notification이나 `PROCESSED`를 만들지 않는다. 이벤트 선점 전 DB 연결 실패와 scheduler 실행 실패는 개별 이벤트 실패 횟수에 포함하지 않는다.

DB에는 구조화된 실패 코드, 실패 시각, 예외 분류와 길이가 제한된 정제 설명만 저장한다. 원본 SQL·파라미터·수신자 목록·payload·인증 정보와 stack trace는 저장하지 않는다.

### 지연과 순서

relay는 전역 순서와 방별 처리 순서를 보장하지 않는다. 앞선 실패 이벤트가 뒤 이벤트를 막지 않게 독립 처리하고, 늦게 복구된 이벤트의 Notification은 원인 시각 기준의 과거 목록 위치에 나타날 수 있음을 수용한다.

Notification `createdAt`은 Outbox `occurredAt`을 복사한 원인 업무 시각이고, Outbox·Notification `recordedAt`은 각 저장 트랜잭션의 PostgreSQL 작업 시각이다. 시각 출처와 저장 제약의 상세는 ADR-0039를 따른다. 수신자별 relay 전달 지연은 `Notification.recordedAt - Outbox.recordedAt`으로 측정한다.

정상 상태의 p95 전달 지연 목표와 가장 오래된 처리 가능 이벤트 목표는 운영 파라미터 정본이 소유한다. 결정 시점 값은 각각 30초 이하와 60초 이하다.

### 보존과 운영 복구

`PENDING`과 `RETRY_WAIT` 이벤트는 자동 삭제하지 않는다. `PROCESSED` 이벤트와 남은 수신자 행은 `PROCESSED_OUTBOX_RETENTION` 뒤 정리하고, `FAILED`는 재처리하거나 폐기하기 전까지 자동 삭제하지 않는다. 결정 시점 처리 완료 보존 기간은 30일이다.

Notification은 원인 시각인 `createdAt`부터 `NOTIFICATION_RETENTION` 동안 읽음 여부와 관계없이 보존한다. `expiresAt = createdAt + NOTIFICATION_RETENTION`을 저장하고, 물리 삭제 전의 조회·읽음 만료 판정은 ADR-0039의 PostgreSQL 시각 계약을 따른다. 결정 시점 보존 기간은 90일이다.

수동 재처리는 `FAILED`이면서 복구 트랜잭션의 PostgreSQL `operationTime < occurredAt + FAILED_REPROCESS_WINDOW`인 이벤트만 대상으로 한다. 결정 시점 창은 89일 미만이다. 적격 이벤트에는 새로운 최대 5회 주기를 부여하고 현재 주기 실패 횟수를 0으로 초기화하되 누적 실패 횟수, 재처리 횟수, 마지막 사유와 시각을 보존한다. 수신자 스냅샷을 재검증한 뒤 `lastReprocessedAt = availableAt = operationTime`인 `RETRY_WAIT`로 전환한다. 창이 지났거나 `NOTIFICATION_EXPIRED`인 이벤트는 재처리하지 않고 운영자가 `DISCARDED`로만 종결한다.

복구 interface는 공개 HTTP API나 직접 SQL이 아니라 `notification-ops` 전용 one-shot profile이다. 웹 서버와 일반 scheduler를 시작하지 않고 application service를 호출한 뒤 명시적인 종료 코드로 끝낸다. 명령은 고유한 양의 ID를 `OPS_MAX_EVENT_IDS` 이하로 받고 기본 dry-run을 적용한다. 실제 변경은 비어 있지 않은 사유와 실행자 표기를 요구하며 폐기는 별도 확인 문자열까지 일치해야 한다.

입력 ID는 오름차순으로 정규화하고 하나의 `ORDER BY id FOR UPDATE` 조회로 모두 잠근다. 하나라도 없거나 중복되거나 상태·기간 조건을 만족하지 않으면 전체를 변경하지 않는다. adapter는 트랜잭션·Repository·직접 SQL을 소유하지 않고 `NotificationOutboxRecoveryService`에 위임한다. 실행자 입력은 자기신고 로그 값이며, 권위 있는 실제 주체는 배포·SSM 실행 기록에서 확인한다.

`DISCARDED` 전환은 사유와 시각을 남기고 수신자 스냅샷을 같은 트랜잭션에서 제거한다. 최소 이벤트 기록은 `DISCARDED_OUTBOX_RETENTION` 뒤 정리한다.

### bounded cleanup

cleanup scheduler는 이전 실행 완료 뒤 `CLEANUP_INTERVAL`에 `CLEANUP_JITTER`를 더해 다시 실행한다. 모든 인스턴스가 실행할 수 있다. Notification과 Outbox는 서로 독립적으로, 한 batch에서 `CLEANUP_BATCH_SIZE` 이하, 한 실행에서 종류별 `CLEANUP_MAX_BATCHES_PER_TARGET` 이하만 처리한다. 결정 시점 값은 1시간, 0~5분, 500건, 5개 batch다.

Scheduler와 Coordinator는 실행 주기와 반복 상한만 조정하며 만료 판정 시각을 만들지 않는다. 각 cleanup Executor는 batch 트랜잭션 안에서 `SELECT clock_timestamp()`를 정확히 한 번 실행해 PostgreSQL `measurementTime`을 고정한다. 정리 인덱스의 due ID 선점 조건, 같은 트랜잭션의 삭제 조건과 완료·실패 로그는 이 값을 재사용한다. 따라서 애플리케이션 `Clock`이 DB보다 앞서도 아직 조회·읽음 가능한 Notification을 먼저 삭제하지 않는다.

각 batch는 due ID를 정리 인덱스 순서로 `FOR UPDATE SKIP LOCKED` 선점하고 같은 트랜잭션에서 삭제한다. `CLEANUP_BATCH_SIZE`보다 적게 삭제하면 해당 종류의 이번 실행을 끝낸다. 실패하면 그 batch만 롤백하고 같은 실행에서 무제한 재시도하지 않으며 다음 주기에 다시 시작한다.

Notification은 `expiresAt <= measurementTime`, `PROCESSED`·`DISCARDED` Outbox는 `cleanupAt <= measurementTime`일 때만 삭제한다. `PENDING`, `RETRY_WAIT`, `FAILED`는 cleanup 대상이 아니다. `DISCARDED` 수신자 행은 폐기 트랜잭션에서 즉시 삭제하고, 정기 Outbox 삭제는 남은 수신자 행을 FK cascade로 제거한다.

### 관측과 확장 경계

relay와 cleanup은 처리 수·지연·실패와 PostgreSQL에서 고정한 기준 시각을 구조화 로그로 남긴다. 로그에는 이벤트 ID와 구조화된 실패 코드만 사용하고 수신자 ID·payload·원본 SQL·인증 정보를 넣지 않는다. metrics 수집, dashboard와 외부 경고는 측정 요구가 확정될 때 별도 범위로 결정한다.

현재 비관적 선점은 같은 PostgreSQL 안에서 끝나는 서비스 내 웹 알림에만 사용한다. 외부 채널이나 장시간 작업이 추가되면 영속 lease와 채널별 전달 작업을 후속 ADR에서 비교한다. 독립 consumer 증가, 별도 배포 요구, 조정 뒤에도 지연 목표 위반, 측정된 DB 병목이나 장애 격리 요구가 생기면 PostgreSQL relay 개선과 외부 broker를 같은 근거로 재검토한다.

## 결과

- 얻는 것:
    - ADR-0030의 승인 당시 원문을 보존하면서 현행 relay·복구·보존 정책을 하나의 대체 ADR에서 읽을 수 있다.
    - PostgreSQL 행 잠금과 이벤트별 트랜잭션으로 다중 인스턴스 처리, 실패 격리와 수신자별 멱등성을 유지한다.
    - relay·재처리·cleanup의 due 판정을 PostgreSQL 시계로 고정해 앱·DB 시계 오차에 따른 지연·조기 삭제를 막는다.
    - 89일 재처리 창과 90일 만료를 분리해 늦은 복구가 사용자 노출 없이 `PROCESSED`로 끝나는 위험을 줄인다.
- 감수할 비용·위험:
    - 이벤트별 트랜잭션과 cleanup batch별 DB 시각 조회가 connection과 SQL 비용을 사용한다.
    - 처리 중 worker 상태를 영속하지 않고 전역·방별 순서를 보장하지 않는다.
    - 89일이 지난 실패 이벤트는 데이터가 복구돼도 재처리할 수 없다.
    - 모든 인스턴스에서 polling과 cleanup scheduler가 실행되므로 유휴 확인이 발생한다.
- 후속 작업:
    - 전진 Flyway 마이그레이션과 Entity로 상태·시각·보존·복구·정리 필드와 인덱스를 구현한다.
    - 실제 PostgreSQL에서 relay·복구·cleanup 다중 인스턴스 선점, 실패 격리, 역순 ID 잠금과 만료 경계를 검증한다.
    - 애플리케이션 Clock이 PostgreSQL보다 앞서거나 뒤진 환경에서 relay due와 cleanup 삭제가 DB 시각에 수렴하는 회귀 테스트를 추가한다.
    - one-shot 복구 명령의 dry-run·전체 원자성·폐기 확인·종료 코드와 운영 증거 양식을 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: 영속 `PROCESSING` lease, leader election, 관리자 HTTP API·백오피스, 관측 dashboard, 외부 broker와 외부 알림 채널
- 보류 이유: 현재 작업은 최대 10명의 Notification을 같은 PostgreSQL에서 생성·정리하는 짧은 트랜잭션이며 더 복잡한 기반을 요구하는 측정 근거가 없다.
- 다시 검토할 조건: 외부 호출이나 장시간 작업이 relay에 들어오거나, 운영자가 원격·상시 복구 interface를 요구하거나, 보존·감사 규제가 현재 정책을 바꾸거나, 관측된 지연·DB 부하가 운영 목표를 지속해서 위반할 때

## 참고 자료

- [P1 알림 구현 명세](../../p1/notification.md)
- [P1 공통 명세](../../P1-spec.md)
- [ADR-0029](0029-room-integration-event-transactional-outbox.md)
- [ADR-0030 — 대체된 이전 결정](0030-postgresql-notification-relay-processing-recovery.md)
- [ADR-0039](0039-notification-presentation-and-bulk-read-snapshot.md)
- [알림 Outbox 운영 런북](../../guides/NOTIFICATION_OPERATIONS.md)

## 검증

- 상태: 미검증
- 근거:
    - 계약:
        - P1 알림 명세와 ERD가 Outbox 상태·수신자 멱등성·Notification 만료·복구·cleanup 필드와 PostgreSQL 시각 경계를 정의한다.
        - 운영 런북의 `현재 운영 파라미터 정본`이 이 결정에 연결된 현재 수치를 소유하고 one-shot 복구·cleanup 실행 절차와 증거 형식을 정의한다.
        - 아키텍처 문서가 relay·recovery·cleanup의 패키지, 트랜잭션과 시각 소유 경계를 연결한다.
- 미검증:
    - relay·실패 기록·수동 복구·bounded cleanup 생산 구현과 전진 Flyway 마이그레이션
    - `FOR UPDATE SKIP LOCKED`, 다중 worker·cleanup, 앱·DB 시계 차이, 89일·90일 경계와 조기 삭제 방지 PostgreSQL 테스트
    - `notification-ops` profile의 dry-run·전체 원자성·오름차순 잠금·폐기 확인·종료 코드 검증
    - 운영 파라미터의 실제 배포값, 전달 지연·oldest age·DB 부하 측정

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
