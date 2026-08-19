# P2 운영 관측 런북

> **문서 상태: active · 계약 정본 · OPS-01 구현·AWS 실측 완료 · OPS-04 구현·로컬 검증 완료 · 최종 검증일: 2026-08-19**
>
> 이 문서는 `OPS-01`~`OPS-05`의 metric·log 허용 목록, 경고 대응과 전체 스택 계획 종료·재기동 계약을 소유한다. `OPS-01-AC1`~`AC3`은 [#730](https://github.com/bamsongi-club/albam-mate/issues/730), `OPS-01-AC4`~`AC7`은 [#731](https://github.com/bamsongi-club/albam-mate/issues/731)의 앱·운영 CLI·인프라와 AWS 수용 실행에서 검증됐다.

[P2 운영 관측 명세](../p2/monitoring.md)는 기능 규칙과 완료 기준, [운영 대시보드 정책](../p2/dashboard.md)은 화면·등급·비용 정책, [ADR-0071](../adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md)과 [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)는 전송 기술을 소유한다. 이 런북은 사용자 결정을 마친 [#713](https://github.com/bamsongi-club/albam-mate/issues/713)을 정본에 반영해 실제 구현·배포·장애 대응에 연결하며, 문서나 정적 설정만으로 구현·배포·실측 완료를 선언하지 않는다.

## 현재 상태와 적용 경계

| 구분 | 현재 판정 | 완료 증거 |
| --- | --- | --- |
| 이 문서의 metric·log·alarm·상태 전이 계약 | 확정 | 이 문서와 연결 정본의 링크·회귀 검사 |
| 애플리케이션 OTLP·JSON logging | `OPS-01-AC1`~`AC3` 구현·자동 검증 완료, OPS-02 HTTP·JVM·Tomcat·Hikari·Nginx timing 원천 범위 부분 구현·자동 검증, OPS-04 usage·cost-warning meter 구현·자동 검증 완료 | OPS-01 앱 [#764](https://github.com/bamsongi-club/albam-mate/pull/764), merge `0fa8285a019fafbb1d7caa65baa30cc8446e2c89`; OPS-02 production 설정·자동 검증; OPS-04 #852 소비 결과와 #872 로컬 통합 검증 |
| 인프라 수집·상태 정본·경고 제어·Scheduler | `OPS-01-AC1`~`AC7` 구현·자동 검증 완료, OPS-02 infra 미구현, OPS-04 dashboard·cost-warning alarm·비용 계산 구현·로컬 검증 완료 | `albam-mate-infra` [#14](https://github.com/bamsongi-club/albam-mate-infra/pull/14)·[#16](https://github.com/bamsongi-club/albam-mate-infra/pull/16)·[#17](https://github.com/bamsongi-club/albam-mate-infra/pull/17)·[#18](https://github.com/bamsongi-club/albam-mate-infra/pull/18)·[#19](https://github.com/bamsongi-club/albam-mate-infra/pull/19)·[#20](https://github.com/bamsongi-club/albam-mate-infra/pull/20)·[#22](https://github.com/bamsongi-club/albam-mate-infra/pull/22), main `ce8913c01937b7db71264008bd24a851a1c6d4d4`; OPS-04 별도 워크트리 로컬 검증 |
| AWS 배포와 실제 수집 | `OPS-01-AC1`~`AC7` 임시 배포·실측·철거 완료, OPS-02·OPS-04 미배포·미측정 | OPS-01 #730 앱 release `8e25bbc6ee2c1b68aa28247b9c2fdbf7b8e88784`, 아래 #730·#731 T1~T3와 Terraform teardown; OPS-02·OPS-04는 같은 release의 metric·log 도착과 수집 공백 검사 필요 |
| 경고·복구 | #731 OPS-01 범위 실측 완료, OPS-02·OPS-04 미측정 | OPS-01 대표 alarm `OK → ALARM → OK`, SNS 경고·복구 실제 수신, 최종 receipt `79bc6489-994a-4ba5-80ae-b43b075d8020`; OPS-02와 OPS-04 `$4` warning·복구 실측 필요 |

이 계약은 App1·App2·PostgreSQL·Redis로 구성한 하나의 `stackId` 전체에만 적용한다. 애플리케이션 배포, 한 컨테이너 재시작, rolling restart와 부분 유지보수는 `PLANNED_STOP`이 아니며 `ACTIVE` 상태와 배포 grace 안에서 관측한다.

### #731 AWS 수용 결과

- T1은 `ACTIVE → PLANNED_STOP → ACTIVE`, 16개 alarm action 억제·복구와 초과 schedule 생성·삭제를 AWS API와 receipt에서 재확인했다.
- T2는 App2 종료 뒤 App1 권한 거부를 주입해 부분 실패를 만들고 `PLANNED_STOP`·실제 resource·alarm 억제·schedule을 보존한 뒤 `recover`로 정상 복구했다.
- T3는 PostgreSQL → Redis → App2 → App1 순으로 동일 release를 재기동하고 health·metric·log 도착 뒤에만 `ACTIVE`를 선언했으며, 대표 alarm과 SNS 경고·복구 수신을 확인했다.
- 검증 뒤 Terraform P1 리소스 83개를 삭제했고 EC2·EBS·EIP·alarm·SNS 잔존 0개, 원격 P1 state 0개를 확인했다. receipt bucket과 bootstrap lock·state·ECR 자원은 감사·재실행 경계로 보존했다.

### #730 AWS 수용 결과

- T1은 management `9090`을 container loopback으로 유지하고 OTLP `4318`을 역할별 동일-host Docker bridge에만 열어 다른 EC2에서 접근할 수 없음을 확인했다. App1 Agent를 중단한 동안에도 제품 요청은 `200`이었고 Agent 복구 뒤 수집이 재개됐다.
- T2는 같은 release에서 PostgreSQL과 Redis를 각각 중단해 App1·App2의 dependency 신호가 `1 → 0 → 1`로 독립 전이하고, Spring·다른 dependency는 정상으로 분리되는 것을 CloudWatch와 container-local health에서 확인했다.
- T3는 App2 Java process 종료와 cgroup OOM을 주입해 restart count `0 → 1 → 2`, OOM `0 → 1`, Spring running 복구를 확인했다. host memory는 `InstanceId`·`Role`·`StackId` 차원으로 분리돼 container restart·OOM과 별도 신호로 유지됐다.
- 같은 release에서 앱 metric 401개 시계열·137개 metric 이름·43개 dimension 이름을 검사해 금지 dimension 0개를 확인했다. App1·App2 log 각 100건은 모두 JSON이었고 금지 key 0개였으며 원문 log와 사용자 식별자는 Git에 저장하지 않았다.
- 검증 뒤 Terraform P1 리소스 83개와 스택 전용 SecureString 9개를 삭제했다. Terraform state·EC2·EBS·EIP·alarm·log group·SNS·SSM 임시값은 모두 0개이며 bootstrap state·receipt·lock·ECR만 감사·재실행 경계로 보존했다.

[#730](https://github.com/bamsongi-club/albam-mate/issues/730)의 `OPS-01-AC1`~`AC3`과 [#731](https://github.com/bamsongi-club/albam-mate/issues/731)의 `OPS-01-AC4`~`AC7`을 합쳐 `OPS-01` 구현·자동 검증·임시 AWS 실측 완료로 판정한다. 이는 상시 운영 배포나 `OPS-02`~`OPS-05` 완료를 뜻하지 않는다.

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
| `receiptId` | 한 번의 상태 전이·연장·복구 기록을 연결하고 S3 감사 receipt prefix를 찾는 고유 ID |

`PLANNED_STOP`은 자동 연장하지 않는다. 운영자는 최대 7일 안에서 새 `plannedUntil`을 명시해 연장하고 상태·Scheduler·receipt를 모두 재검증해야 한다. 기한이 지나면 가용성 alarm action은 계속 억제하지만 `계획 종료 초과` `critical`을 즉시 보내고, `ACTIVE` 전환 또는 명시적 연장까지 24시간마다 반복한다.

### receipt 감사 저장소

receipt 본문은 SSM state와 분리한 전용 S3 bucket에 append-only object로 저장한다. `albam-mate-infra`는 bucket 이름을 `operations_receipt_bucket_name` output으로 제공하고 public access를 전부 차단하며 기본 암호화·versioning과 Object Lock `COMPLIANCE` 90일 보존을 설정한다. 90일이 지난 object만 lifecycle로 만료할 수 있고, Terraform state·Ansible 전송·부하 결과 bucket과 함께 사용하지 않는다.

object key는 `receipts/v1/{environment}/{stackId}/{receiptId}/{sequence}-{stage}.json`이다. `sequence`는 `000-requested`에서 시작해 단계마다 단조 증가하고 `999-final`로 끝난다. CLI는 `If-None-Match: *`로 새 key만 쓰며 기존 key·version을 덮어쓰거나 삭제하지 않는다. 각 object는 `schemaVersion`, `receiptId`, `sequence`, `stage`, `recordedAt`, actor·account·region·environment·stack·release, 이전 object key·SHA-256, 요청한 작업, AWS API 재조회 결과와 단계 판정을 포함한다. secret·이메일·사용자 데이터와 원문 log는 넣지 않는다.

- 운영 CLI writer에는 대상 prefix의 `s3:PutObject`, `s3:GetObject`와 제한된 `s3:ListBucket`만 부여한다. `DeleteObject`, retention 변경과 `s3:BypassGovernanceRetention`은 부여하지 않는다.
- 감사 reader는 별도 read-only role로 `ListBucket`, `GetObject`, `GetObjectVersion`만 사용한다. application EC2 role·GitHub Actions·일반 사용자 API는 receipt bucket 권한을 갖지 않는다.
- CLI는 상태·alarm·Scheduler·resource를 바꾸기 전에 `000-requested`를 쓰고 다시 읽어 version ID·ETag·본문 SHA-256을 확인한다. 각 변경 뒤에는 AWS API 재조회 결과를 다음 sequence에 append하고 같은 검증을 마친 뒤에만 다음 변경으로 진행한다.
- object 기록·재조회·hash chain 검증이 실패하면 상태 전환 실패로 처리하고 다음 변경을 실행하지 않는다. AWS 변경 뒤 기록이 실패한 경우 자동 성공이나 `ACTIVE`를 선언하지 않고 독립 SNS로 `critical`을 보낸 뒤, 운영자가 실제 AWS 상태와 마지막 검증 receipt를 기준으로 수동 복구한다.
- 과거 작업은 state의 최신 `receiptId`에만 의존하지 않는다. stack prefix를 나열해 sequence·version·Object Lock 보존과 `previousSha256` chain을 검증해야 하나의 전이 기록으로 인정한다.

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
| `http.server.requests` | timer·Spring MVC observation | `method`, 정규화 `uri`, `status`, `outcome` | 5분 count·p50·p95·p99·5xx 비율 | production histogram 설정·OTLP export 자동 검증 완료, CloudWatch 배포·실측 필요 |
| `jvm.memory.used`, `jvm.memory.max` | gauge·Micrometer JVM binder | `area`, 제한된 `id` | 1분 `Maximum`·used/max | production 설정·OTLP export 자동 검증 완료, CloudWatch 배포·실측 필요 |
| `jvm.gc.pause` | timer·Micrometer JVM binder | `action`, `cause`의 라이브러리 유한값 | 5분 count·p95 | meter 기반 있음·OTLP export 검증 필요, CloudWatch 배포·실측 필요 |
| `jvm.threads.live` | gauge·Micrometer JVM binder | 없음 | 1분 `Maximum` | production 설정·OTLP export 자동 검증 완료, CloudWatch 배포·실측 필요 |
| `tomcat.threads.busy`, `tomcat.threads.current`, `tomcat.threads.config.max` | gauge·Tomcat binder | connector `name`의 배포 고정값 | 1분 `Maximum`, busy/max | Spring Boot 4 connector binder·OTLP export 자동 검증, CloudWatch 실측 필요 |
| `hikaricp.connections.active`, `idle`, `pending`, `max`, `timeout` | gauge·counter·HikariCP binder | 고정 pool 이름 | 1분 `Maximum`·timeout `Sum` | production 설정·OTLP export 자동 검증 완료, CloudWatch 배포·실측 필요 |
| `albam.dependency.health` | gauge·앱 코드 | `dependency=postgresql|redis`; 값 `1=up`, `0=down`; 최초 known 결과 전과 probe timeout·중단·실행 예외는 missing 값을 유지 | 마지막 값과 2회 연속 down | 앱 코드·자동 검증 완료, OTLP/CloudWatch export·배포·실측 미확인 |
| `albam.telemetry.last_success_age` | gauge·Agent/infra 추가 구현 | `signal=metric|log`, 배포 dimension | 5분 `Maximum`; ACTIVE에서 임계 초과 | 추가 구현 필요 |

`frontend/nginx.production.conf`는 proxy 구간에 한해 raw URI·query·client 식별자 없이 `request_time`, `upstream_response_time`, `upstream_addr`를 구조화된 timing 원천으로 남긴다. 외부 응답의 `X-Albam-Mate-Upstream`은 backend가 검증한 `app1` 또는 `app2` 역할만 전달하고 raw 주소를 합성하지 않는다. 내부 `upstream_addr`는 CloudWatch dimension으로 직접 사용하지 않고, private infra가 배포 manifest와 대조해 유한한 App1·App2 `role`로 변환해야 한다. Agent 변환·CloudWatch 배포·실측은 아직 완료 증거가 아니다.

`meter 기반 있음`은 Actuator·Micrometer binder를 사용할 수 있다는 뜻이며 CloudWatch 도착이나 percentile 존재를 뜻하지 않는다. 실제 OTLP 이름 변환이 생기면 CloudWatch의 최종 이름을 구현 PR에서 이 표와 alarm query에 함께 고정한다.

### 현재 생산 코드의 domain meter

source는 첫 두 meter가 `AuthenticationRequestLimiterMetrics`, WebSocket 네 meter가 `ChatWebSocketMetrics`, message delivery 두 meter가 `ChatMessageCommittedListener`, 채팅 업무 결과 meter가 `ChatMessageMetrics`, retention 여덟 meter가 `ChatMessageRetentionMetrics`다. `추가 구현 필요` meter는 각각 notification relay, ROOM status correction과 waitlist module이 생산하고 도메인 코드가 CloudWatch SDK에 의존하지 않도록 `MeterRegistry`까지만 소유한다.

| 이름 | type | 허용 tag 값 | 사용 query·용도 | 상태 |
| --- | --- | --- | --- | --- |
| `auth.request.limiter.rejections` | counter | `family=ip|failure`, `reason=capacity_saturated|redis_unavailable` | 5분 `Sum`·인증 거절 원인 | 현재 코드·export 필요 |
| `auth.request.limiter.capacity.utilization` | gauge | `family=ip|failure` | 5분 `Maximum`·용량 warning 후보 | 현재 코드·export 필요 |
| `chat.websocket.connections.active` | gauge | 없음 | App별 `Maximum` | 현재 코드·export 필요 |
| `chat.websocket.delivery.latency` | timer | 없음 | 5분 count·p95 | 현재 코드·export 필요 |
| `chat.websocket.delivery.failures` | counter | 없음 | 5분 `Sum` | 현재 코드·export 필요 |
| `chat.websocket.recovery.messages` | counter | 없음 | 5분 `Sum`·복구 확인 | 현재 코드·export 필요 |
| `chat.message.delivery.duration` | timer | 없음 | 5분 count·p95 | 현재 코드·export 필요 |
| `chat.message.delivery.failures` | counter | 없음 | 5분 `Sum` | 현재 코드·export 필요 |
| `chat.message.operations` | counter | `outcome=accepted|rejected|failed` | 배포 fixture별 `Sum`·저장 업무 결과 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `chat.message.retention.lock.skipped` | counter | 없음 | 1시간 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.rooms.purged` | counter | 없음 | 1일 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.messages.deleted` | counter | 없음 | 1일 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.failures` | counter | 없음 | 15분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.lease.guard.aborted` | counter | 없음 | 15분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.backlog.remaining` | counter | 없음 | 15분 `Sum` | 현재 코드·export 필요 |
| `chat.message.retention.execution.duration` | timer | 없음 | 실행별 p95·max | 현재 코드·export 필요 |
| `chat.message.retention.delay` | timer | 없음 | 실행별 p95·max | 현재 코드·export 필요 |
| `notification.relay.events` | counter | `outcome=processed|retry_scheduled|failed` | 1분 `Sum`·최종 전달 결과 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `notification.relay.delivery.duration` | timer | 없음 | 1분 p95·알림 전달 30초 기준 | 현재 코드·export 필요, CloudWatch 배포·실측 필요 |
| `notification.relay.oldest.processable.age` | gauge | 없음 | 1분 `Maximum`·60초 3회 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `room.status.correction.runs` | counter | `outcome=completed|failed|skipped|batch_limit` | 15분 `Sum`·보정 결과 | `completed|failed|batch_limit` 현재 코드·export 필요, `skipped` 추가 구현 필요 |
| `room.status.correction.duration` | timer | 없음 | 실행별 p95·180초 warning | 현재 코드·export 필요, CloudWatch 배포·실측 필요 |
| `room.waitlist.operations` | counter | `operation=join|cancel|promote`, `outcome=accepted|rejected|failed` | 배포 fixture별 `Sum`·최종 업무 결과 | 현재 코드·H2·PostgreSQL 자동 검증 완료, CloudWatch 배포·실측 필요 |
| `assistant.usage.events` | counter | `provider=fake|openai|unknown`, `model=gpt-5.6-luna|unknown`, `feature=AI-02|unknown`, 승인된 `prompt_version`·`schema_version`·`status` 또는 `unknown` | 같은 release의 요청 수를 provider·model·feature·status별 `Sum` | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `assistant.usage.tokens` | distribution summary | 위 usage tag와 `token_type=input|output|total` | 같은 release의 token 합계와 공식 가격 snapshot 기반 추정 비용 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `assistant.usage.latency` | timer | usage event와 같은 유한 tag | provider·model·feature별 count·p95 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `assistant.usage.cost.usd` | distribution summary | usage event와 같은 유한 tag | provider adapter가 보고한 참고 비용; 공식 가격 재계산이나 청구서로 사용 금지 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |
| `assistant.cost.warning.events` | counter | `quota_month=YYYY-MM`, `warning_threshold_usd=4.00|unknown` | 월별 중복 없는 `$4` warning, SNS warning·OK 복구 | 현재 코드·자동 검증 완료, CloudWatch 배포·실측 필요 |

`notification.relay.delivery.duration`은 outbox의 `recordedAt`부터 Notification 기록 시각까지의 `deliveryDelayMs`를 기록한다. `notification.relay.oldest.processable.age`는 batch 종료 뒤 PostgreSQL 조회의 밀리초 값을 초 단위 gauge로 기록하고, 처리 가능한 적체가 없으면 0이다. `processingDurationMs`는 구조화 로그의 진단 필드일 뿐 meter에 기록하지 않는다.

### OPS-04 가격·비용 계산과 경고 경계

OpenAI `gpt-5.6-luna` standard short-context 가격은 [OPS-04 가격 snapshot](../measurements/ops-04/README.md)에 고정한다. 입력 USD 0.20/1M token과 출력 USD 1.20/1M token으로 `assistant.usage.tokens`를 독립 재계산하며, dashboard 값은 실제 청구서가 아닌 추정값이다. cached input을 분리하지 못하거나 요청별 input이 272,000 token을 넘으면 임의의 요율이나 비용 `0`을 적용하지 않고 `NO_OBSERVATION`으로 남긴다.

#872 승인 T1의 `outcome` 축은 #852가 고정한 실제 bounded tag `status`로 조회한다. OPS-04가 같은 의미의 `outcome` tag를 중복 추가하거나 공유 meter 계약을 확장하지 않는다.

비공개 인프라의 `${project_name}-${stack_id}-ops04` dashboard는 같은 `release`의 요청 수, input/output/total token, 공식 snapshot 추정 비용과 `$4` warning 신호를 표시한다. cost-warning alarm은 SNS warning과 OK action을 모두 가지며, 예상 밖 비용 신호이므로 `PLANNED_STOP` alarm 억제 목록에는 넣지 않는다. 통제된 `OK → ALARM → OK`는 별도 alert-cycle 허용 목록과 receipt로 검증한다. 정적 dashboard·alarm 구현은 AWS metric 도착, 이메일 수신, 복구와 teardown을 증명하지 않는다.

관측 자체의 월 비용은 기존 host/application 관측을 제외한 OPS-04 증분만 계산한다. release별 provider 하나와 현재 유한 status 조합을 전제로 31일·60초 export, 최대 128개 bounded series, export당 series별 최대 2 datapoint, datapoint당 600 bytes, OTel USD 0.50/GB, 유료 dashboard 1개 USD 3, standard alarm 1개 USD 0.10을 보수적으로 적용한 현재 추정은 월 USD 6.53이다. 128개는 무제한 cardinality 예측이 아니라 배포 중단 상한이다. series·datapoint·가격 가정 중 하나라도 넘거나 누락되면 `$10 이하`로 간주하지 않고 재계산·재승인을 요구한다. OPS-04는 새 중앙 log group을 만들지 않아 증분 log 비용은 0으로 계산한다.

채팅 보존의 복구 판정은 앱 인스턴스 메모리나 domain meter가 합성하지 않는다. release 전체의 `failures`와 `completed` 신호를 함께 평가하는 비공개 infra alarm이 소유하며, 그 alarm 구현·배포·실측은 미완료다.

`notification.relay.events`부터 `room.waitlist.operations`까지의 여섯 meter는 현재 구조화 log·업무 결과에 값이 있거나 검증 경계가 있지만 지속 alarm·업무 결과용 meter는 없는 항목의 구현 이름을 고정한다. 구현 중 다른 이름이나 Logs metric filter가 더 적합하다고 판단하면 코드만 다르게 만들지 않고 이 inventory와 alarm query를 같은 변경에서 갱신한다.

## 중앙 log 허용 목록

### 공통 필드

허용 공통 필드는 UTC `timestamp`, `level`, 고정 `event`, `environment`, `stackId`, `service`, `role`, `instanceId`, `release`, 서버 확정 `requestId`다. event별 수치·enum 필드는 아래 목록에서만 추가한다.

- 수치: 고정 event가 정의한 `*Ms`, `*Millis`, `*Count`, `*Limit`, `attempt`, `batchNumber`
- 유한 enum: `failureCode`, `reasonCode`, event별 `exceptionClass` 또는 `exceptionType`, `eventType`, `targetType`, `action`, `outcome`, `dependency=postgresql|redis`, `roomStatus`, `useCase`, `section`, `lockName`
- UTC 시각: `measurementTime`, `occurredAt`, `outboxRecordedAt`, `notificationRecordedAt`, `nextAvailableAt`
- 접근 제한 상관 키: 단일 `roomId`, `messageId`, `sourceEventId`, `gameId`; metric dimension·dashboard group·alarm dimension에는 사용하지 않는다.
- event별 boolean은 `notification_outbox_relay_event_failed` 전용 boolean `deterministicFailure`만 허용한다. `true`는 이번 실패가 결정적 또는 보존 기간 만료로 자동 재시도 대상이 아니며 최종 실패로 격리됐음을, `false`는 그렇지 않음을 뜻한다. 다른 event나 metric dimension에는 넣지 않는다.

이메일·IP·사용자 ID·`actorUserId`, session·cookie·token·secret, request/response body, prompt/response 원문, Tool 인자·결과, 채팅 내용, 알림 payload, 원본 SQL, 예외 message·stack trace 전문은 중앙 전송을 금지한다. `sourceEventIds` 같은 ID 배열과 자유 입력 `requestedBy`·`reasonReference`도 제외한다. 정상 2xx·4xx access log 전체는 전송하지 않는다.

### event inventory

| event | level·허용 필드 | 중앙 전송 상태 |
| --- | --- | --- |
| `notification_outbox_relay_batch_completed` | INFO; 처리·retry·failure count, `durationMs`, `oldestProcessableAgeMs` | 허용 |
| `notification_outbox_relay_event_processed` | INFO; 단일 `sourceEventId`, `eventType`, count·시각·지연 | 허용·상관 키 비집계 |
| `notification_outbox_relay_retry_scheduled` | WARN; 단일 `sourceEventId`, 고정 failure 값·count·다음 시각 | 허용·상관 키 비집계 |
| `notification_outbox_relay_event_failed` | WARN; 단일 `sourceEventId`, 고정 failure 값·count, 전용 boolean `deterministicFailure` | 허용·상관 키 비집계 |
| `notification_outbox_relay_scheduler_failed`, `notification_outbox_operation_failed` | ERROR; `failureCode`, `exceptionClass`, `occurredAt` | 허용 |
| `http_request_failed` | ERROR; `failureCode=HTTP_SERVER_ERROR`, 서버 확정 `requestId` | 허용 |
| `dependency_health_changed` | WARN/INFO; `dependency=postgresql|redis`, `outcome=down|recovered`, down일 때만 고정 `failureCode` | 허용 |
| `notification_cleanup_completed`, `notification_cleanup_failed` | INFO/WARN; `targetType`, batch·delete count, duration, 고정 failure 값 | 허용 |
| `chat_message_retention_completed`, `chat_message_retention_lease_guard_aborted`, `chat_message_retention_backlog_remaining`, `chat_message_retention_failed` | INFO/WARN/ERROR; count·duration·threshold·`exceptionClass` | 허용 |
| `chat_message_retention_room_failed`, `chat_message_retention_lock_skipped` | INFO/WARN; 고정 reason, `lockName`, `section`, `exceptionClass` | 허용 |
| `chat_realtime_publish_failed` | WARN; `eventType`, 단일 `roomId`·`messageId`, `exceptionType` | 허용·상관 키 비집계 |
| `chat_realtime_subscription_start_failed`, `chat_realtime_subscription_retry_schedule_failed` | WARN; `retryDelayMillis`, `exceptionType` | 허용 |
| `chat_message_sender_nickname_missing` | ERROR; 단일 `roomId` | 허용·상관 키 비집계 |
| `room_state_reconciliation_completed`, `room_status_correction_batch_limit_reached`, `room_state_reconciliation_failed`, `room_status_correction_execution_slow`, `room_status_correction_skipped` | INFO/WARN; count·limit·duration·threshold·고정 reason | 허용 |
| `room_status_reconciliation_room_failed`, `room_state_reconciliation_lock_skipped` | DEBUG/WARN; 단일 `roomId`, `useCase`, `reasonCode`, `lockName` | WARN만 허용·상관 키 비집계 |
| `room_update_retry`, `room_cancel_retry`, `room_finish_retry`, `room_participation_retry`, `room_participation_cancel_retry`, `room_waitlist_cancel_retry`, `room_state_reconciliation_retry` | DEBUG/WARN; 단일 `roomId`, `attempt`, `useCase`, `reasonCode` | exhausted WARN만 허용·상관 키 비집계 |
| `room_created`, `room_updated`, `room_canceled`, `room_finished`, `room_participation_created`, `room_participation_canceled` | 현재 `actorUserId` 포함 | 필드 제거·회귀 검사 전 중앙 전송 금지 |
| `notification_outbox_operation_previewed`, `notification_outbox_operation_completed` | 현재 ID 배열·자유 입력 operator 필드 포함 | 전용 감사 경로를 설계하기 전 중앙 전송 금지 |
| `game_search_completed` | INFO; `outcome=success`, `resultCount`, `durationMs` | 허용 |
| `game_detail_completed` | INFO; 단일 `gameId`, `outcome=success`, `durationMs` | 허용·상관 키 비집계 |
| `game_played_state_changed` | INFO; 단일 `gameId`, `action=mark|unmark`, `outcome=played|not_played` | 허용·상관 키 비집계 |
| `game_search_failed`, `game_detail_failed`, `game_played_state_change_failed` | 예상 사용자 거절은 INFO·`outcome=rejected`·`failureCode`; 기술 실패는 WARN/ERROR·`outcome=failed`·`failureCode`·`exceptionClass`; 상세·상태 변경만 단일 `gameId`, 상태 변경은 `action=mark|unmark` | 허용·상관 키 비집계 |

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

일반 API의 60초 timeout은 Nginx가 504를 확정하므로 애플리케이션이 `HTTP_TIMEOUT` 로그나 meter를 합성하지 않는다. Nginx timing과 중앙 수집을 연결한 timeout 경고·복구 검증은 비공개 infra alarm 소유의 미완료 항목이다.

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

각 항목은 [receipt 감사 저장소](#receipt-감사-저장소)의 단계별 object와 hash chain으로 남긴다. 원문 CloudWatch log, 이메일 주소, secret, 사용자 데이터는 receipt와 Git에 넣지 않는다. 구현 rollback은 마지막 검증 release로 되돌린 뒤 같은 health·수집·alarm 검증을 반복한다. 문서 검사, Terraform plan, dashboard JSON과 screenshot만으로 배포·복구·실측을 통과로 기록하지 않는다.

## 구현 이후 명령 소유권

상태 조회·계획 종료·연장·재기동·복구 명령은 [`albam-mate-infra`](https://github.com/bamsongi-club/albam-mate-infra/tree/ce8913c01937b7db71264008bd24a851a1c6d4d4)의 단일 운영 CLI가 소유한다. 반복 subcommand와 필수 인자는 [COMMANDS](../COMMANDS.md#운영-compose)에 등록하며, 세부 bootstrap·배포·receipt 절차는 인프라 저장소 README를 따른다.

> 문서 관리: 소유자 `밤송이클럽 개발·운영 팀` · 최종 검증일 `2026-08-18` · 폐기 조건 `상태 전이·경고 대응 계약을 검증된 단일 운영 CLI의 생성형 문서가 완전히 대체할 때`
