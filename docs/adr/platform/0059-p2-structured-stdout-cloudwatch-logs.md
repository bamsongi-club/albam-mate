# ADR-0059: P2 구조화 애플리케이션 로그를 CloudWatch Logs에 전송

- 상태: 승인됨
- 작성일: 2026-08-13
- 결정일: 2026-08-13
- 관련: [P2 운영 관측](../../p2/monitoring.md), [P2 운영 대시보드 정책](../../p2/dashboard.md), [ADR-0058 애플리케이션 메트릭 OTLP 전송](0058-p2-application-metrics-otlp-host-cloudwatch-agent.md), [운영 Compose](../../../compose.production.yml)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

P2는 오류와 핵심 업무 결과를 release·instance·request 또는 작업 상관 키로 조사하되 개인정보·비밀값·원문 payload를 중앙에 모으지 않아야 한다. 현재 production Compose는 Spring·web stdout을 Docker `json-file` driver로 각각 `10m` 크기, 최대 5개까지 회전하지만 Spring 로그는 공통 JSON schema가 아니고 중앙 수집·필터·보존 정책도 구현하지 않았다.

2026-08-13 첫 논의에서는 Spring Boot Logstash JSON stdout을 Docker `json-file`로 회전하고 host CloudWatch Agent가 Docker 내부 log file을 직접 읽는 안을 선택했다. 이후 공식 Docker 문서를 확인한 결과 `json-file`의 내부 파일은 Docker daemon 전용이며 외부 도구가 접근하면 예기치 않은 동작을 일으킬 수 있으므로 피하라고 명시돼 있었다. 따라서 최초 선택은 승인 결정으로 확정하지 않고, stdout-first 경계와 Agent file 수집을 안전하게 연결하는 방식을 다시 선택한다.

판단 기준은 다음과 같다.

- Spring Boot가 기본 제공하는 구조화 logging을 사용하고 CloudWatch SDK·remote appender를 제품 프로세스에 추가하지 않을 것
- 한 event를 한 줄 JSON stdout으로 남기고 Docker의 제한된 로컬 회전을 유지할 것
- Docker daemon 전용 `json-file` 내부 파일을 외부 Agent가 직접 읽지 않을 것
- 중앙 전송은 허용한 level·event만 포함하고 금지 데이터는 생성·수집 두 경계에서 차단할 것
- Agent·CloudWatch 장애가 제품 요청을 실패시키지 않고 유실 구간을 관측 공백으로 드러낼 것
- 중앙 로그는 14일 뒤 자동 삭제할 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| stdout과 별도 bind-mounted rolling JSON file에 같은 구조화 event 기록, host Agent는 전용 file만 수집 | stdout-first 경계를 유지하면서 Agent가 공식 지원하는 일반 파일을 안정적으로 읽고 중앙 허용 목록을 적용한다. | log event를 두 output에 기록하고 애플리케이션 file rotation·host directory 권한·disk 상한을 관리해야 한다. | 선택 |
| Spring stdout → Docker `json-file` 내부 파일을 host Agent가 직접 수집 | 현재 Compose 회전을 그대로 재사용한다. | Docker 공식 문서가 daemon 전용 파일의 외부 접근을 피하라고 경고한다. container 교체·회전과 Agent file descriptor 경계도 불안정하다. | 제외 |
| Spring stdout → host의 `docker logs --follow` pump → bounded file → host Agent | Docker CLI/API를 통해 stdout을 읽으므로 daemon 내부 파일에 직접 접근하지 않는다. | 별도 상주 pump의 재시작·container 교체·중복·유실·file rotation을 구현하고 관측해야 한다. | 대안 |
| Docker `awslogs` driver의 non-blocking 전송 | 별도 file tail 없이 Docker가 CloudWatch Logs로 전송한다. | host Agent 경계를 우회하고 중앙 허용 목록·14일 보존·로컬 조회와 buffer drop 정책을 Docker/AWS driver에 결합한다. | 대안 |
| 애플리케이션 remote appender 또는 OpenTelemetry log export | Docker envelope 없이 event를 직접 전송할 수 있다. | 제품 프로세스에 원격 전송 buffer·재시도·장애와 추가 의존성을 넣고 메트릭과 다른 실패 경계를 만든다. | 제외 |

## 결정

production Spring은 같은 구조화 event를 한 줄 JSON stdout과 bind-mounted Agent 수집 전용 rolling file에 함께 기록한다. Docker `json-file`은 stdout을 로컬 운영 조회용으로 회전하고, host CloudWatch Agent는 Docker 내부 파일이 아니라 전용 rolling file만 읽어 CloudWatch Logs로 전송한다.

