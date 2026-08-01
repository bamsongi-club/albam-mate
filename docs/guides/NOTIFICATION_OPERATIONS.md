# 알림 Outbox 운영 런북

이 문서는 P1 서비스 내 웹 알림의 PostgreSQL Outbox relay를 관측하고, `FAILED` 이벤트를 안전하게 재처리·폐기하며, 보존 기간이 지난 데이터를 정리하는 절차를 정의한다. 현재 적용할 운영 수치는 이 문서의 [현재 운영 파라미터 정본](#현재-운영-파라미터-정본), 기술 선택과 결정 이유는 [ADR-0040](../adr/notification/0040-postgresql-notification-relay-recovery-retention.md), 저장 필드와 제약은 [ERD의 P1 알림 저장 계약](../ERD.md#p1-알림-저장-계약)이 각각 소유한다.

> **단계: P1 운영 계약** · 현재 생산 코드·자동 검증·운영 상태는 [P1 기능 상태 정본의 `NOTI-01`](../p1/README.md#기능별-현재-상태)을 따른다. 생산 코드와 운영 배포가 완료되기 전에는 아래 one-shot 명령을 실행하거나 직접 SQL로 우회하지 않는다.

## 문서 소유권과 변경 규칙

- 아래 표가 relay·재시도·측정·보존·복구·cleanup의 **현재 수치를 소유하는 유일한 문서 정본**이다. 다른 문서는 값 대신 파라미터 키와 이 절 링크를 사용한다.
- ADR-0030과 ADR-0040 결정 본문의 숫자는 각 결정 당시 기준을 보존하는 역사 기록이다. 현행 정책은 ADR-0040, 현재 구현·테스트·운영 판정에 적용할 수치는 아래 표를 사용한다.
- 아래 값을 바꾸는 것은 ADR-0040의 승인 결정을 바꾸는 일이므로 후속 ADR 승인 없이 이 표만 수정하지 않는다. 승인된 후속 ADR, 이 표, 구현 설정·테스트와 운영 증거 양식을 같은 변경에서 맞춘다.
- 다른 문서나 구현값이 이 표와 다르면 임의로 절충하지 않고 문서·구현 drift로 판정한다. 운영 배포의 effective value도 표와 일치해야 한다.
- 브라우저 알림 조회 주기는 relay 운영 수치가 아니라 [P1 알림 프론트엔드 UX 계약](../p1/notification.md#조회와-polling)이 소유한다. API 페이지 크기처럼 다른 경계의 수치도 이 표에 섞지 않는다.

## 현재 운영 파라미터 정본

파라미터 키는 문서·구현 설정·테스트·운영 증거에서 같은 이름으로 식별하기 위한 안정된 용어다.

| 파라미터 키 | 현재 값 | 적용 경계 |
| --- | --- | --- |
| `RELAY_POLL_INTERVAL` | 5초 | 각 애플리케이션 인스턴스의 relay 실행 간격 |
| `RELAY_MAX_EVENTS_PER_RUN` | 50건 | 인스턴스당 한 실행의 최대 처리 이벤트 수 |
| `AUTO_PROCESS_MAX_ATTEMPTS` | 최초 처리 포함 5회 | 한 자동 처리 주기의 상한 |
| `AUTO_RETRY_DELAYS` | 10초, 30초, 2분, 10분 | 기록된 실패 1~4회 뒤 다음 처리까지의 간격 |
| `DELIVERY_P95_TARGET` | 30초 이하 | 완전한 전달 지연 표본 구간의 정상 목표 |
| `DELIVERY_MEASUREMENT_WINDOW` | 최근 24시간, 표본 부족 시 최대 최근 7일 | 전달 지연 표본 검색 구간 |
| `DELIVERY_MIN_SAMPLE_COUNT` | 100건 | p95 합격·불합격을 판정할 최소 표본 수 |
| `OLDEST_PROCESSABLE_TARGET` | 60초 이하 | 처리 가능한 가장 오래된 이벤트의 정상 목표 |
| `OLDEST_BREACH_BATCHES` | relay batch 로그 3회 연속 | oldest age 적체 조사 개시 조건 |
| `BROKER_REVIEW_DAILY_FAILURES` | 완전한 일별 구간 3회 연속 | 조정 뒤에도 지연 목표를 위반할 때 broker 비교를 검토하는 조건 |
| `FAILED_REPROCESS_WINDOW` | `occurredAt`부터 89일 미만 | `FAILED` 이벤트를 수동 재처리할 수 있는 기간. 90일 알림 만료 전 복구 여유를 둠 |
| `NOTIFICATION_RETENTION` | `createdAt`부터 90일 | 사용자 조회 제외와 Notification 정리 기준 |
| `PROCESSED_OUTBOX_RETENTION` | `processedAt`부터 30일 | 처리 완료 Outbox와 남은 수신자 행 보존 기간 |
| `DISCARDED_OUTBOX_RETENTION` | `discardedAt`부터 30일 | 폐기된 최소 Outbox 기록 보존 기간 |
| `OPS_MAX_EVENT_IDS` | 50개 | 한 운영 명령에 명시할 수 있는 서로 다른 이벤트 ID 상한 |
| `OPS_REASON_LENGTH` | 1~500자 | 재처리·폐기 사유 길이 |
| `OPS_REQUESTED_BY_LENGTH` | 1~100자 | 운영 명령 실행자 표기 길이 |
| `OPS_DEFAULT_DRY_RUN` | `true` | 변경 명령의 기본 실행 모드 |
| `CLEANUP_INTERVAL` | 이전 완료 뒤 1시간 | cleanup 다음 실행의 기준 간격 |
| `CLEANUP_JITTER` | 0~5분 | cleanup 인스턴스 간 실행 분산 범위 |
| `CLEANUP_BATCH_SIZE` | 트랜잭션당 500건 | Notification·Outbox 각 cleanup 선점·삭제 상한 |
| `CLEANUP_MAX_BATCHES_PER_TARGET` | 실행당 종류별 5개 batch | 한 cleanup 실행의 반복 상한 |

## 측정 계약

### 수신자별 전달 지연

Notification 한 건을 표본 한 개로 센다. 방 취소 이벤트 하나가 세 사용자에게 알림을 만들면 전달 지연 표본은 세 개다. 원인 업무의 최종 성공 트랜잭션과 relay 처리 트랜잭션은 각각 PostgreSQL `clock_timestamp()`를 한 번 조회해 `operationTime`으로 고정한다. 전자는 Outbox `recordedAt`, 후자는 그 이벤트에서 생성하는 모든 Notification의 `recordedAt`에 사용한다.

```text
deliveryDelayMs = Notification.recordedAt - Outbox.recordedAt
```

- `Outbox.recordedAt`: 원인 업무의 최종 성공 트랜잭션이 PostgreSQL에서 한 번 고정한 Outbox 기록 `operationTime`
- `Notification.recordedAt`: relay 트랜잭션이 PostgreSQL에서 한 번 고정한 Notification 기록 `operationTime`
- 두 값은 같은 PostgreSQL 시계에서 생성한 작업 시각이지만 실제 commit timestamp는 아니다.
- 브라우저의 조회 polling과 화면 렌더링 시간은 포함하지 않는다. polling 값의 정본은 [프론트엔드 UX 계약](../p1/notification.md#조회와-polling)이다.
- 결과가 음수이면 0으로 보정하거나 표본에서 제외하지 않는다. PostgreSQL 호스트 시계의 역방향 보정 또는 저장 시각 생성 계약 위반으로 기록하고 해당 측정 구간을 유효한 p95 근거로 사용하지 않는다.

### p95 표본과 산식

1. 측정 종료 시각을 한 번 고정하고 `Notification.recordedAt`이 [`DELIVERY_MEASUREMENT_WINDOW`](#현재-운영-파라미터-정본)의 기본 구간에 속하는 성공 Notification을 모은다.
2. 표본이 [`DELIVERY_MIN_SAMPLE_COUNT`](#현재-운영-파라미터-정본) 미만이면 같은 파라미터의 최대 구간까지 시작 시각을 확장한다.
3. 최대 구간에도 최소 표본 수가 모이지 않으면 `INSUFFICIENT_SAMPLE`로 기록하고 합격·불합격을 선언하지 않는다.
4. 처리 성공 로그를 사용하면 각 `deliveryDelayMs`를 같은 로그의 `recipientCount`만큼 가중하고, 전체 지연값을 오름차순으로 정렬한다.
5. `ceil(0.95 × N)`번째 값을 p95로 사용한다.

다음 상태 중 하나로 결과를 기록한다.

| 상태 | 조건 |
| --- | --- |
| `PASS` | p95가 [`DELIVERY_P95_TARGET`](#현재-운영-파라미터-정본) 이하 |
| `FAIL` | p95가 [`DELIVERY_P95_TARGET`](#현재-운영-파라미터-정본) 초과 |
| `INVALID_CLOCK` | 음수 지연이 한 건 이상 발견됨 |
| `INSUFFICIENT_SAMPLE` | 최대 측정 구간의 표본이 `DELIVERY_MIN_SAMPLE_COUNT` 미만 |

단일 `FAIL`은 조사 시작 신호다. PostgreSQL 쿼리·인덱스, connection pool, worker 수와 batch를 조정한 뒤에도 최소 표본을 충족한 완전한 일별 구간에서 [`BROKER_REVIEW_DAILY_FAILURES`](#현재-운영-파라미터-정본)만큼 연속 실패할 때 외부 broker 비교를 위한 후속 ADR을 검토한다.

### 처리 가능한 가장 오래된 이벤트

측정 SQL의 `MATERIALIZED operation` CTE가 PostgreSQL `clock_timestamp()`를 한 번 평가해 고정한 `measurementTime`에 다음 조건을 모두 만족하는 이벤트만 처리 가능 backlog로 센다. relay 선점 SQL도 같은 DB 시각 생성 규칙으로 due 여부를 판정한다.

```text
status IN (PENDING, RETRY_WAIT)
availableAt <= measurementTime
```

```text
oldestProcessableAgeMs = measurementTime - MIN(availableAt)
```

대상이 없으면 `null`이다. 미래 시각까지 기다리는 `RETRY_WAIT`와 운영자 조치가 필요한 `FAILED`는 이 값에 포함하지 않는다. `oldestProcessableAgeMs`가 [`OLDEST_PROCESSABLE_TARGET`](#현재-운영-파라미터-정본)을 [`OLDEST_BREACH_BATCHES`](#현재-운영-파라미터-정본)만큼 연속 초과하면 아래 트러블슈팅 순서로 조사한다.

### 측정 환경 기록

통제된 부하 측정과 운영 측정에는 다음 정보를 함께 남긴다.

| 구분 | 필수 기록 |
| --- | --- |
| 배포 | 환경, commit SHA, 애플리케이션 인스턴스 수 |
| 런타임 | Java 버전, JVM 옵션, 애플리케이션 시작 시각 |
| 데이터베이스 | PostgreSQL 버전, 인스턴스 등급, connection pool 최대값 |
| relay | `RELAY_POLL_INTERVAL`, `RELAY_MAX_EVENTS_PER_RUN`, 측정 중 조정한 값 |
| 입력 | 이벤트 수, 수신자별 Notification 수, 이벤트 유형별 수 |
| 결과 | 측정 시작·종료, 표본 수, p50·p95·최대 지연, oldest age, 성공·재시도·`FAILED` 수 |

CI의 단위·MVC·PostgreSQL 테스트는 정확성을 증명한다. 실제 시간·부하를 사용하는 이 측정 결과를 대신하지 않는다.

## 구조화 로그 계약

P1에서는 Actuator/Micrometer, dashboard와 외부 경고 전송을 먼저 추가하지 않는다. 다음 key-value 로그를 수집해 처리 흐름과 지연을 재구성한다.

| `event` 값 | 레벨 | 필수 필드 |
| --- | :---: | --- |
| `notification_outbox_relay_event_processed` | INFO | `sourceEventId`, `eventType`, `recipientCount`, `outboxRecordedAt`, `notificationRecordedAt`, `failureCount`, `totalFailureCount`, `reprocessCount`, `deliveryDelayMs`, `processingDurationMs` |
| `notification_outbox_relay_batch_completed` | INFO 또는 DEBUG | `claimedCount`, `processedCount`, `retryScheduledCount`, `failedCount`, `durationMs`, `oldestProcessableAgeMs` |
| `notification_outbox_relay_retry_scheduled` | WARN | `sourceEventId`, `eventType`, `failureCode`, `failureCount`, `totalFailureCount`, `nextAvailableAt` |
| `notification_outbox_relay_event_failed` | WARN | `sourceEventId`, `eventType`, `failureCode`, `failureCount`, `totalFailureCount`, `deterministicFailure` |
| `notification_outbox_relay_scheduler_failed` | ERROR | `failureCode`, `exceptionClass`, `occurredAt` |
| `notification_outbox_operation_previewed` | INFO | `action`, `reasonReference`, `requestedBy`, `requestedCount`, `eligibleCount`, `dryRun` |
| `notification_outbox_operation_completed` | WARN | `action`, `reasonReference`, `requestedBy`, `requestedCount`, `changedCount`, `dryRun` |
| `notification_cleanup_completed` | INFO 또는 DEBUG | `targetType`, `batchNumber`, `deletedCount`, `durationMs`, `measurementTime` |
| `notification_cleanup_failed` | WARN | `targetType`, `batchNumber`, `failureCode`, `exceptionClass`, `measurementTime` |

cleanup 완료·실패 로그는 batch마다 한 건이며, `measurementTime`은 해당 batch 트랜잭션이 PostgreSQL에서 고정해 due 선점·삭제에 사용한 값이다. DB 시각을 얻기 전에 실패하면 due 판정과 삭제를 실행하지 않고 `measurementTime`을 생략한다. 정상 batch에서 처리·적체가 모두 0이면 batch 완료 로그는 DEBUG로 낮출 수 있다. 다음 값은 로그에 넣지 않는다.

- 수신자 사용자 ID, 닉네임과 이메일
- 방 제목, 정확한 장소와 참가자 목록
- 이벤트 payload, 인증·세션 정보
- 원본 SQL, 바인딩 파라미터와 정제되지 않은 예외 메시지

`sourceEventId`를 로그와 운영 명령의 이벤트 상관 키로 사용한다. 실제 변경 명령은 앞뒤 공백을 제거한 자유 서술 `reason`을 `lastReprocessReason` 또는 `discardReason`에만 저장하고, preview는 저장하지 않는다. `reason`은 구조화 로그, 표준 출력·오류와 예외 메시지에 포함하지 않는다. 로그에는 검증을 통과한 `reasonReference`를 그대로 남기고 `action`을 비민감 분류로 사용한다. `reasonReference`는 전체 값이 `(?:INC-[0-9]{4}-[0-9]{1,10}|ISSUE-[1-9][0-9]{0,9})` 정규식과 일치해야 한다. 다른 형식은 마스킹하지 않고 인자 검증 실패로 거절한다. `reason`에는 개인정보나 비밀값을 넣지 않는다.

## 지연·적체 트러블슈팅

relay가 PostgreSQL에서 고정한 `operationTime`이 `occurredAt + NOTIFICATION_RETENTION` 이상이면 이미 만료된 Notification이나 `PROCESSED`를 만들지 않고 `NOTIFICATION_EXPIRED` 결정적 실패로 기록한다. 이 이벤트는 `FAILED_REPROCESS_WINDOW`도 지났으므로 재처리하지 않고 근거를 확인한 뒤 `DISCARDED`로 종결한다.

### 1. relay 자체가 실행되는지 확인

- `notification_outbox_relay_batch_completed`가 [`RELAY_POLL_INTERVAL`](#현재-운영-파라미터-정본) 주기로 나타나는지 확인한다.
- 로그가 없으면 애플리케이션 인스턴스 상태, scheduler 활성화와 최근 `notification_outbox_relay_scheduler_failed`를 확인한다.
- scheduler 실패가 있으면 DB 연결, connection pool 고갈, Flyway·스키마 불일치 순으로 확인한다.

### 2. 처리 용량이 부족한지 확인

- `claimedCount`가 [`RELAY_MAX_EVENTS_PER_RUN`](#현재-운영-파라미터-정본)에 계속 도달하고 `oldestProcessableAgeMs`가 증가하면 batch 상한까지 처리하는 실제 backlog다.
- `processingDurationMs`와 DB connection 사용량이 함께 증가하면 쿼리·인덱스·트랜잭션 시간을 먼저 확인한다.
- DB 병목이 없고 이벤트 유입이 처리량보다 많을 때만 worker·batch·polling 조정을 비교한다.

### 3. 특정 이벤트가 반복 실패하는지 확인

- 같은 `sourceEventId`의 retry·`FAILED` 로그를 모은다.
- `deterministicFailure=true`이면 같은 입력을 즉시 반복하지 않는다. 코드·마이그레이션·스냅샷 불변식을 고친 뒤 dry-run 재처리한다.
- 일시 DB 오류면 다음 `nextAvailableAt` 뒤 성공 로그가 생기는지 확인한다.
- 한 poison event 뒤 정상 이벤트가 계속 처리되는지도 batch 성공 건수로 확인한다.

### 4. p95만 높고 현재 oldest age는 정상인지 확인

- 과거 retry 뒤 늦게 성공한 이벤트가 p95를 높였는지 `failureCount`, `reprocessCount`를 확인한다.
- 음수 지연이 있으면 PostgreSQL 호스트의 NTP·UTC 설정과 두 `recordedAt`의 저장 출처를 확인하고 측정 구간을 다시 만든다.
- 특정 시간대의 DB·배포 장애가 원인이면 해당 구간과 정상 구간을 분리해 기록하되, 불리한 표본을 임의로 삭제하지 않는다.

## 수동 복구 명령 계약

### 구현 필수 조건

구현은 `notification-ops` profile에서만 one-shot 명령 adapter를 활성화해야 한다.

- 웹 서버와 일반 scheduler를 시작하지 않는다.
- 운영 데이터소스와 Flyway·JPA 검증은 일반 애플리케이션과 같은 계약을 사용한다.
- `NotificationOutboxRecoveryService` 호출이 끝나면 종료 코드를 반환하고 프로세스를 종료한다.
- 일반 웹 애플리케이션 profile에서 `app.notification.ops.*` 인자가 들어오면 조용히 무시하지 않고 기동을 거절한다.
- 명령 adapter는 Repository나 직접 SQL을 호출하지 않는다.

### 공통 인자

| 인자 | 필수 | 계약 |
| --- | :---: | --- |
| `app.notification.ops.action` | Y | `INSPECT`, `REPROCESS`, `DISCARD` 중 하나 |
| `app.notification.ops.event-ids` | Y | 서로 다른 양의 ID를 `OPS_MAX_EVENT_IDS` 이하로 지정, 쉼표 구분 |
| `app.notification.ops.dry-run` | 변경 작업 | 기본값은 `OPS_DEFAULT_DRY_RUN`; 실제 변경은 명시적으로 `false` |
| `app.notification.ops.reason-reference` | 재처리·폐기 | `(?:INC-[0-9]{4}-[0-9]{1,10}|ISSUE-[1-9][0-9]{0,9})` 형식의 Incident·Issue 식별자; 로그에는 이 값만 기록 |
| `app.notification.ops.reason` | 재처리·폐기 | 앞뒤 공백 제거 후 `OPS_REASON_LENGTH` 범위의 저장 전용 자유 서술; 로그 출력 금지 |
| `app.notification.ops.requested-by` | 재처리·폐기 | `OPS_REQUESTED_BY_LENGTH` 범위의 팀 계정 또는 배포 주체; 로그 전용 |
| `app.notification.ops.confirm` | 폐기 | 실제 폐기 때 정확히 `DISCARD` |

모든 명령은 입력 ID의 중복을 거절한 뒤 오름차순으로 정규화한다. 실제 `REPROCESS`·`DISCARD`는 복구 트랜잭션에서 PostgreSQL `clock_timestamp()`를 한 번 평가해 기준 `operationTime`으로 고정하고, 하나의 `SELECT ... WHERE id IN (...) ORDER BY id FOR UPDATE`로 그 순서대로 모두 잠근다. 잠근 결과를 전체 검증한 뒤 하나라도 없거나 부적격이면 아무 이벤트도 변경하지 않는다. `INSPECT`와 preview는 같은 정렬로 조회하지만 이후 실제 변경까지 상태가 유지된다고 보장하지 않는다.

### 명령 예시의 상태

아래 형태는 P1 알림 운영 CLI 계약이다. 실행 가능 여부는 [P1 기능 상태 정본의 `NOTI-01`](../p1/README.md#기능별-현재-상태)을 따르며, 생산 코드와 운영 배포가 완료되기 전에는 실행하지 않는다. 실행할 때는 `<배포 JAR 절대 경로>`와 실행자·사유·이벤트 ID를 실제 값으로 바꾼다.

#### 상태 확인

```powershell
$notificationArtifact = '<배포 JAR 절대 경로>'
java -jar $notificationArtifact `
  --spring.profiles.active=notification-ops `
  --app.notification.ops.action=INSPECT `
  --app.notification.ops.event-ids=123,124
```

`INSPECT`는 상태, 이벤트 유형, 발생·만료 시각, 현재·누적 실패 수, 마지막 실패 코드와 재처리 가능 여부만 출력한다. 수신자 ID와 payload는 출력하지 않는다.

#### 재처리 preview

```powershell
$notificationArtifact = '<배포 JAR 절대 경로>'
java -jar $notificationArtifact `
  --spring.profiles.active=notification-ops `
  --app.notification.ops.action=REPROCESS `
  --app.notification.ops.event-ids=123,124 `
  --app.notification.ops.reason-reference='INC-2026-001' `
  --app.notification.ops.reason='원인 수정 후 재처리' `
  --app.notification.ops.requested-by='team-account' `
  --app.notification.ops.dry-run=true
```

preview에서 모든 대상이 `FAILED`, [`FAILED_REPROCESS_WINDOW`](#현재-운영-파라미터-정본) 안, 수신자 스냅샷 유효로 표시되는지 확인한다.

#### 재처리 실행

같은 commit·환경·ID·사유 근거·사유로 `dry-run=false`만 바꿔 실행한다.

```powershell
$notificationArtifact = '<배포 JAR 절대 경로>'
java -jar $notificationArtifact `
  --spring.profiles.active=notification-ops `
  --app.notification.ops.action=REPROCESS `
  --app.notification.ops.event-ids=123,124 `
  --app.notification.ops.reason-reference='INC-2026-001' `
  --app.notification.ops.reason='원인 수정 후 재처리' `
  --app.notification.ops.requested-by='team-account' `
  --app.notification.ops.dry-run=false
```

성공하면 현재 주기의 `failureCount`는 0, `reprocessCount`는 1 증가하고 복구 트랜잭션이 PostgreSQL `clock_timestamp()`로 한 번 고정한 `operationTime`을 `lastReprocessedAt`과 `availableAt`에 함께 사용한 `RETRY_WAIT`가 된다. 누적 실패와 마지막 실패 정보는 보존한다. relay 성공 뒤 `PROCESSED`와 사용자별 Notification을 확인한다.

#### 폐기 preview와 실행

폐기는 재처리하지 않기로 결정한 `FAILED`에만 사용한다. preview를 먼저 실행하고, 실제 명령에는 `dry-run=false`와 `confirm=DISCARD`를 모두 지정한다.

```powershell
$notificationArtifact = '<배포 JAR 절대 경로>'
java -jar $notificationArtifact `
  --spring.profiles.active=notification-ops `
  --app.notification.ops.action=DISCARD `
  --app.notification.ops.event-ids=123 `
  --app.notification.ops.reason-reference='INC-2026-001' `
  --app.notification.ops.reason='원인 데이터 복구 불가' `
  --app.notification.ops.requested-by='team-account' `
  --app.notification.ops.dry-run=false `
  --app.notification.ops.confirm=DISCARD
```

성공하면 이벤트는 `DISCARDED`, `discardedAt`과 `DISCARDED_OUTBOX_RETENTION`을 적용한 `cleanupAt`을 기록한다. 수신자 스냅샷은 같은 트랜잭션에서 즉시 삭제된다. 폐기 뒤에는 이 명령으로 되돌리지 못하므로 사유와 Incident·Issue 근거를 먼저 남긴다.

### 종료 코드와 원자성

| 종료 코드 | 의미 | 상태 변경 |
| ---: | --- | --- |
| `0` | inspect·preview 완료 또는 요청한 전체 변경 성공 | 명령에 따라 전체 변경 또는 미변경 |
| `2` | `reasonReference` 형식을 포함한 인자 검증 실패, 대상 없음·중복, 상태·`FAILED_REPROCESS_WINDOW`·확인 조건 불일치 | 없음 |
| `1` | 애플리케이션 기동, DB 연결 또는 예상하지 못한 실행 실패 | 명령 트랜잭션 롤백 |

부분 성공 종료 코드는 두지 않는다. 한 대상이라도 바꿀 수 없으면 같은 명령의 다른 대상도 변경하지 않는다. 명령 실패 뒤 대상 목록을 넓히지 말고 같은 ID를 다시 `INSPECT`한다.

## cleanup 동작과 실패 복구

### 실행 경계

1. Scheduler가 [`CLEANUP_INTERVAL`](#현재-운영-파라미터-정본)에 [`CLEANUP_JITTER`](#현재-운영-파라미터-정본)를 더해 실행한다.
2. Notification cleanup과 Outbox cleanup을 독립적으로 호출한다.
3. 각 Executor는 batch 트랜잭션 안에서 `SELECT clock_timestamp()`를 한 번 실행해 PostgreSQL `measurementTime`을 고정한다. 같은 값을 바인딩해 정리 인덱스의 가장 이른 due ID를 `FOR UPDATE SKIP LOCKED`로 `CLEANUP_BATCH_SIZE` 이하 선점하고 삭제하며, 완료·실패 로그에도 같은 `measurementTime`을 사용한다. Scheduler와 Coordinator는 만료 판정 시각을 만들지 않는다.
4. 한 종류에서 `CLEANUP_MAX_BATCHES_PER_TARGET` 이하 batch를 처리한다. 삭제 건수가 `CLEANUP_BATCH_SIZE`보다 작으면 더 이상 즉시 반복하지 않는다.
5. 한 batch가 실패하면 그 batch만 롤백하고 같은 실행에서 재시도하지 않는다. 다음 주기에서 다시 시작한다.

### 삭제 대상

| 대상 | 삭제 조건 | 함께 삭제되는 값 |
| --- | --- | --- |
| Notification | `expiresAt <= measurementTime` | 해당 Notification 행 |
| `PROCESSED` Outbox | `cleanupAt <= measurementTime` | FK cascade로 남은 수신자 행 |
| `DISCARDED` Outbox | `cleanupAt <= measurementTime` | 최소 폐기 이벤트 기록 |

`PENDING`, `RETRY_WAIT`, `FAILED`는 자동 삭제하지 않는다. 읽음 여부는 Notification 보존 기간을 바꾸지 않는다. cleanup 적체를 해소하기 위해 직접 SQL이나 한 트랜잭션 전체 삭제를 사용하지 않는다.

## 구현 완료 전 검증

운영 가능 판정에는 다음 근거가 모두 필요하다.

1. [P1 알림 명세의 검증 증거 매핑](../p1/notification.md#검증-증거-매핑)에 연결된 단위·MVC·PostgreSQL 테스트가 통과한다.
2. `notification-ops`의 inspect·dry-run·재처리·폐기 확인·일괄 원자성·종료 코드, `reasonReference` 형식 거절과 `NOTIFICATION_EXPIRED` 재처리 거절 테스트가 통과한다.
3. PostgreSQL에서 다중 worker `SKIP LOCKED`, poison event 격리, cleanup 다중 인스턴스 선점과 서로 겹치는 역순 복구 ID 변경 명령이 교착 없이 한 명령의 성공 또는 계약된 전체 부적격 결과로 끝나는지 검증한다.
4. 고정된 commit·환경에서 `DELIVERY_MIN_SAMPLE_COUNT` 이상 전달 지연 표본을 만들고 산식과 환경을 기록한다.
5. 로그와 표준 출력·오류에 자유 서술 `reason`, 수신자·payload·SQL·인증 정보가 없음을 자동 또는 캡처 기반으로 확인한다.
6. 운영 배포에서 dry-run을 실행하고 실제 변경 없이 대상 판정과 종료 코드가 맞는지 확인한다.

코드와 테스트가 있어도 4~6번이 없으면 운영 검증은 완료가 아니다.

## 운영 증거 기록 양식

```text
측정·조치 시각:
환경 / commit:
Java / PostgreSQL:
애플리케이션 인스턴스 / DB pool:
polling / batch:
측정 구간 / 표본 수:
p50 / p95 / 최대 지연:
oldestProcessableAgeMs:
성공 / 재시도 / FAILED:
대상 sourceEventId:
실행 action / dry-run:
reasonReference / 실행자:
종료 코드 / 변경 건수:
후속 확인:
```

수신자 정보, DB 비밀번호, 세션·인증 값과 원본 예외는 기록하지 않는다.

## 관련 문서

- [P1 알림 구현 명세](../p1/notification.md)
- [ADR-0029: 방 변경 통합 이벤트와 Transactional Outbox](../adr/notification/0029-room-integration-event-transactional-outbox.md)
- [ADR-0030: PostgreSQL polling relay 처리와 복구 — 대체됨](../adr/notification/0030-postgresql-notification-relay-processing-recovery.md)
- [ADR-0040: PostgreSQL 알림 relay·복구·보존 정책](../adr/notification/0040-postgresql-notification-relay-recovery-retention.md)
- [ADR-0039: 알림 표시 투영과 PostgreSQL 조회·읽음 시각](../adr/notification/0039-notification-presentation-and-bulk-read-snapshot.md)
- [ERD의 P1 알림 저장 계약](../ERD.md#p1-알림-저장-계약)
- [알림 아키텍처](../ARCHITECTURE.md#알림-relay복구정리)
- [프로젝트 검증 명령](../COMMANDS.md)
