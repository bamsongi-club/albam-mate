# P2 운영 대시보드 정책

> **문서 상태: active · 정책 값 사용자 확인 완료 · 정본 승격일: 2026-08-13**
>
> 이 문서는 화면 배치보다 먼저 대시보드가 답할 질문과 판정 근거를 정의하는 P2 운영 정책 정본이다. 세부 정책 값과 전송 ADR은 확정했지만 구현·배포·실측 상태는 별도로 판정한다.

## 대시보드의 책임

P2 대시보드는 CloudWatch에 그래프를 나열하는 화면이 아니다. 운영자와 개발자가 같은 release·UTC 구간에서 다음 질문에 순서대로 답하도록 돕는다.

| 순서 | 질문 | 판정에 필요한 관측 | 연결 기능 |
| --- | --- | --- | --- |
| 1 | 서비스가 살아 있는가 | health, EC2·container·Spring, PostgreSQL·Redis 연결, 마지막 수집 시각 | `OPS-01` |
| 2 | 느려졌는가 | API p50·p95·p99, Nginx upstream, Tomcat·HikariCP, 외부 API·AI 응답시간 | `OPS-02` |
| 3 | 실패하는가 | 5xx·timeout, dependency·scheduler·AI·Tool Calling 오류 | `OPS-03` |
| 4 | 돈을 많이 쓰는가 | AI 요청·token·provider·model별 사용량·추정 비용, metric·log 수집량 | `OPS-04` |
| 5 | 기능이 실제로 동작하는가 | 알림·채팅·참가 대기열의 기술 수락·업무 결과·사용자 가시 결과 | `OPS-05` |

P2는 다섯 질문을 모두 지원한다. AI 결과가 유용한지, 근거가 적절한지와 사용자 만족도는 별도 제품 품질 평가로 남긴다.

## 판정 순서

~~~text
생존·연결 확인
→ 지연·포화 확인
→ 실패 종류와 영향 범위 확인
→ 사용량·추정 비용 확인
→ 핵심 업무 기능의 최종 결과 확인
→ 구조화 로그·런북으로 다음 조치 결정
~~~

상위 단계가 정상이어도 하위 단계는 실패할 수 있다. 예를 들어 Spring health가 `UP`이고 HTTP가 `200`이어도 알림이 inbox에 기록되지 않거나 채팅이 저장 후 전달되지 않으면 `OPS-05` 관점에서는 기능 장애다.

## 화면 구성

P2는 같은 지표·로그를 재사용하는 CloudWatch dashboard 두 개를 만든다.

| 화면 | 주된 사용자·상황 | 책임 |
| --- | --- | --- |
| 요약 대시보드 | 최초 경고 확인, 운영 상태 공유와 발표 | 생존·지연·실패·비용·기능 동작의 현재 판정과 `warning`·`critical` 요약 |
| 상세 대시보드 | 개발자·운영자의 장애 원인 분석 | App1·App2부터 JVM·DB·Redis·scheduler·AI·도메인 작업까지 원인 계층 비교 |

두 화면 때문에 metric·log를 중복 수집하지 않는다. 요약 panel은 상세 panel 또는 정해진 Logs Insights query와 런북으로 이동할 수 있어야 한다.

지표 전송은 AI 코딩 에이전트가 아니라 EC2에 설치되는 Amazon CloudWatch Agent가 담당한다. 애플리케이션은 CloudWatch API를 직접 호출하지 않고 [ADR-0071](../adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md)에 따라 Spring Micrometer metric을 동일 호스트 전용 Docker bridge의 OTLP HTTP로 Agent에 전달한다.

