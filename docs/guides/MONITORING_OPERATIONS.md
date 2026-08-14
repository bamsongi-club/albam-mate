# P2 운영 관측 런북

> **문서 상태: active · 계약 정본 · 구현·배포 전 기준 · 최종 검증일: 2026-08-14**
>
> 이 문서는 `OPS-01`~`OPS-05`의 metric·log 허용 목록, 경고 대응과 전체 스택 계획 종료·재기동 계약을 소유한다. 현재 `albam-mate-infra` 운영 CLI에는 이 절차가 아직 구현되지 않았으므로 아래 흐름을 실행 가능한 명령으로 해석하지 않는다.

[P2 운영 관측 명세](../p2/monitoring.md)는 기능 규칙과 완료 기준, [운영 대시보드 정책](../p2/dashboard.md)은 화면·등급·비용 정책, [ADR-0058](../adr/platform/0058-p2-application-metrics-otlp-host-cloudwatch-agent.md)과 [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)는 전송 기술을 소유한다. 이 런북은 사용자 결정을 마친 [#713](https://github.com/bamsongi-club/albam-mate/issues/713)을 정본에 반영해 실제 구현·배포·장애 대응에 연결하며, 문서나 정적 설정만으로 구현·배포·실측 완료를 선언하지 않는다.

## 현재 상태와 적용 경계

| 구분 | 현재 판정 | 완료 증거 |
| --- | --- | --- |
| 이 문서의 metric·log·alarm·상태 전이 계약 | 확정 | 이 문서와 연결 정본의 링크·회귀 검사 |
| 애플리케이션 OTLP·JSON logging | 미구현 | 생산 설정·자동 검증·release SHA |
| 인프라 상태 정본·경고 제어·Scheduler | 미구현 | `albam-mate-infra` 구현·plan·자동 검증 |
| AWS 배포와 실제 수집 | 미배포·미측정 | 같은 release의 metric·log 도착과 수집 공백 검사 |
| 경고·복구 | 미측정 | `OK → ALARM → OK`, SNS 실제 수신과 receipt |

이 계약은 App1·App2·PostgreSQL·Redis로 구성한 하나의 `stackId` 전체에만 적용한다. 애플리케이션 배포, 한 컨테이너 재시작, rolling restart와 부분 유지보수는 `PLANNED_STOP`이 아니며 `ACTIVE` 상태와 배포 grace 안에서 관측한다.

## 운영 상태 정본

### 상태와 저장 형식

운영 상태의 단일 정본은 Parameter Store의 일반 `String` `/albam-mate/ops/{stackId}/state`다. 애플리케이션 비밀값 prefix와 분리하고 Spring·PostgreSQL·Redis 프로세스는 이 값을 읽거나 쓰지 않는다.

~~~json
{
  "schemaVersion": 1,
  "environment": "performance",
  "stackId": "example-stack",
  "release": "git-sha-or-image-digest",
  "state": "PLANNED_STOP",
  "declaredAt": "2026-08-14T03:00:00Z",
  "plannedUntil": "2026-08-21T03:00:00Z",
  "actorArn": "arn:aws:iam::123456789012:user/example",
  "receiptId": "immutable-operation-id"
}
~~~

| 필드 | 계약 |
| --- | --- |
| `schemaVersion` | 현재 값은 `1`; 알 수 없는 버전은 갱신하지 않고 명령 실패 |
| `environment`, `stackId` | CLI 대상과 정확히 일치해야 하며 다른 스택 상태를 재사용하지 않음 |
| `release` | 종료 직전 또는 재기동한 애플리케이션 SHA·image digest를 식별 |
| `state` | `ACTIVE`, `PLANNED_STOP`만 허용 |
| `declaredAt` | AWS 인증 운영자가 상태를 확정한 UTC 시각 |
| `plannedUntil` | `PLANNED_STOP`에서 필수, `declaredAt` 초과·최대 7일; `ACTIVE`에서는 `null` |
| `actorArn` | STS `GetCallerIdentity`로 확인한 AWS principal ARN; 사용자 입력값을 신뢰하지 않음 |
| `receiptId` | 한 번의 상태 전이·연장·복구 기록을 연결하는 고유 ID |

`PLANNED_STOP`은 자동 연장하지 않는다. 운영자는 최대 7일 안에서 새 `plannedUntil`을 명시해 연장하고 상태·Scheduler·receipt를 모두 재검증해야 한다. 기한이 지나면 가용성 alarm action은 계속 억제하지만 `계획 종료 초과` `critical`을 즉시 보내고, `ACTIVE` 전환 또는 명시적 연장까지 24시간마다 반복한다.

### writer와 IAM 경계

- 유일한 writer는 AWS 인증 운영자가 로컬에서 실행하는 `albam-mate-infra` 운영 CLI다. 애플리케이션 API·관리자 화면·GitHub Actions는 상태를 선언·연장·해제하지 않는다.
- 운영 principal에 대상 state parameter의 `GetParameter`·`PutParameter`, 대상 alarm의 `DescribeAlarms`·`DisableAlarmActions`·`EnableAlarmActions`, 대상 Scheduler의 조회·생성·수정·삭제와 필요한 SNS publish·제한된 role 전달 권한만 부여한다.
- EC2 application role은 운영 state parameter와 alarm·Scheduler 변경 권한을 갖지 않는다. 기존 애플리케이션 비밀값 read와 CloudWatch Agent 전송 권한도 운영 writer 권한과 분리한다.
- CLI는 매 단계 뒤 SSM state, alarm action 상태와 schedule을 다시 읽어 기대값과 일치할 때만 다음 단계로 이동한다. API 성공 응답만으로 성공을 선언하지 않는다.
- 시간 만료 뒤에도 target alarm이 `ALARM`이면 action을 다시 실행할 수 있는 CloudWatch Alarm Mute Rule은 사용하지 않는다. 가용성 action은 CLI가 `DisableAlarmActions`·`EnableAlarmActions`로 명시 제어하고, 기한 초과는 별도 Scheduler·SNS가 담당한다.

## Metric export 허용 목록

### 공통 dimension과 비용 경계

모든 custom metric은 `environment`, `stackId`, `service`, `role`, `instanceId`, `release` 가운데 수집 지점에서 안정적으로 주입할 수 있는 배포 dimension만 사용한다. HTTP는 실제 URL 대신 정규화 `method`, `route`, `status` 계열을 사용한다. 사용자·ROOM·메시지·알림·request ID와 임의 exception message는 dimension으로 금지한다.

아래 목록 밖의 application meter는 기본적으로 중앙 export하지 않는다. 새 meter는 이름·type·유한한 tag 값·query·예상 시계열 수를 이 표에 먼저 추가한다. P2가 추가하는 OTLP metric·중앙 로그·alarm 예상 월 비용은 기존 host 관측을 제외하고 USD 10 이하로 유지한다.

### 인프라·Spring 표준 meter

| 이름 | type·source | 허용 dimension | 사용 query | 상태 |
| --- | --- | --- | --- | --- |
| `AWS/EC2 StatusCheckFailed` | gauge·EC2 | `InstanceId`, 배포 `role` mapping | 1분 `Maximum > 0`, 2회 | 현재 인프라 alarm·P2 연결 필요 |
| `AWS/EC2 CPUUtilization` | gauge·EC2 | `InstanceId`, 배포 `role` mapping | 1분 `Average`·원인 분석 | 현재 수집 |
| `AWS/EC2 CPUCreditBalance` | gauge·EC2 | `InstanceId`, 배포 `role` mapping | 5분 `Minimum < 20`, 1회 | 현재 인프라 alarm |
| `CWAgent mem_used_percent` | gauge·host Agent | `InstanceId`, `StackId`, `Role` | 10초 `Maximum > 85`, 6회 | 현재 인프라 alarm |
| `CWAgent disk_used_percent` | gauge·host Agent | 위 값과 `fstype=xfs`, `path=/` | 10초 `Maximum > 85`, 6회 | 현재 인프라 alarm |
| `AWS/EC2 NetworkIn`, `NetworkOut` | counter·EC2 | `InstanceId`, 배포 `role` mapping | 1분 `Sum`·원인 분석 | 현재 dashboard |
| `http.server.requests` | timer·Spring MVC observation | `method`, 정규화 `uri`, `status`, `outcome` | 5분 count·p50·p95·p99·5xx 비율 | meter 기반 있음·histogram/export 설정 필요 |
| `jvm.memory.used`, `jvm.memory.max` | gauge·Micrometer JVM binder | `area`, 제한된 `id` | 1분 `Maximum`·used/max | meter 기반 있음·export 필요 |
| `jvm.gc.pause` | timer·Micrometer JVM binder | `action`, `cause`의 라이브러리 유한값 | 5분 count·p95 | meter 기반 있음·export 필요 |
| `jvm.threads.live` | gauge·Micrometer JVM binder | 없음 | 1분 `Maximum` | meter 기반 있음·export 필요 |
| `tomcat.threads.busy`, `tomcat.threads.current`, `tomcat.threads.config.max` | gauge·Tomcat binder | connector `name`의 배포 고정값 | 1분 `Maximum`, busy/max | meter 기반 있음·export 필요 |
| `hikaricp.connections.active`, `idle`, `pending`, `max`, `timeout` | gauge·counter·HikariCP binder | 고정 pool 이름 | 1분 `Maximum`·timeout `Sum` | meter 기반 있음·export 필요 |
| `albam.dependency.health` | gauge·추가 구현 | `dependency=postgresql|redis`; 값 `1=up`, `0=down` | 마지막 값과 2회 연속 down | 추가 구현 필요 |
| `albam.telemetry.last_success_age` | gauge·Agent/infra 추가 구현 | `signal=metric|log`, 배포 dimension | 5분 `Maximum`; ACTIVE에서 임계 초과 | 추가 구현 필요 |

`meter 기반 있음`은 Actuator·Micrometer binder를 사용할 수 있다는 뜻이며 CloudWatch 도착이나 percentile 존재를 뜻하지 않는다. 실제 OTLP 이름 변환이 생기면 CloudWatch의 최종 이름을 구현 PR에서 이 표와 alarm query에 함께 고정한다.

### 현재 생산 코드의 domain meter

source는 첫 두 meter가 `AuthenticationRequestLimiterMetrics`, 다음 네 meter가 `AuthNotificationMeasurementRecorder`, WebSocket 네 meter가 `ChatWebSocketMetrics`, message delivery 두 meter가 `ChatMessageCommittedListener`, retention 여덟 meter가 `ChatMessageRetentionMetrics`다. `추가 구현 필요` meter는 각각 notification relay, ROOM status correction과 waitlist module이 생산하고 도메인 코드가 CloudWatch SDK에 의존하지 않도록 `MeterRegistry`까지만 소유한다.

| 이름 | type | 허용 tag 값 | 사용 query·용도 | 상태 |
| --- | --- | --- | --- | --- |
| `auth.request.limiter.rejections` | counter | `family=ip|failure`, `reason=capacity_saturated|redis_unavailable` | 5분 `Sum`·인증 거절 원인 | 현재 코드·export 필요 |
| `auth.request.limiter.capacity.utilization` | gauge | `family=ip|failure` | 5분 `Maximum`·용량 warning 후보 | 현재 코드·export 필요 |
| `auth.login.stage.duration` | timer | `stage=request-limit|verification-gate|failure-limit|user-lookup|bcrypt-permit|bcrypt-verify|bcrypt-upgrade-check|bcrypt-upgrade-encode|password-hash-update|failure-record|failure-reset|session-context-save|session-repository-save` | stage별 count·p95 | 현재 코드·production 기본 비활성 |
| `auth.login.rejections` | counter | `source=ip-limit|verification-gate|failure-limit|bcrypt-slot|redis-unavailable` | source별 5분 `Sum` | 현재 코드·production 기본 비활성 |
| `notification.query.stage.duration` | timer | `stage=content|total-count|unread-count` | stage별 count·p95 | 현재 코드·production 기본 비활성 |
| `notification.relay.stage.duration` | timer | `stage=claim|event-fetch|recipient-lookup|recipient-insert-loop|event-flush`일 때 `result=success`; `stage=tx-commit|tx-total|afterCompletion`일 때 `result=committed|rolled-back` | stage별 count·p95 | 현재 코드·production 기본 비활성 |
| `chat.websocket.connections.active` | gauge | 없음 | App별 `Maximum` | 현재 코드·export 필요 |
| `chat.websocket.delivery.latency` | timer | 없음 | 5분 count·p95 | 현재 코드·export 필요 |
| `chat.websocket.delivery.failures` | counter | 없음 | 5분 `Sum` | 현재 코드·export 필요 |
| `chat.websocket.recovery.messages` | counter | 없음 | 5분 `Sum`·복구 확인 | 현재 코드·export 필요 |
| `chat.message.delivery.duration` | timer | 없음 | 5분 count·p95 | 현재 코드·export 필요 |
| `chat.message.delivery.failures` | counter | 없음 | 5분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.lock.skipped` | counter | 없음 | 1시간 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.rooms.purged` | counter | 없음 | 1일 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.messages.deleted` | counter | 없음 | 1일 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.failures` | counter | 없음 | 15분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.lease.guard.aborted` | counter | 없음 | 15분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.backlog.remaining` | counter | 없음 | 15분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.execution.duration` | timer | 없음 | 실행별 p95·max | 현재 코드·export 필요 |
| `chat.message.retention.delay` | timer | 없음 | 실행별 p95·max | 현재 코드·export 필요 |
| `notification.relay.events` | counter | `outcome=processed|retry_scheduled|failed` | 1분 `Sum`·최종 전달 결과 | 추가 구현 필요 |
| `notification.relay.delivery.duration` | timer | 없음 | 1분 p95·알림 전달 30초 기준 | 추가 구현 필요 |
| `notification.relay.oldest.processable.age` | gauge | 없음 | 1분 `Maximum`·60초 3회 | 추가 구현 필요 |
| `room.status.correction.runs` | counter | `outcome=completed|failed|skipped|batch_limit` | 15분 `Sum`·보정 결과 | 추가 구현 필요 |
| `room.status.correction.duration` | timer | 없음 | 실행별 p95·180초 warning | 추가 구현 필요 |
| `room.waitlist.operations` | counter | `operation=join|cancel|promote`, `outcome=accepted|rejected|failed` | 배포 fixture별 `Sum`·최종 업무 결과 | 추가 구현 필요 |

마지막 여섯 meter는 현재 구조화 log·업무 결과에 값이 있거나 검증 경계가 있지만 지속 alarm·업무 결과용 meter는 없는 항목의 구현 이름을 고정한다. 구현 중 다른 이름이나 Logs metric filter가 더 적합하다고 판단하면 코드만 다르게 만들지 않고 이 inventory와 alarm query를 같은 변경에서 갱신한다.

## 중앙 log 허용 목록

### 공통 필드

허용 공통 필드는 UTC `timestamp`, `level`, 고정 `event`, `environment`, `stackId`, `service`, `role`, `instanceId`, `release`, 서버 확정 `requestId`다. event별 수치·enum 필드는 아래 목록에서만 추가한다.

- 수치: 고정 event가 정의한 `*Ms`, `*Count`, `*Limit`, `attempt`, `batchNumber`
- 유한 enum: `failureCode`, `reasonCode`, `exceptionClass`, `eventType`, `targetType`, `action`, `outcome`, `roomStatus`, `useCase`, `section`, `lockName`
- UTC 시각: `measurementTime`, `occurredAt`, `outboxRecordedAt`, `notificationRecordedAt`, `nextAvailableAt`
- 접근 제한 상관 키: 단일 `roomId`, `messageId`, `sourceEventId`; metric dimension·dashboard group·alarm dimension에는 사용하지 않는다.

이메일·IP·사용자 ID·`actorUserId`, session·cookie·token·secret, request/response body, prompt/response 원문, Tool 인자·결과, 채팅 내용, 알림 payload, 원본 SQL, 예외 message·stack trace 전문은 중앙 전송을 금지한다. `sourceEventIds` 같은 ID 배열과 자유 입력 `requestedBy`·`reasonReference`도 제외한다. 정상 2xx·4xx access log 전체는 전송하지 않는다.

### event inventory

| event | level·허용 필드 | 중앙 전송 상태 |
| --- | --- | --- |
| `notification_outbox_relay_batch_completed` | INFO; 처리·retry·failure count, `durationMs`, `oldestProcessableAgeMs` | 허용 |
| `notification_outbox_relay_event_processed` | INFO; 단일 `sourceEventId`, `eventType`, count·시각·지연 | 허용·상관 키 비집계 |
| `notification_outbox_relay_retry_scheduled`, `notification_outbox_relay_event_failed` | WARN; 단일 `sourceEventId`, 고정 failure 값·count·다음 시각 | 허용·상관 키 비집계 |
| `notification_outbox_relay_scheduler_failed`, `notification_outbox_operation_failed` | ERROR; `failureCode`, `exceptionClass`, `occurredAt` | 허용 |
| `notification_cleanup_completed`, `notification_cleanup_failed` | INFO/WARN; `targetType`, batch·delete count, duration, 고정 failure 값 | 허용 |
| `chat_message_retention_completed`, `chat_message_retention_lease_guard_aborted`, `chat_message_retention_backlog_remaining`, `chat_message_retention_failed` | INFO/WARN/ERROR; count·duration·threshold·`exceptionClass` | 허용 |
| `chat_message_retention_room_failed`, `chat_message_retention_lock_skipped` | INFO/WARN; 고정 reason, `lockName`, `section`, `exceptionClass` | 허용 |
| `chat_realtime_publish_failed`, `chat_realtime_subscription_start_failed`, `chat_realtime_subscription_retry_schedule_failed` | WARN/ERROR; `eventType`, 단일 room/message 상관 키, `exceptionClass` | 허용·상관 키 비집계 |
| `chat_message_sender_nickname_missing` | ERROR; 단일 `roomId` | 허용·상관 키 비집계 |
| `room_state_reconciliation_completed`, `room_status_correction_batch_limit_reached`, `room_state_reconciliation_failed`, `room_status_correction_execution_slow`, `room_status_correction_skipped` | INFO/WARN; count·limit·duration·threshold·고정 reason | 허용 |
| `room_status_reconciliation_room_failed`, `room_state_reconciliation_lock_skipped` | DEBUG/WARN; 단일 `roomId`, `useCase`, `reasonCode`, `lockName` | WARN만 허용·상관 키 비집계 |
| `room_update_retry`, `room_cancel_retry`, `room_finish_retry`, `room_participation_retry`, `room_participation_cancel_retry`, `room_waitlist_cancel_retry`, `room_state_reconciliation_retry` | DEBUG/WARN; 단일 `roomId`, `attempt`, `useCase`, `reasonCode` | exhausted WARN만 허용·상관 키 비집계 |
| `room_created`, `room_updated`, `room_canceled`, `room_finished`, `room_participation_created`, `room_participation_canceled` | 현재 `actorUserId` 포함 | 필드 제거·회귀 검사 전 중앙 전송 금지 |
| `notification_outbox_operation_previewed`, `notification_outbox_operation_completed` | 현재 ID 배열·자유 입력 operator 필드 포함 | 전용 감사 경로를 설계하기 전 중앙 전송 금지 |

CloudWatch Logs subscription 또는 Agent 전처리는 `event` 허용 목록과 최소 level을 함께 적용한다. production에서 JSON parsing 실패, 허용 목록 밖 event, 금지 key 또는 한 실행에서 새로운 event·field가 발견되면 배포 검증을 실패시킨다. 운영 log group 보존기간은 14일이다.

### 기본 Logs Insights query

~~~text
fields @timestamp, level, event, service, role, instanceId, release, failureCode, durationMs
| filter level in ["WARN", "ERROR"]
| sort @timestamp desc
| limit 100
~~~

~~~text
fields @timestamp, event, processedCount, retryScheduledCount, failedCount, oldestProcessableAgeMs, durationMs, release
| filter event = "notification_outbox_relay_batch_completed"
| sort @timestamp desc
| limit 100
~~~

Query 결과를 Git에 원문으로 저장하지 않는다. 장기 증거는 금지 필드와 상관 키를 제거한 시각·release·query·판정 요약만 남긴다.

## Alarm·runbook matrix

모든 alarm은 `jiho`를 1차 담당자로 하고 SNS 실제 구독에서만 이메일 주소를 관리한다. `warning`과 `critical`, `OK` 복구를 전달하며 자동 restart·scale·rollback·데이터 재처리를 실행하지 않는다. 측정 전 값은 `후보`이며 SLA가 아니다.

| alarm·query | 기간·평가 | 등급·missing data | `PLANNED_STOP` | 복구·첫 조치 |
| --- | --- | --- | --- | --- |
| EC2 `StatusCheckFailed > 0` | 1분·2회 | critical·breaching | 억제 | instance·role 확인, 자동 재시작 금지, 2회 정상 뒤 OK |
| CPU credit `< 20` | 5분·1회 | warning·breaching | 억제 | CPU·요청량·role 비교, credit 회복 뒤 OK |
| host·container CPU 포화 | 5분·3회 후보 | warning·별도 수집 공백 alarm | 억제 | role·요청량·Tomcat busy 비교; 초기 측정 뒤 threshold 확정 |
| host memory·root disk `> 85%` | 10초·6회 | warning·breaching | 억제 | container/JVM 또는 volume 원인 분리, 85% 이하 뒤 OK |
| Spring·PostgreSQL·Redis health down | 1분·2회 | critical·breaching | 억제 | App1/App2·dependency를 분리, 정상 2회 뒤 OK |
| metric/log 마지막 성공 age 임계 초과 | 1분·2회 | warning·breaching | 억제 | Agent·OTLP·file·CloudWatch 순서 조사, 값 `0`으로 대체 금지 |
| API 5xx·p95/p99·Hikari pending | 5분·2회 후보 | critical 또는 warning·별도 수집 공백 alarm | 억제 | route→Tomcat→Hikari→DB/Redis 비교; 초기 측정 뒤 threshold 확정 |
| 알림 전달 p95 `> 30s`, oldest age `> 60s` | 1분·3회 | warning·별도 수집 공백 alarm | 억제 | [알림 런북](NOTIFICATION_OPERATIONS.md)으로 적체·retry·FAILED 확인 |
| chat·scheduler failure/backlog | 15분·1회 후보 | warning/critical·별도 수집 공백 alarm | 억제 | 고정 event와 DB 상태 확인, 다음 주기 수렴 검증 |
| 계획 종료 초과 | `plannedUntil` 즉시, 이후 24시간 | critical·schedule 자체 상태 감시 | 유지 | operator가 `ACTIVE` 복구 또는 7일 이내 명시 연장 |
| 상태 전환·alarm/schedule 검증 실패 | 작업 즉시 | critical·해당 없음 | 유지 | 추가 종료 금지, receipt 단계와 AWS API 재조회 |
| `PLANNED_STOP` 중 예상 밖 running resource·비용 | 5분·1회 | critical·notBreaching은 별도 heartbeat로 보완 | 유지 | stackId resource inventory와 비용 증가 확인, 임의 종료 금지 |
| 보안·IAM·감사 | 원천 정책 | critical 또는 warning·원천 정책 | 유지 | AWS 감사 경로를 따르며 가용성 alarm 억제와 묶지 않음 |

CloudWatch alarm의 `treat_missing_data`와 action 억제는 별개다. 현재 인프라 alarm은 missing data를 `breaching`으로 판정하되, 검증된 `PLANNED_STOP` 동안에만 `DisableAlarmActions`로 위 표의 억제 대상 action을 끈다. 상태가 ALARM으로 남아 있어도 `EnableAlarmActions` 뒤 기존 ALARM의 action 재실행에 의존하지 말고 통제 신호로 `OK → ALARM → OK`를 다시 검증한다.

## 전체 스택 계획 종료

### 종료 전 불변 조건

1. CLI가 AWS account·region·`environment`·`stackId`, 현재 `ACTIVE`, release와 실제 App1·App2·PostgreSQL·Redis resource를 확인한다.
2. 운영자가 UTC `plannedUntil`을 명시한다. 현재보다 뒤이고 7일 이내가 아니면 실패한다.
3. CLI가 actor ARN과 receipt ID를 확정하고 `PLANNED_STOP` state를 기록한다.
4. `plannedUntil` 즉시·이후 24시간마다 SNS로 보내는 stack 전용 EventBridge Scheduler schedule을 생성 또는 교체한다.
5. 억제 목록의 모든 alarm에 `DisableAlarmActions`를 적용한다. 초과·전환 실패·예상 밖 resource/비용·보안/IAM/감사 alarm은 건드리지 않는다.
6. SSM state·schedule·모든 alarm action 상태를 다시 읽어 정확히 일치하는지 확인한다.
7. 검증이 모두 끝난 뒤 App2 → App1 → Redis → PostgreSQL 순서로 종료한다. 각 단계의 대상·결과를 receipt에 남긴다.

3~6 가운데 하나라도 실패하면 resource 종료를 시작하지 않는다. 이미 바꾼 schedule·alarm action·state를 원래 `ACTIVE`로 되돌리고 재조회한다. rollback까지 검증되지 않으면 상태 전환 실패 `critical`을 보내고 명령을 실패시키며, 어떤 resource도 종료하지 않고 `ACTIVE`라고 선언하지 않는다.

종료 도중 일부 resource가 실패하면 자동으로 다른 resource를 생성·삭제하지 않는다. `PLANNED_STOP`을 유지하고 명령을 실패시키며, 살아 있는 resource는 예상 밖 running resource·비용 경고가 감지해야 한다.

## 재기동과 `ACTIVE` 복구

1. SSM state와 receipt를 읽고 대상 stack·release를 확인한다.
2. PostgreSQL → Redis → App2 → App1 순서로 시작한다. 각 data node health 뒤 다음 단계로 진행하고 App1 진입점은 마지막에 연다.
3. App1·App2 Spring readiness, PostgreSQL·Redis 연결, metric·log 도착과 release 일치를 확인한다.
4. 억제한 모든 alarm action을 `EnableAlarmActions`하고 재조회한다.
5. overdue schedule을 삭제하고 삭제 상태를 재조회한다.
6. 모든 검증 뒤 SSM state를 `ACTIVE`, `plannedUntil=null`로 기록하고 다시 읽는다.
7. 통제 신호로 대표 alarm의 `OK → ALARM → OK`와 SNS 경고·복구 수신을 확인해 receipt를 닫는다.

health가 성공했더라도 alarm action 활성화, schedule 삭제 또는 `ACTIVE` 기록·재조회가 실패하면 자동으로 서버를 다시 끄지 않는다. `ACTIVE`를 선언하지 않고 명령을 실패시키며 운영자가 같은 CLI 복구 절차를 수동 재시도한다. 실패 단계와 실제 켜진 resource를 상태 전환 실패·예상 밖 비용 경고가 계속 관측해야 한다.

## 배포·rollback·증거 계약

애플리케이션은 OTLP HTTP를 동일 호스트 전용 Docker bridge의 CloudWatch Agent에만 보내며 포트를 host·인터넷·다른 EC2에 publish하지 않는다. JSON log는 stdout과 Agent 전용 bind-mounted rolling file을 sink별 10MB × 5개로 제한한다. Agent·CloudWatch 장애는 제품 요청과 transaction을 실패시키지 않으며 회전으로 사라진 구간은 관측 공백으로 남긴다.

배포 검증 receipt에는 다음을 기록한다.

- `receiptId`, actor ARN, account·region·environment·stackId
- release SHA와 각 image digest, resource·role·instance ID
- 실행 시작·종료 UTC, 이전·목표·최종 state와 `plannedUntil`
- fixture 식별자, 기대 결과와 실제 Spring·PostgreSQL·Redis health
- metric·log 도착 query, alarm action 전후와 Scheduler ARN
- `OK → ALARM → OK` 시각과 SNS 실제 수신·복구 판정
- 실패 단계, 수동 조치, 관측 공백 시작·종료와 최종 판정

원문 CloudWatch log, 이메일 주소, secret, 사용자 데이터는 receipt와 Git에 넣지 않는다. 구현 rollback은 마지막 검증 release로 되돌린 뒤 같은 health·수집·alarm 검증을 반복한다. 문서 검사, Terraform plan, dashboard JSON과 screenshot만으로 배포·복구·실측을 통과로 기록하지 않는다.

## 구현 이후 명령 소유권

상태 조회·계획 종료·연장·재기동·복구 명령은 `albam-mate-infra`의 단일 운영 CLI가 소유한다. 구현 PR에서 실제 subcommand, 필수 인자, dry-run·exit code와 receipt 위치를 확정한 뒤에만 [COMMANDS](../COMMANDS.md)에 반복 명령을 추가한다. 이 문서는 구현 전 임의 shell 예시를 제공하지 않는다.

> 문서 관리: 소유자 `밤송이클럽 개발·운영 팀` · 최종 검증일 `2026-08-14` · 폐기 조건 `상태 전이·경고 대응 계약을 검증된 단일 운영 CLI의 생성형 문서가 완전히 대체할 때`
