# P2 운영 관측 명세

> **문서 상태: active · 정본 승격일: 2026-08-13**
>
> 이 문서는 P1에서 조건부 후속으로 예약한 운영 관측을 P2의 `OPS-01`~`OPS-05`로 구체화한 기능 정본이다. 문서 활성화와 메트릭·로그 전송 ADR·운영 계약 승인만으로 구현·배포·실측이 완료되지는 않으며, 기능별 상태표에서 그 축을 따로 판정한다.

이 문서는 `OPS-01`~`OPS-05`의 기능 규칙, 완료 기준과 제외 범위를 정의한다. 현재 계약 준비·생산 코드·자동 검증·운영 배포와 실측 상태는 [P2 기능 상태 정본](README.md#기능별-현재-상태)에서만 판정한다.

P2 전체 범위와 공통 운영 흐름은 [P2 공통 명세](../P2-spec.md), 화면·경고·비용·배포 검증 정책은 [운영 대시보드 정책](dashboard.md)이 관리한다. 실제 metric·log 허용 목록, alarm matrix와 전체 스택 계획 종료·재기동 절차는 [P2 운영 관측 런북](../guides/MONITORING_OPERATIONS.md)이 소유한다. 메트릭 전송은 [ADR-0071](../adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md), 구조화 로그 전송·보존은 [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)가 소유하며, 이 문서가 ADR이나 구현 증거를 대신하지 않는다.

## 목표와 지원 수준

P2 대시보드는 인프라가 켜져 있다는 사실만 보여주지 않는다. 다음 다섯 질문에 실제 배포 데이터와 재현 가능한 검증 증거로 답해야 한다.

| 기능 ID | 운영 질문 | 최종 지원 범위 |
| --- | --- | --- |
| `OPS-01` | 서비스가 살아 있는가 | health, 인스턴스·컨테이너, PostgreSQL·Redis 연결, 관측 공백 |
| `OPS-02` | 느려졌는가 | API p50·p95·p99, Tomcat·HikariCP 대기, 외부 API·AI 지연 |
| `OPS-03` | 실패하는가 | 5xx·timeout, scheduler·의존성 실패, AI·Tool Calling 오류 |
| `OPS-04` | 돈을 많이 쓰는가 | AI 요청·token·status별 사용량과 추정 비용, 관측 데이터 수집량. provider·model은 event contract에만 남기고 metric label로 전송하지 않음 |
| `OPS-05` | 기능이 실제로 동작하는가 | 알림, 채팅, 참가 대기열의 시도부터 최종 업무 결과까지 |

화면 존재, Terraform 선언, Actuator endpoint와 테스트 metric 증가는 완료가 아니다. 같은 release의 정상·장애·복구 시나리오에서 지표·로그·경고·업무 결과를 확인해야 한다.

## 현재 기반과 남은 연결

| 현재 기반 | 확인된 범위 | P2에서 남은 범위 |
| --- | --- | --- |
| Spring Boot Actuator·Micrometer | HTTP·JVM 기본 지표와 채팅·인증 제한 custom metric | 지속 export, percentile·label 설정, 수집 공백 |
| 도메인 key-value 로그 | 알림 relay·cleanup, ROOM 보정·재시도, 채팅 실패·보존 | 공통 JSON 형식, 중앙 수집, request·작업 상관과 개인정보 검사 |
| Amazon CloudWatch Agent | EC2에서 백그라운드로 실행되며 host CPU·memory·disk·network·swap 수집 | container·애플리케이션·의존성 지표와 중앙 로그. AI 코딩 에이전트와 무관 |
| CloudWatch dashboard·alarm | EC2 상태, CPU credit, host memory·disk와 부하 실행 화면 | 다섯 운영 질문, 실제 경고 수신과 업무 결과 |
| 부하 실행 수집기 | App·JVM·Tomcat·HikariCP·PostgreSQL·Redis 원자료 | 캠페인 밖 지속 수집. 부하 원자료를 실사용 통계로 재분류하지 않음 |
| 알림 운영 런북 | relay 지연·oldest age·retry·FAILED 기준 | 자동 집계, 경고, 중앙 조회와 실제 수신 |

## P2 운영 관측 완료선과 조건부 연동

`OPS-01`~`OPS-05`는 P2 운영 관측이 최종적으로 지원할 범위다. 각 기능의 배포 시점이 다르므로 모든 완료 기준을 한 번에 충족해야만 운영 관측 기반을 전달할 수 있는 것은 아니다. 이번 단계에서는 아래 여섯 결과를 필수 완료선으로 삼는다.

| 필수 결과 | 완료 판정 |
| --- | --- |
| 메트릭·로그 수집 | 애플리케이션 메트릭이 동일 호스트 전용 Docker bridge의 OTLP와 CloudWatch Agent를 거쳐 CloudWatch에 도착하고, 허용된 JSON 로그가 같은 release로 조회됨 |
| 요약·상세 대시보드 | 두 화면이 실제 배포 데이터로 생존·지연·실패·자원 상태와 적용 가능한 업무 결과를 표시함 |
| 이메일 경고 | 대표 경고에서 `OK → ALARM → OK` 전이와 실제 이메일 수신·복구를 확인함 |
| 배포 시 기능 검증 | 알림·채팅·참가 대기열의 격리 시나리오가 기술 수락·업무 결과·사용자 가시 결과까지 통과함 |
| 보존·보안 | 중앙 로그가 14일 뒤 자동 삭제되고 금지한 개인정보·비밀값·원문 payload가 지표와 로그에 포함되지 않음 |
| 완료 증거 | release SHA, 실행 시각, fixture, 기대 결과, 실제 metric·log·alarm 변화와 판정을 재현 가능한 기록으로 남김 |

위 완료선은 지원 범위를 줄이지 않는다. `OPS-01`~`OPS-05`의 세부 완료 기준은 해당 신호를 만드는 기능과 의존성이 배포되는 순서에 맞춰 이어서 충족한다.

이 표는 새 기능 ID나 완료 기준을 추가하지 않는다. 구현 Issue와 PR은 여섯 결과 가운데 맡은 부분을 기존 `OPS-*` 완료 기준과 연결하고, 완료한 AC 범위를 정확히 선언한다.

### 조건부 연동

| 대상 | 적용 시점 | 상태 판정 |
| --- | --- | --- |
| AI 요청·token·status별 비용·Tool Calling | AI 기능의 provider·model·호출 경계가 확정되고 실제 환경에 배포된 뒤 | provider·model·feature·prompt/schema version은 event contract에만 남기고, metric은 `status`·`token_type`으로 집계한다. 연동 전에는 값 없음을 명시하고 `OPS-04`를 완료로 표시하지 않는다. 다른 운영 관측 기반의 전달은 막지 않는다. |
| 외부 API·AI 지연과 오류 | 해당 외부 호출을 사용하는 기능이 배포된 뒤 | 내부 처리시간과 외부 응답시간을 분리해 추가한다. 호출이 없던 기간을 성공이나 비용 `0`의 증거로 쓰지 않는다. |
| API·HikariCP·AI 경고 임계값 | 정상 실행과 통제 장애의 초기 측정을 마친 뒤 | 측정값으로 초기 운영 기준을 정하고 SLA로 표현하지 않는다. 측정 전에는 임의의 확정값을 넣지 않는다. |

조건부 항목이 아직 배포되지 않았다는 이유만으로 이미 동작하는 수집·대시보드·경고·배포 검증을 미완료로 되돌리지 않는다. 반대로 미배포 항목을 `0`, 정상 또는 통과로 기록하지 않으며, 각 기능의 상태는 [P2 기능 상태 정본](README.md#기능별-현재-상태)에 따로 남긴다.

## 공통 수집·보안 계약

- 지표와 로그는 `environment`, `stackId`, `service`, `role`, `instanceId`, `release` 중 수집 경계에서 사용할 수 있는 배포 식별자를 갖는다.
- HTTP route는 `/api/rooms/{roomId}` 같은 template으로 정규화한다. 실제 ID·query string·request ID를 metric label로 사용하지 않는다.
- metric label은 provider, model, feature, tool, outcome, failure code처럼 사전에 제한한 값만 허용한다.
- 사용자 ID·이메일·IP·세션·cookie·token, 요청·응답 body, 프롬프트·응답 원문, Tool 인자·결과, 채팅 내용, 알림 payload와 원본 SQL을 중앙 지표·로그에 넣지 않는다.
- request ID는 외부 값을 그대로 신뢰하지 않고 서버가 확정한다. request ID와 허용된 자원 상관 키는 접근 제한된 로그에서만 사용한다.
- management endpoint는 loopback 또는 관리 전용 내부 경계에서만 수집한다. 수집 실패가 제품 요청과 업무 transaction을 실패시키지 않는다.
- Spring Micrometer metric은 [ADR-0071](../adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md)에 따라 OTLP HTTP로 같은 EC2의 host Amazon CloudWatch Agent에 전달한다. 컨테이너 loopback에 의존하지 않고 동일 호스트 전용 Docker bridge를 사용하며, OTLP 수신 포트를 Docker publish·인터넷·다른 host에 공개하지 않는다.
- 모든 시각은 UTC로 수집하고 dashboard 표시 timezone을 명시한다. 데이터 누락은 값 `0`이나 정상 상태가 아니라 관측 공백으로 표시한다.

## 핵심 운영 흐름

### API 오류 원인 좁히기

~~~text
5xx 또는 지연 경고 수신
→ 정규화 route·인스턴스·release와 발생 구간 확인
→ 같은 구간의 컨테이너·JVM·Tomcat·HikariCP 확인
→ PostgreSQL·Redis 상태와 구조화 오류 로그 확인
→ 애플리케이션, DB, Redis, 인프라 또는 관측 공백으로 원인 계층 분류
→ 런북에 확인 결과와 후속 Issue 기록
~~~

### 애플리케이션 메모리 장애 구분하기

~~~text
컨테이너 메모리·재시작 또는 상태 경고 수신
→ host memory와 container memory를 분리해 확인
→ JVM heap·thread·Tomcat busy와 Hikari pending 확인
→ 같은 구간의 PostgreSQL lock·connection과 Redis 상태 확인
→ App 자원 병목인지 데이터 계층 병목인지 근거와 함께 판정
~~~

### 알림 전달 이상 확인하기

~~~text
relay 실패 또는 oldest processable age 경고 수신
→ 처리·retry·FAILED·backlog와 전달 지연 확인
→ App 상태와 HikariCP·PostgreSQL 상태 확인
→ 알림 운영 런북의 조사 절차 수행
→ 자동 재처리나 broker 변경 없이 원인과 허용된 다음 조치 결정
~~~

### 스케줄 작업 이상 확인하기

~~~text
ROOM 상태 보정 또는 채팅 보존 실패·지연·상한 경고 수신
→ 잠금 획득·skip과 실행 owner 확인
→ 실패 대상·적체·실행시간과 DB 상태 확인
→ 재실행 안전성 및 다음 정상 주기의 수렴 여부 확인
~~~

### 경고 전달과 복구 검증하기

~~~text
통제된 장애 또는 test alarm 발생
→ CloudWatch OK에서 ALARM 전이 확인
→ 지정한 수신자가 실제 메시지 수신
→ 대시보드와 런북으로 원인 확인
→ 장애 입력 제거 뒤 정상 회복과 OK 전이 확인
→ 시각·release·지표·로그·수신 증거 기록
~~~

---

## OPS-01 서비스 생존과 연결 상태

### 구현 컨텍스트

| 구분 | 참조 문서 |
| --- | --- |
| 공통 규칙 | [실제 동작과 증거 상태 분리](../P2-spec.md#실제-동작과-증거-상태-분리), [공통 수집·보안 계약](#공통-수집보안-계약) |
| 화면·경고 | [대시보드의 책임](dashboard.md#대시보드의-책임), [경고 정책](dashboard.md#경고-정책) |
| 실행 환경 | [P1 AWS 실행 설계](../guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md) |
| 현재 상태 | [P2 기능 상태 정본](README.md#기능별-현재-상태) |

### 기능 규칙

- EC2 status, container running·restart·OOM, Spring liveness·readiness와 마지막 성공 수집 시각
- App1·App2의 PostgreSQL·Redis 연결 성공 여부와 dependency health
- Nginx health와 upstream별 연결 상태. Nginx 성공만으로 Spring·DB·Redis가 정상이라고 판정하지 않음
- 수집기·로그 전송 중단과 마지막 정상 수집 시각

### 완료 기준

- `OPS-01-AC1` 인터넷에 management 포트를 열지 않고 허용된 수집 주체만 health·metrics를 조회한다.
- `OPS-01-AC2` host, container, Spring, PostgreSQL과 Redis 상태를 분리하고 어느 계층이 끊겼는지 식별한다.
- `OPS-01-AC3` App1·App2와 release를 구분하고 컨테이너 재시작·OOM을 host memory와 혼동하지 않는다.
- `OPS-01-AC4` 운영을 선언한 시간에 수집을 의도적으로 중단하면 이전 정상값이 유지되지 않고 관측 공백으로 판정된다. App1·App2·PostgreSQL·Redis 전체 스택을 최대 7일 종료하는 [검증된 `PLANNED_STOP`](../guides/MONITORING_OPERATIONS.md#운영-상태-정본) 구간만 가용성 장애에서 제외한다.
- `OPS-01-AC5` 정상 → 연결 실패 → 복구 시나리오와 상태 전이를 같은 release에서 기록한다.
- `OPS-01-AC6` 배포·재기동 절차는 Spring health와 PostgreSQL·Redis 연결 확인에 성공한 뒤에만 운영 상태를 `ACTIVE`로 바꾸고 이메일 경고를 활성화한다.
- `OPS-01-AC7` 계획 종료 절차는 `PLANNED_STOP`·초과 schedule·억제 대상 alarm action을 기록하고 재조회한 뒤에만 서버를 종료한다. 하나라도 실패하면 종료를 중단하고 `ACTIVE`를 유지하며 상태 전환 실패를 조용히 무시하지 않는다.

### 제외 범위

- 장애 감지 뒤 자동 재시작·자동 확장·자동 rollback
- 관리 endpoint의 인터넷 공개와 일반 사용자용 상태 페이지
- 계획 종료로 기록되지 않은 운영 중 수집 공백의 정상 처리

---

## OPS-02 지연과 포화

### 구현 컨텍스트

| 구분 | 참조 문서 |
| --- | --- |
| 공통 규칙 | [운영 관측 공통 연결](../P2-spec.md#운영-관측-공통-연결), [공통 수집·보안 계약](#공통-수집보안-계약) |
| 화면 | [요약 대시보드의 요청 지연과 포화](dashboard.md#2-요청-지연과-포화), [상세 대시보드](dashboard.md#상세-대시보드) |
| 측정 근거 | [OPS-02 비식별 실측 결과](../measurements/k6/jiho/ops02-latency-saturation-2026-08-19.md), [k6 측정 문서](../measurements/k6/README.md) |
| 현재 상태 | [P2 기능 상태 정본](README.md#기능별-현재-상태) |

### 기능 규칙

| 계층 | 필수 관측값 |
| --- | --- |
| HTTP | method·정규화 route별 요청 수와 p50·p95·p99 응답시간 |
| Nginx | request time, upstream response time, upstream App1·App2 |
| 외부 호출·AI | provider·model·feature별 응답시간과 timeout. 해당 기능이 배포된 경우만 값 존재 |
| container·JVM | CPU, memory·limit, heap·non-heap, GC pause, live thread |
| Tomcat·HikariCP | busy·current·max thread, active·idle·pending·max connection, acquisition timeout |
| PostgreSQL·Redis | connection·waiting lock, Redis memory·client·ops·latency와 수집 실패 |

p50·p95·p99는 같은 timer의 분포에서 계산한다. 평균만 표시하거나 서로 다른 표본 구간을 하나의 percentile 비교로 사용하지 않는다.

### 완료 기준

- `OPS-02-AC1` 정상·느린 요청이 method·정규화 route·인스턴스·release와 percentile로 분리된다.
- `OPS-02-AC2` Tomcat busy와 HikariCP pending을 같은 구간에서 비교해 web thread 포화와 DB pool 대기를 구분한다.
- `OPS-02-AC3` host memory, container memory와 JVM heap을 분리해 cgroup OOM 원인을 heap 사용량으로 오인하지 않는다.
- `OPS-02-AC4` PostgreSQL waiting lock과 Redis latency를 App 지연과 같은 UTC 구간에서 조회한다.
- `OPS-02-AC5` 배포된 외부 API·AI 호출은 내부 처리시간과 외부 응답시간을 분리한다.
- `OPS-02-AC6` 통제된 느린 요청·DB pool 대기 시나리오가 예상 지표와 정상 복구를 남긴다.

### 제외 범위

- 한 주의 측정만으로 장기 SLO·SLA 또는 최종 용량 경계 확정
- 지연 감지 뒤 자동 scale·재시작과 캐시·잠금 전략 자동 변경
- 평균 응답시간만으로 percentile 완료 기준 대체

---

## OPS-03 실패와 이상

### 구현 컨텍스트

| 구분 | 참조 문서 |
| --- | --- |
| 공통 규칙 | [데이터·권한 경계 우선](../P2-spec.md#데이터권한-경계-우선), [공통 수집·보안 계약](#공통-수집보안-계약) |
| 화면·경고 | [요약 대시보드의 실패와 이상](dashboard.md#3-실패와-이상), [경고 정책](dashboard.md#경고-정책) |
| 알림 조사 | [알림 운영 런북](../guides/NOTIFICATION_OPERATIONS.md) |
| 현재 상태 | [P2 기능 상태 정본](README.md#기능별-현재-상태) |

### 기능 규칙

- HTTP 5xx와 timeout을 정규화 route·status 계열·고정 failure code로 집계
- PostgreSQL·Redis 연결·명령 실패와 Hikari acquisition timeout
- 알림 relay·cleanup, ROOM 상태 보정과 채팅 보존 scheduler의 실패·slow·lock skip·상한 도달
- AI provider timeout·rate limit·5xx, parsing·fallback과 Tool Calling 실행 실패
- 로그·metric 수집 실패와 경고 전달 실패

4xx는 사용자 입력·인증·권한 거절의 분포를 보는 정보이며 기본 장애 경고로 사용하지 않는다. 예외 message와 Tool 인자·결과 원문은 수집하지 않는다.

### 완료 기준

- `OPS-03-AC1` 5xx·timeout·dependency·scheduler·AI 오류를 유한한 failure code로 분류하고 원본 사용자 입력을 포함하지 않는다.
- `OPS-03-AC2` 같은 실패가 metric, 구조화 로그와 release·instance·request 또는 작업 상관 키로 연결된다.
- `OPS-03-AC3` 반복 실패와 단발 실패의 초기 경고 기준, 확인 dashboard·query와 허용 조치를 런북에 연결한다.
- `OPS-03-AC4` AI·Tool Calling이 배포되면 provider 실패, fallback 성공·실패와 Tool 실행 결과를 구분한다.
- `OPS-03-AC5` 대표 5xx, Redis 불능과 scheduler 실패 시나리오에서 예상 신호와 복구를 확인한다. AI 기능이 배포된 경우에는 AI 실패 시나리오도 같은 방식으로 확인한다.

### 제외 범위

- 4xx 전체를 서비스 장애율로 합산
- 실패 감지 뒤 자동 재처리·데이터 폐기·rollback·broker 교체
- 예외 message, 사용자 입력과 Tool 인자·결과 원문의 중앙 수집

---

## OPS-04 AI 사용량과 추정 비용

### 구현 컨텍스트

| 구분 | 참조 문서 |
| --- | --- |
| 공통 규칙 | [데이터·권한 경계 우선](../P2-spec.md#데이터권한-경계-우선), [공통 수집·보안 계약](#공통-수집보안-계약) |
| 화면·비용 | [요약 대시보드의 사용량과 추정 비용](dashboard.md#4-사용량과-추정-비용), [완료 증거](dashboard.md#완료-증거) |
| 현재 상태 | [P2 기능 상태 정본](README.md#기능별-현재-상태) |

AI 기능의 외부 처리·provider·model·호출 예산 경계는 완료된 [#795](https://github.com/bamsongi-club/albam-mate/issues/795)와 승인된 [ADR-0074](../adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md)·[ADR-0085](../adr/platform/0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)에 정본으로 등록돼 있다. `OPS-04`는 그 승인 경계를 관측·표시에 연결하며 provider나 비용 정책을 이 문서에서 임의로 결정하지 않는다. 실제 배포·관측·가격 snapshot 전에는 `OPS-04`를 완료로 표시하지 않는다.

### 기능 규칙

- AI 요청 수와 success·fallback·failure별 제한된 `status` 집계. provider·model·feature·prompt/schema version은 event contract에만 남기고 반복 metric label로 전송하지 않는다.
- 입력·출력 token별 누적 사용량과 이 둘을 조회에서 재계산한 `total` 누적값. 공유 event의 `totalTokens`와 중복 `total` series는 전송하지 않으며 token meter는 `token_type=input|output`만 사용한다. status별 요청 수는 별도 usage event meter가 소유하고 latency meter에는 반복 label을 붙이지 않는다.
- Tool별 호출 수·성공·실패·실행시간. Tool 이름은 허용 목록만 사용
- 실제 외부 provider 호출 수 × USD `0.10`의 기간별 고정 예약 비용과 앱 월 `$5` cap·`$4` warning 사용량
- provider 공식 가격표 snapshot을 이용한 token 기반 참고 추정값. 이는 고정 예약 cap의 계산값이 아님
- CloudWatch custom metric 수, log ingestion·보존량처럼 P2 관측이 추가한 비용 입력

고정 예약 비용은 앱 내부 예산 판정값이고 실제 청구액·청구 호출 수가 아니다. token 기반 참고 추정값도 실제 청구액이 아니다. 환율, 할인, 무료 구간, 캐시 token, provider 청구 단위와 세금이 다를 수 있으므로 가격 출처·적용일·통화·계산식을 함께 표시한다. 가격 snapshot은 USD `0.10` 예약값 적정성 재검토에 쓰되 월 cap을 다시 계산하지 않는다. AI 기능이 아직 배포되지 않은 환경의 값 없음은 비용 `0`의 증거로 사용하지 않는다.

### 완료 기준

- `OPS-04-AC1` AI 호출 경계가 요청 수를 제한된 관측 `status`로, 입력·출력 token을 `token_type`으로 기록하며 provider·model·feature·prompt/schema version은 metric label로 전송하지 않는다. token·latency에는 status를 중복하지 않는다.
- `OPS-04-AC2` 프롬프트·응답·Tool 인자·사용자 ID 없이 success·fallback·failure와 Tool 결과를 집계한다.
- `OPS-04-AC3` 실제 외부 provider 호출 수와 USD `0.10`으로 고정 예약 비용·월 cap·warning 사용량을 재현하고, 공식 가격표 snapshot·통화·적용일·계산식의 token 기반 참고 추정값을 별도 표시할 수 있다.
- `OPS-04-AC4` dashboard가 기간·status별 요청 수와 입력·출력 token을 조회에서 재계산한 total, 고정 예약 예산 사용량과 참고 추정값을 서로 구분해 보여주며 어느 값도 청구 확정액으로 표현하지 않는다.
- `OPS-04-AC5` metric·log 수집량과 보존기간으로 P2 관측 자체의 비용 증가 요인을 설명할 수 있다.
- `OPS-04-AC6` AI 기능·provider·model은 확정됐지만, 실제 배포·관측·가격 snapshot이 없는 상태에서는 `OPS-04`를 완료로 표시하지 않는다.
- `OPS-04-AC7` 기존 CloudWatch 계정 기준선과 P2 증분을 합친 예상 월 비용은 USD 10 이하이며, 기준선·가정·필수 입력이 빠지면 비용 0이나 통과가 아니라 `NO_OBSERVATION`으로 표시한다.
- `OPS-04-AC8` 예상 월 비용이 USD 10을 넘으면 수집 간격·label·로그 범위를 조정하거나 사용자 재승인을 받기 전까지 비용 검증을 통과로 기록하지 않는다.

### 제외 범위

- AI provider의 실제 청구액 확정과 AWS 계정 전체 FinOps
- 환율·할인·무료 구간·세금을 임의로 추정해 공식 가격표 snapshot을 덮어쓰거나, token 추정값으로 고정 예약 cap을 대체하기
- AI 답변의 정확성·유용성·근거 품질 평가
- AI 기능이 배포되지 않은 환경의 값 없음을 비용 `0`으로 판정

---

## OPS-05 핵심 업무 기능 결과

### 구현 컨텍스트

| 구분 | 참조 문서 |
| --- | --- |
| 공통 규칙 | [실제 동작과 증거 상태 분리](../P2-spec.md#실제-동작과-증거-상태-분리), [핵심 운영 흐름](#핵심-운영-흐름) |
| 기능 검증 | [기능 동작을 증명하는 방식](dashboard.md#기능-동작을-증명하는-방식), [핵심 업무 기능 결과 패널](dashboard.md#5-핵심-업무-기능-결과) |
| 관측 대상 | [P1 알림](../archive/p1/notification.md), [P1 채팅](../archive/p1/chatting.md), [P1 ROOM·참가](../archive/p1/room.md) |
| 현재 상태 | [P2 기능 상태 정본](README.md#기능별-현재-상태) |

### 성공 판정 원칙

HTTP 2xx나 scheduler 실행 성공은 업무 기능 성공의 일부일 뿐이다. 다음 세 단계를 구분한다.

1. 기술 수락: 요청 또는 작업이 실행 경계에 들어왔다.
2. 업무 결과: 저장 불변식과 상태 전이가 기대대로 반영됐다.
3. 사용자 가시 결과: 후속 조회·전달·복구에서 사용자가 기대한 결과를 확인했다.

모든 핵심 기능은 `attempt`, `business_success`, `business_rejection`, `technical_failure`를 구분한다. 사용자·ROOM·메시지별 label은 만들지 않는다.

### 기능 규칙

| 기능 | 실제 동작 판정 | 필수 관측 |
| --- | --- | --- |
| 알림 | 원인 event 이후 대상 Notification이 기록되고 inbox에서 조회됨 | 전달 지연, processed·retry·FAILED, oldest processable age, backlog |
| 채팅 | 메시지가 PostgreSQL에 저장되고 실시간 전달되거나 재연결 이력으로 복구됨 | 저장·publish·delivery 실패, 전달 지연, recovery messages, 활성 연결 |
| 참가 대기열 | 정원 초과 시 대기 등록되고 빈자리 발생 시 FIFO 승격되며 중복 활성 참가가 생기지 않음 | 진입·취소·승격·업무 거절, 충돌 retry·소진, 불변식 위반 0건 |

### 완료 기준

- `OPS-05-AC1` 알림이 원인 event부터 Notification 기록·조회까지 도달했는지 전달 지연·실패·backlog로 판정한다.
- `OPS-05-AC2` 채팅의 저장 성공, 실시간 publish·delivery와 재연결 복구를 분리해 판정한다.
- `OPS-05-AC3` 참가 대기열의 진입·취소·FIFO 승격·충돌 결과와 저장 불변식을 판정한다.
- `OPS-05-AC4` business rejection을 기술 실패나 장애율로 합산하지 않는다.
- `OPS-05-AC5` 각 배포의 운영 검증 단계에서 격리 fixture의 재현 가능한 통제 시나리오가 기술 수락·업무 결과·사용자 가시 결과를 함께 검증한다.
- `OPS-05-AC6` dashboard가 세 기능의 성공·지연·실패·적체를 보여주고 세부 자원 조사는 접근 제한된 로그·fixture 결과로 연결한다.
- `OPS-05-AC7` 상시 주기 synthetic은 실행하지 않으며, 시나리오 하나라도 최종 업무 결과에 도달하지 못하면 해당 배포의 기능 동작 검증을 통과로 기록하지 않는다.

### 제외 범위

- 모든 P1 기능을 대상으로 한 상시 write synthetic
- 실제 사용자 데이터와 운영 ROOM을 사용하는 통제 시나리오
- HTTP 2xx·scheduler 실행 성공만으로 최종 업무 성공 판정
- AI 답변 품질·사용자 만족도와 일반 사용자 행동 분석

---

## 공통 구조화 로그·대시보드·경고

중앙 로그는 UTC `timestamp`, `level`, 배포 식별자, 서버 확정 `requestId`, 고정 `event`, 정규화 route와 허용된 `failureCode`·`outcome`을 사용한다. 정상 API 요청 수·지연·status 분포는 metric이 소유하며 정상 2xx·4xx access log 전체를 중앙 전송하지 않는다.

중앙 수집 허용 범위는 `WARN`·`ERROR`, 알림·채팅·참가 대기열의 고정 핵심 업무 event와 배포 검증 event다. 정상 요청의 세부 흐름은 기본 수집하지 않고 오류 또는 허용된 업무 event만 서버 확정 request ID나 작업 상관 키로 연결한다.

production 애플리케이션은 [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)에 따라 Spring Boot 기본 Logstash 형식의 같은 event를 한 줄 JSON stdout과 Agent 수집 전용 rolling file에 함께 기록한다. Docker `json-file`과 전용 file은 각각 10MB × 5개로 sink별 최대 50MB, 두 sink 합계는 Spring container별 최대 100MB 이내로 회전한다. host 전체 용량은 Spring container 수에 따른 이 합계와 다른 container·host log를 별도로 더해 산정한다. host Agent는 Docker daemon 전용 내부 파일이 아니라 bind-mounted 전용 file에서 허용한 로그만 CloudWatch Logs로 전송하며, 애플리케이션은 CloudWatch Logs API를 직접 호출하지 않는다.

Agent·CloudWatch 장애는 사용자 요청과 업무 transaction을 실패시키지 않는다. 로컬 보관은 Docker 회전 범위를 넘겨 무제한 확장하지 않으며, 전송 재개 전에 회전으로 유실된 구간은 숨기지 않고 관측 공백으로 기록한다.

중앙 운영 로그는 CloudWatch Logs에서 14일 보존 후 자동 삭제한다. 원문 로그를 Git에 복사하지 않으며, 장기 보존이 필요한 장애는 민감정보를 제거한 시각·release·입력·판정·인사이트 요약만 문서에 남긴다.

대시보드는 [운영 대시보드 정책](dashboard.md)에 따라 같은 지표·로그를 재사용하는 두 화면으로 구성한다.

1. 요약 대시보드: 생존, 지연, 실패, 비용과 핵심 업무 기능 결과를 한 흐름으로 보여준다.
2. 상세 대시보드: App1·App2, JVM·Tomcat·HikariCP, PostgreSQL·Redis, scheduler, AI·Tool과 도메인 작업을 같은 UTC 구간에서 분석한다.

경고는 `warning`과 `critical`만 사용한다. 모든 경고는 실제 수신 경로, 1차 담당자, 확인 dashboard·query, 허용 조치와 금지된 자동 조치를 가져야 한다. metric·log inventory와 경고별 query·missing-data·복구 절차는 [운영 관측 런북](../guides/MONITORING_OPERATIONS.md#alarmrunbook-matrix)을 따르며, 최소 한 번은 `OK → ALARM → OK`와 실제 경고·복구 수신을 검증한다.

## 제외 범위

- 제품 내부 관리자 dashboard·metric API와 일반 사용자 상태 페이지
- OpenTelemetry distributed tracing, X-Ray, 상용 APM, Prometheus·Grafana 병행 운영과 frontend RUM
- 사용자·ROOM·메시지·알림 단위 시계열과 요청·응답·프롬프트·채팅·알림 내용 수집
- AI 제품 기능 자체와 AI 답변의 정확성·유용성·근거 품질 평가
- 모든 P1 기능의 상시 쓰기형 합성 테스트, 24시간 on-call과 다단계 escalation
- 자동 restart·rollback·scale, Outbox 자동 재처리와 잠금·broker·저장 구조 자동 변경
- AWS 계정 전체 FinOps와 실제 청구액 확정
- `OPS-06` 배포·rollback·backup·복구 자동화, `SEC-01`, `RANK-01`

## 결정 위치와 구현 경계

사용자가 확인한 운영 정책은 [운영 대시보드 정책](dashboard.md)의 해당 절이 관리한다. 이 문서는 그 정책을 각 `OPS-*` 기능 규칙과 완료 기준으로만 연결하며 확정값 목록을 반복하지 않는다.

메트릭 전송은 승인된 [ADR-0071](../adr/platform/0071-p2-application-metrics-otlp-host-cloudwatch-agent.md), 로그 전송·보존은 승인된 [ADR-0059](../adr/platform/0059-p2-structured-stdout-cloudwatch-logs.md)가 소유한다. 실제 metric·log 허용 목록, 경고별 query·런북, 배포 설정·IAM·상태 전이와 비용·장애 검증 계약은 [운영 관측 런북](../guides/MONITORING_OPERATIONS.md)에 반영했다. 남은 작업은 애플리케이션·인프라 구현, 자동 검증, AWS 배포와 실측이며 이 문서·런북·ADR 승인만으로 그 상태가 끝났다고 판정하지 않는다.

AI provider·model과 실제 이메일 주소처럼 다른 기능 명세나 배포 비밀이 소유하는 값은 이 문서에 임의로 만들지 않는다. 구현 시점의 현재 상태는 [P2 기능 상태 정본](README.md#기능별-현재-상태)만 갱신한다.