운영 로그는 [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)에 따라 Spring Boot Logstash 한 줄 JSON을 stdout과 Agent 전용 rolling file에 함께 기록한다. Docker `json-file`과 전용 file은 각각 10MB × 5개로 sink별 최대 50MB, 두 sink 합계는 Spring container별 최대 100MB 이내로 회전한다. host 전체 용량은 Spring container 수에 따른 이 합계와 다른 container·host log를 별도로 더해 산정한다. host Agent는 bind-mounted 전용 file의 허용 event만 CloudWatch Logs로 전송한다. Agent 장애는 사용자 기능을 실패시키지 않으며 회전으로 유실된 구간은 관측 공백으로 표시한다.

### 요약 대시보드

#### 1. 현재 상태 요약

- 현재 `environment`, `stackId`, `release`, App1·App2와 데이터 node
- 현재 운영 상태 `ACTIVE` 또는 `PLANNED_STOP`과 선언한 시작·종료 시각
- EC2·container·Spring·PostgreSQL·Redis 상태
- 마지막 metric·log 수집 시각과 관측 공백
- 현재 `warning`·`critical` 경고와 최근 복구

#### 2. 요청 지연과 포화

- 정규화 route별 요청량과 p50·p95·p99
- Nginx request·upstream response time과 App1·App2 분포
- container CPU·memory, JVM heap·GC, Tomcat busy, HikariCP pending
- PostgreSQL connection·waiting lock과 Redis latency
- 배포된 외부 API·AI feature별 응답시간

#### 3. 실패와 이상

- 5xx·timeout과 고정 failure code
- PostgreSQL·Redis 오류와 Hikari acquisition timeout
- scheduler 실패·slow·lock skip·상한 도달
- AI provider·fallback·Tool Calling 성공·실패
- 수집 공백과 경고 전달 실패

#### 4. 사용량과 추정 비용

- provider·model·feature별 AI 요청과 입력·출력 token
- success·fallback·failure별 사용량
- 실제 외부 provider 호출 수 × USD `0.10`의 고정 예약 비용과 앱 월 `$5` cap·`$4` warning 사용량
- 공식 가격표 snapshot을 적용한 token 기반 참고 추정 비용
- CloudWatch custom metric 수와 log ingestion·보존량

고정 예약 비용은 앱 내부 예산 통제값이고 실제 AI provider 청구서가 아니며, token 기반 참고 추정값도 청구 확정액이 아니다. 두 값을 같은 값으로 합치지 않고, 참고 추정에는 통화·가격 적용일·계산식을 표시한다. 이 화면을 실제 AWS·AI provider 청구서로 표현하지 않는다.

배포 검증 전에 공식 provider 가격과 버전 관리 snapshot을 비교하고 변경됐을 때만 `jiho`가 갱신한다. 가격 snapshot은 USD `0.10` 고정 예약값의 적정성 재검토와 참고 추정에만 쓰며, 앱 월 `$5` cap을 token 가격으로 다시 계산하지 않는다. 과거 실행 결과는 당시 snapshot 식별자를 유지한다.

P2가 추가하는 애플리케이션 OTLP metric·중앙 로그·신규 alarm의 예상 월 비용은 USD 10 이하로 제한하고 기존 host 관측 비용은 별도로 표시한다. 초과하면 수집 간격·label·로그 범위를 줄이거나 사용자 재승인을 받기 전까지 비용 검증을 통과로 기록하지 않는다.

#### 5. 핵심 업무 기능 결과

| 기능 | 기술 수락 | 업무 결과 | 사용자 가시 결과 |
| --- | --- | --- | --- |
| 알림 | 원인 event·Outbox 처리 시작 | Notification 기록 | inbox 조회와 전달 지연 목표 확인 |
| 채팅 | 메시지 전송 요청 수락 | PostgreSQL 저장 | 실시간 전달 또는 재연결 이력 복구 |
| 참가 대기열 | 참가·취소 요청 수락 | 대기 등록·취소·FIFO 승격 | 후속 조회에서 상태 전이와 불변식 확인 |

각 기능은 attempt, business success, business rejection과 technical failure를 분리한다. 업무 거절을 5xx와 합치지 않고, 세부 자원 ID는 metric label이 아니라 접근 제한된 조사 로그에서만 사용한다.

