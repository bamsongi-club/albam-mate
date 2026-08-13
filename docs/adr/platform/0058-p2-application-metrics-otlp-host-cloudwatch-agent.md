# ADR-0058: P2 애플리케이션 메트릭을 동일 호스트 CloudWatch Agent로 OTLP 전송

- 상태: 승인됨
- 작성일: 2026-08-13
- 결정일: 2026-08-13
- 관련: [P2 운영 관측](../../p2/monitoring.md), [P2 운영 대시보드 정책](../../p2/dashboard.md), [다중 인스턴스 실행](../../ARCHITECTURE.md#다중-인스턴스-실행), [ADR-0051 P1 저비용 4 EC2 자체 운영 인프라](0051-p1-self-managed-aws-infrastructure.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

P2 운영 관측은 Spring Boot Actuator·Micrometer의 HTTP·JVM·도메인 메트릭을 CloudWatch Metrics·Dashboard·Alarm에서 지속적으로 사용해야 한다. 현재 AWS 검증 토폴로지는 App1·App2의 Spring을 Docker bridge 안에서 실행하고, Amazon CloudWatch Agent는 각 EC2 호스트에서 host 메트릭을 수집한다.

초기 P2 문서는 애플리케이션이 같은 EC2의 `loopback` Agent로 OTLP를 보낸다고 표현했다. 그러나 컨테이너의 `127.0.0.1`은 호스트가 아니라 컨테이너 자신이므로 현재 실행 토폴로지에서 이 경로는 성립하지 않는다. Agent를 외부 인터페이스 전체에 노출하거나 다른 호스트의 Agent로 우회하면 수집 경계와 장애 격리가 불명확해진다.

판단 기준은 다음과 같다.

- 애플리케이션에 AWS SDK, CloudWatch API 호출과 AWS 자격 증명을 넣지 않을 것
- App1·App2가 각자 같은 EC2의 Agent에만 전송하고 OTLP 수신 포트를 인터넷이나 다른 호스트에 공개하지 않을 것
- 수집 장애·CloudWatch 장애가 사용자 요청과 업무 트랜잭션 결과를 바꾸지 않을 것
- Micrometer의 meter·tag 모델과 P2의 제한된 label 계약을 유지할 것
- 기존 host CloudWatch Agent와 IAM 경계를 재사용하고 별도 상용 APM이나 관리 백엔드를 만들지 않을 것
- 전송 실패, 관측 공백과 비용을 확인하고 안전하게 수집을 중지할 수 있을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 호스트 CloudWatch Agent가 동일 호스트 전용 Docker bridge에서 OTLP HTTP 수신 | 기존 host Agent·IAM을 재사용하고 애플리케이션에서 AWS 의존성을 제거한다. App별 수집 경계와 host 지표를 같은 Agent에서 관리한다. | Docker bridge와 host 방화벽 설정이 필요하고 Agent 장애 시 해당 App의 관측 공백이 생긴다. | 선택 |
| CloudWatch Agent를 Compose 서비스로 실행 | Spring과 같은 Compose network에서 안정적인 서비스 이름을 사용할 수 있다. | 현재 host Agent를 대체하거나 이중 운영해야 하며 host metric 수집을 위한 mount·권한과 배포 생명주기가 복잡해진다. | 제외 |
| 애플리케이션이 CloudWatch registry·SDK로 직접 전송 | 컨테이너와 host 사이 수신 포트를 만들지 않아도 된다. | 애플리케이션에 AWS SDK·자격 증명·재시도와 vendor API 장애 경계를 넣고 제품 코드와 운영 인프라를 결합한다. | 제외 |
| Prometheus endpoint를 별도 collector가 scrape | pull 방식으로 애플리케이션 export 실패를 분리하고 Prometheus 생태계를 활용할 수 있다. | P2에서 운영하지 않는 Prometheus collector와 scrape discovery·보안 경계를 추가하며 현재 CloudWatch Agent OTLP 방향을 이중화한다. | 보류 |

## 결정

Spring Micrometer 메트릭은 OTLP HTTP로 각 Spring 컨테이너와 같은 EC2에 설치된 Amazon CloudWatch Agent에 전송하고, Agent가 CloudWatch Metrics로 내보낸다.

1. 애플리케이션은 Micrometer OTLP registry를 사용하고 표준 OTLP HTTP 포트 `4318`의 `/v1/metrics`로 push한다. gRPC `4317`, CloudWatch SDK 직접 호출과 다른 호스트 Agent fallback은 사용하지 않는다.
2. Agent의 OTLP receiver는 지정한 동일 호스트 전용 Docker bridge의 host gateway/interface에서만 수신한다. Spring 컨테이너에는 배포 설정이 확정한 같은-host endpoint를 주입한다. `4318`은 Docker publish, EC2 보안 그룹, public/private host 인바운드에 추가하지 않는다.
3. host 방화벽은 해당 Spring 컨테이너 network source만 `4318`에 접근하도록 제한한다. bridge CIDR·gateway는 환경별 배포 값이며 ADR에 고정하지 않지만, 실제 적용값과 검증 결과는 배포 manifest에 남긴다.
4. App1은 App1 Agent, App2는 App2 Agent만 사용한다. 한 Agent가 실패했을 때 다른 EC2로 자동 우회하지 않고 해당 구간을 관측 공백으로 기록한다.
5. meter에는 `environment`, `stackId`, `service`, `role`, `instanceId`, `release` 중 수집 경계에서 확정할 수 있는 배포 식별자를 resource attribute 또는 제한된 dimension으로 붙인다. 사용자·ROOM·메시지·request ID와 원문 값은 label로 사용하지 않는다.
6. export는 사용자 요청과 업무 트랜잭션 밖에서 bounded timeout·buffer로 수행한다. buffer 포화·전송 실패는 제품 요청을 실패시키거나 무제한 메모리 사용을 일으키지 않으며 Agent·CloudWatch 상태와 마지막 수집 시각으로 드러내야 한다.
7. 수집 주기, histogram·percentile, CloudWatch dimension과 metric 허용 목록은 실제 비용·조회 가능성 검증에서 확정한다. 미측정 값을 SLA로 표현하거나 P2 관측 비용 상한을 넘긴 채 활성화하지 않는다.
8. rollback은 애플리케이션 OTLP export와 Agent receiver를 비활성화하고 bridge·방화벽 규칙을 제거하는 방식으로 수행한다. 제품 기능과 기존 Actuator·인프로세스 meter 정의는 유지한다.

## 결과

- 얻는 것:
    - 애플리케이션의 AWS 자격 증명과 CloudWatch API 의존 없이 기존 Micrometer meter를 CloudWatch로 보낼 수 있다.
    - 각 App host의 애플리케이션 메트릭과 host 메트릭을 같은 release·instance 시간축에서 비교할 수 있다.
    - Agent 장애를 제품 장애가 아닌 관측 공백으로 격리할 수 있다.
- 감수할 비용·위험:
    - Docker bridge gateway, host 방화벽과 Agent receiver를 배포 순서에 맞춰 관리해야 한다.
    - Agent buffer·CloudWatch 전송이 지연되면 로컬 meter와 dashboard 사이에 시차나 누락이 생길 수 있다.
    - metric·dimension 수를 제한하지 않으면 CloudWatch 비용과 dashboard query 복잡도가 증가한다.
- 후속 작업:
    - 애플리케이션 OTLP registry·resource attribute·export 실패 검증을 구현한다.
    - 인프라 저장소에서 same-host bridge, Agent OTLP receiver, host 방화벽과 IAM을 구현·검증한다.
    - 정상·Agent 중지·전송 재개 시나리오로 제품 요청 결과, buffer 상한과 관측 공백을 확인한다.
    - 고정 release에서 CloudWatch metric·dashboard·alarm과 예상 월 비용을 검증한다.

## 보류 및 재검토

- 지금 하지 않는 것: Prometheus·Grafana 병행 운영, OpenTelemetry distributed tracing, X-Ray, 애플리케이션의 CloudWatch SDK 직접 호출, 다른 host Agent 자동 fallback
- 보류 이유: P2는 CloudWatch 한 경로로 메트릭·대시보드·경고를 완성하고 제품 요청과 관측 장애를 분리하는 것이 우선이다.
- 다시 검토할 조건:
    - CloudWatch Agent OTLP receiver가 필요한 Micrometer metric type·dimension을 보존하지 못할 때
    - same-host bridge·방화벽 운영 비용이 Agent sidecar나 다른 collector보다 커질 때
    - CloudWatch 비용 상한을 수집 간격·허용 목록 조정으로도 지키지 못할 때
    - tracing 또는 여러 backend로의 vendor-neutral export가 실제 P2 기능 요구가 될 때

## 참고 자료

- [CloudWatch Agent의 OpenTelemetry metric·trace 수집](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/CloudWatch-Agent-OpenTelemetry-metrics.html)
- [Micrometer OTLP registry](https://docs.micrometer.io/micrometer/reference/implementations/otlp.html)
- [P2 운영 관측](../../p2/monitoring.md)
- [P2 운영 대시보드 정책](../../p2/dashboard.md)

## 검증

- 상태: 미검증
- 근거:
    - 계약: 이 ADR과 P2 운영 관측·대시보드·아키텍처 문서가 OTLP HTTP, 동일 호스트 전용 Docker bridge, host Agent와 외부 비공개 경계를 같은 결정으로 연결한다.
- 미검증:
    - Micrometer OTLP registry와 resource attribute 설정을 생산 코드에서 확인하지 않았다.
    - Docker bridge·host 방화벽·Agent OTLP receiver와 CloudWatch export를 실제 App1·App2에 적용하지 않았다.
    - Agent 중지·buffer 포화·전송 재개가 제품 요청을 바꾸지 않고 관측 공백으로 남는지 검증하지 않았다.
    - 실제 metric·dimension 수, dashboard query와 월 비용을 측정하지 않았다.

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