1. Spring Boot 기본 구조화 logging을 사용해 stdout과 전용 file 모두 Logstash JSON 형식으로 기록한다. 별도 encoder dependency·remote appender·CloudWatch SDK는 추가하지 않는다.
2. 공통 필드는 UTC `timestamp`, `level`, `service`, `environment`, `stackId`, `role`, `instanceId`, `release`, 서버 확정 `requestId`, 고정 `event`, 허용된 `failureCode`·`outcome`이다. 적용되지 않는 값은 빈 문자열로 채우지 않고 생략한다.
3. 사용자 ID·이메일·IP·세션·cookie·token, 요청·응답 body, query string, 프롬프트·응답 원문, Tool 인자·결과, 채팅 내용, 알림 payload와 원본 SQL은 구조화 field와 message에 넣지 않는다. 필요한 자원 상관 키는 허용 목록·접근 제한을 적용한 조사 event에만 둔다.
4. stdout은 현재 Docker `json-file`의 `max-size=10m`, `max-file=5`를 유지해 컨테이너별 최대 50MB 범위에서 `docker logs`로 조회한다. Docker daemon 전용 내부 파일을 Agent나 다른 도구가 직접 읽지 않는다.
5. Agent 수집 전용 file은 container 안의 고정 경로에 기록하고 App1·App2 host의 전용 directory를 bind mount한다. file appender는 파일당 최대 10MB, 현재 파일을 포함해 최대 5개, 총 50MB 이내로 회전한다. 압축 여부와 날짜 suffix는 구현 세부지만 총 상한을 넘기지 않는다.
6. 전용 host directory는 Spring container의 실행 UID가 쓰고 host Agent만 읽을 수 있게 최소 권한을 적용한다. App1·App2는 서로 다른 host directory와 log stream을 사용하고 로그 파일을 공유하지 않는다.
7. Agent filter는 `WARN`·`ERROR`, 알림·채팅·참가 대기열의 고정 핵심 업무 event와 배포 검증 event만 중앙 전송한다. 정상 API 요청 수·지연·status 분포는 ADR-0058의 metric이 소유하며 정상 2xx·4xx access log 전체는 보내지 않는다.
8. log stream은 `environment`, `stackId`, `role`, `instanceId`, `release`를 식별할 수 있어야 한다. CloudWatch Logs 보존기간은 14일이며 원문 로그를 Git에 복사하지 않는다.
9. Agent·CloudWatch 전송 실패는 사용자 요청과 업무 트랜잭션을 실패시키지 않는다. 전용 file 회전 전에 보내지 못한 로그가 삭제되면 해당 UTC 구간과 마지막 수집 시각을 관측 공백으로 기록한다. 파일 쓰기 실패도 제품 요청을 실패시키지는 않지만 stdout 오류와 관측 self-health로 드러내야 한다.
10. stdout과 전용 file에 같은 event가 두 번 기록되는 것은 서로 다른 로컬 sink의 의도된 중복이다. CloudWatch Agent는 전용 file 하나만 수집해 중앙 중복을 만들지 않는다.
11. rollback은 Agent의 file collection과 Spring file appender를 비활성화하고 bind mount·host directory를 제거하는 방식으로 수행한다. Docker의 제한된 stdout 회전과 제품 기능은 유지한다.

## 결과

- 얻는 것:
    - Docker daemon 전용 파일을 외부에서 읽지 않고 stdout 운영 조회와 Agent file 수집을 분리한다.
    - 애플리케이션에 AWS 전송 의존성을 넣지 않은 채 구조화 event·중앙 허용 목록·14일 보존을 적용할 수 있다.
    - Docker stdout과 Agent 전용 file 모두 50MB 상한을 가져 Agent 장애가 host disk의 무제한 증가로 이어지지 않는다.
- 감수할 비용·위험:
    - 같은 event를 stdout과 file에 직렬화·기록하므로 CPU와 disk write 비용이 늘어난다.
    - bind mount UID·권한, file rotation과 Agent tail 상태를 배포 절차에서 함께 관리해야 한다.
    - file appender 실패가 제품 요청을 막지 않도록 격리하면 중앙 로그 일부가 누락될 수 있으므로 self-health와 관측 공백 판정이 필요하다.
- 후속 작업:
    - production structured stdout·file appender, 공통 field·MDC와 개인정보 회귀 검사를 구현한다.
    - Compose에 전용 bind mount와 50MB file rotation 계약을 구현하고 host directory 권한을 검증한다.
    - 인프라 저장소에서 Agent file filter·log group·stream·14일 retention을 구현한다.
    - 정상·WARN·ERROR·허용 업무 event·금지 payload, Agent 중지·file rotation·수집 재개 시나리오를 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: Docker 내부 `json-file` 직접 tail, host `docker logs --follow` pump, Docker `awslogs`, remote appender, 정상 access log 전체 중앙 전송, 원문 로그의 Git 장기 보존
- 보류 이유: Docker 소유 파일의 외부 접근 위험과 P2 비용·개인정보 경계를 피해야 한다.
- 다시 검토할 조건:
    - stdout·file 이중 기록 비용이 App 자원 예산이나 P2 관측 비용 상한을 넘을 때
    - bind mount·Agent tail에서 반복적인 중복·유실·권한 장애가 발생할 때
    - 운영 플랫폼이 ECS·EKS처럼 stdout 중앙 수집을 기본 제공하는 환경으로 이전될 때

## 참고 자료

- [Spring Boot 구조화 logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured)
- [Docker `json-file` logging driver와 외부 접근 경고](https://docs.docker.com/engine/logging/drivers/json-file/)
- [CloudWatch Agent file log·filter·retention 설정](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Agent-Configuration-File-Details.html)

## 검증

- 상태: 미검증
- 근거:
    - 구현: `compose.production.yml`은 Spring·web stdout에 Docker `json-file`과 `max-size=10m`, `max-file=5` 회전을 적용한다.
    - 계약: 이 ADR과 P2 운영 관측·대시보드·아키텍처 문서가 구조화 stdout·전용 rolling file, sink별 50MB 상한, 중앙 허용 목록, 14일 보존과 관측 공백 경계를 같은 결정으로 연결한다.
- 미검증:
    - Spring Boot Logstash JSON stdout·file appender, 공통 field·MDC·금지 데이터 검사를 생산 코드에서 확인하지 않았다.
    - bind mount UID·권한, file당 10MB·최대 5개 회전과 Docker stdout 50MB 상한을 실제 App1·App2에서 확인하지 않았다.
    - Agent filter·CloudWatch Logs 전송·14일 retention과 장애 시 관측 공백을 실제 배포에서 검증하지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