### 상세 대시보드

- App1·App2 container, JVM, GC, Tomcat과 HikariCP
- PostgreSQL connection·waiting lock과 Redis memory·client·latency
- Nginx upstream과 route별 요청 분포
- scheduler 실패·slow·lock skip·상한 도달
- AI provider·model·feature·Tool별 지연·실패·사용량
- 알림·채팅·참가 대기열 세부 결과와 연결된 Logs Insights query·런북

## 기능 동작을 증명하는 방식

대시보드만 바라보는 수동 확인은 재현 가능한 완료 증거가 아니다. 다음 두 증거를 조합한다.

1. 수동·실사용 요청에서 계속 누적되는 수동적 업무 결과 metric
2. 고정 release와 격리 fixture에서 정상·업무 거절·기술 실패·복구를 재현하는 통제 시나리오

통제 시나리오는 사용자 데이터와 운영 ROOM을 사용하지 않는다. 실행 시각, release, 입력 fixture, 기대한 업무 결과, 실제 저장·조회·metric·log·alarm 변화를 manifest로 남긴다.

기능 통제 시나리오는 상시 주기 실행하지 않고 각 배포의 운영 검증 단계에서 한 번 실행한다. 실제 사용자 데이터와 섞이지 않는 fixture를 사용하고, 기술 수락·업무 결과·사용자 가시 결과 중 하나라도 실패하면 해당 배포의 기능 동작 검증을 통과로 기록하지 않는다. 자동 rollback은 이 문서의 책임이 아니다.

## 경고 정책

- `critical`: 핵심 요청이나 알림·채팅·대기열의 최종 업무 결과를 완료할 수 없는 상태
- `warning`: 처리는 이어지지만 지연·포화·backlog·비용 증가 또는 관측 공백을 확인해야 하는 상태
- `warning`·`critical`과 `OK` 복구는 CloudWatch Alarm에서 SNS 이메일로 실제 전달한다.
- 이메일 주소는 문서와 Git에 기록하지 않고 SNS 구독에서만 관리하며, 24시간 당직·야간 대응은 운영하지 않는다.
- 모든 P2 경고의 1차 대응 담당자는 `jiho`로 지정하고 별도 백업 당직은 두지 않는다.
- 모든 경고는 실제 수신 경로, 한 명의 1차 담당자, dashboard·log query와 런북을 갖는다.
- 경고는 자동 restart·rollback·scale, 데이터 재처리와 구조 변경을 실행하지 않는다.
- 최소 한 번은 통제 시나리오로 `OK → ALARM → OK`와 실제 경고·복구 수신을 확인한다.
- 운영을 선언한 `ACTIVE` 시간의 missing data는 경고한다. `PLANNED_STOP`은 App1·App2·PostgreSQL·Redis 전체 스택의 명시적 비용 절감 종료에만 사용하고 `plannedUntil`은 필수·최대 7일이며 연장은 운영자가 다시 선언한다.
- `PLANNED_STOP`에서는 EC2·Spring·PostgreSQL·Redis 생존, metric·log 수집 공백과 CPU·memory·disk 등 중단 때문에 값이 사라지는 alarm action만 억제한다. 계획 종료 초과, 상태 전환 실패, 예상 밖 running resource·비용, 보안·IAM·감사 경고는 유지한다.
- `plannedUntil`을 넘기면 가용성 alarm action은 계속 억제하되 `계획 종료 초과` `critical`을 즉시, `ACTIVE` 복구 또는 명시적 연장까지 24시간마다 반복한다.
- 배포·재기동 절차는 Spring health와 PostgreSQL·Redis 연결에 성공한 뒤에만 `ACTIVE`로 전환하고 이메일 경고를 활성화한다.
- 계획 종료 절차는 별도 SSM 상태, 초과 Scheduler와 alarm action 상태를 기록·재조회한 뒤 서버를 종료한다. 하나라도 실패하면 종료를 중단하고 `ACTIVE`를 유지하며 명령을 실패시킨다.
- 재기동 health 뒤 alarm action 활성화·초과 schedule 삭제·`ACTIVE` 기록 중 하나라도 실패하면 서버를 자동 종료하거나 `ACTIVE`라고 선언하지 않는다. 운영자가 [운영 관측 런북](../guides/MONITORING_OPERATIONS.md#재기동과-active-복구)의 복구 절차를 수동 재시도한다.
- 기존 host memory·disk 85%, CPU credit 20과 알림 전달 p95 30초·oldest processable age 60초 3회 연속 기준은 재사용한다.
- API p95·p99, 5xx 비율, HikariCP pending, AI 오류율·예상 비용은 정상·통제 장애 측정 뒤 P2 초기 운영값으로 확정하고 SLA로 표현하지 않는다.

## 완료 증거

대시보드 완료 기록에는 다음을 남긴다.

- release SHA와 배포 대상·시각
- 정상·장애·복구 입력과 기대 신호
- 관련 dashboard 구간과 metric·log query
- 알림·채팅·대기열의 실제 저장·조회 결과
- 경고 상태 전이와 실제 수신 결과
- AI 가격표 snapshot·계산식과 관측 수집량
- 발견한 인사이트, 허용된 조치와 후속 Issue

정적 Terraform plan, dashboard screenshot, 테스트 코드 성공과 metric 이름 존재는 각각 구현·배포·실측을 대신하지 않는다.

중앙 운영 로그는 CloudWatch Logs에서 14일 보존 후 자동 삭제한다. 원문 로그는 Git에 저장하지 않고, 장기 보존할 장애 증거는 민감정보를 제거한 요약과 재현 manifest로 제한한다.

정상 2xx·4xx access log 전체는 중앙 전송하지 않는다. 요청 수·지연·status 분포는 metric으로 확인하고, 중앙 로그는 `WARN`·`ERROR`, 알림·채팅·참가 대기열의 핵심 업무 event와 배포 검증 event만 수집한다.

## 정책 결정 상태

P2 정책에 필요한 선택은 모두 사용자 확인을 마쳤다. 아래 표는 값을 반복하지 않고 각 정책의 소유 문서와 남은 정본화 작업만 보여준다.

| 정책 묶음 | 상태 | 상세 위치·남은 작업 |
| --- | --- | --- |
| 기능 동작 검증 시점·fixture | 사용자 확인 완료 | [기능 동작을 증명하는 방식](#기능-동작을-증명하는-방식) |
| 경고 등급·이메일·담당자·운영 상태·초기 임계값 | 사용자 확인·정본 반영 완료 | [경고 정책](#경고-정책), [운영 관측 런북](../guides/MONITORING_OPERATIONS.md) |
| 요약·상세 대시보드 구성 | 사용자 확인 완료 | [화면 구성](#화면-구성) |
| AI 예상 비용·가격표 갱신·관측 비용 상한 | 사용자 확인 완료 | [사용량과 추정 비용](#4-사용량과-추정-비용), [완료 증거](#완료-증거) |
| 중앙 로그 범위·14일 보존 | 사용자 확인·정본 반영 완료 | [완료 증거](#완료-증거), [중앙 log 허용 목록](../guides/MONITORING_OPERATIONS.md#중앙-log-허용-목록) |
| 메트릭·로그 전송 경계 | Platform ADR 승인·계약 반영·미검증 | [ADR-0071](../adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md), [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md), [결정 위치와 구현 경계](monitoring.md#결정-위치와-구현-경계) |
| 실제 이메일 주소·AI provider·model | 이 문서의 결정 대상 아님 | SNS 구독과 AI 기능 명세·배포 설정에서 관리 |

현재 계약·구현·검증·배포 상태는 [P2 기능 상태 정본](README.md#기능별-현재-상태)만 갱신한다.
