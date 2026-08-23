# ADR-0090: P1 App EC2를 t4g.small로 상향하고 데이터 노드는 t4g.micro로 유지

- 상태: 제안됨
- 작성일: 2026-08-23
- 결정일: 미정
- 관련: [#1043](https://github.com/bamsongi-club/albam-mate/issues/1043), [ADR-0051](0051-p1-self-managed-aws-infrastructure.md), [2026-08-11 인증·알림 AWS 용량 측정](../../measurements/k6/jiho/auth-notification-capacity-2026-08-11.md), [2026-08-13 인증·알림 병목 진단 캠페인](../../measurements/k6/jiho/auth-notification-bottleneck-campaign-2026-08-13.md)
- 대체 대상: [ADR-0051](0051-p1-self-managed-aws-infrastructure.md)의 App1·App2 초기 인스턴스 유형 `t4g.micro` 지정 부분만 부분 대체
- 후속 ADR: 없음

## 맥락

[ADR-0051](0051-p1-self-managed-aws-infrastructure.md)은 P1 검증 환경을 App1·App2·PostgreSQL·Redis 모두 `t4g.micro`로 시작하는 네 EC2 토폴로지로 정했다. 이 결정은 역할별 최초 병목을 확인하기 위한 초기 기준이며, 모든 역할의 용량 합격선이나 상시 운영 사양을 뜻하지 않는다.

2026-08-11 인증·알림 혼합 부하에서는 두 App의 `512MiB` cgroup 사용량이 `95.94~99.55%`에 도달했고 Java cgroup OOM kill이 반복됐다. 같은 실행에서 App host available memory는 `32.2~45.4MiB`까지 내려갔다. 반면 PostgreSQL CPU 최대치는 `12.20%`, Redis CPU 최대치는 `5.43%`, waiting lock과 최종 처리 가능한 backlog는 `0`이었다. 두 혼합 Run은 완결성 조건을 충족하지 못한 `INVALID`이므로 용량 합격선으로 승격하지 않지만, App 메모리를 최초 직접 병목으로 재검토할 충분한 장애 신호다.

App을 `t4g.small`로 올리는 것만으로 cgroup 상한이 자동으로 커지지는 않는다. 따라서 인스턴스 유형과 컨테이너·JVM 프로파일을 함께 고정하지 않으면 호스트 메모리 여유가 생긴 것처럼 보이면서 동일한 cgroup OOM을 다시 측정할 수 있다. 반대로 PostgreSQL·Redis까지 함께 올리면 현재 관측된 병목과 무관한 비용·변수를 추가해 원인 분리가 어려워진다.

판단 기준은 다음과 같다.

- App 메모리 OOM을 먼저 제거하면서 PostgreSQL·Redis의 초기 비교 조건을 유지할 것
- 웹과 Spring이 함께 있는 App1, Spring만 있는 App2의 호스트 headroom을 별도로 관측할 것
- JVM heap·Docker cgroup·Tomcat·Hikari·CPU credit을 한 Run에서 명시적으로 기록할 것
- `INVALID` 실행을 정상점이나 실패 경계로 재해석하지 않을 것
- 짧은 검증 창의 증설 비용을 제한하고, 승인 전 Terraform·AWS 변경을 실행하지 않을 것

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 네 역할을 모두 `t4g.micro`로 유지하고 App만 최적화 | 비용과 변경 범위가 가장 작다. | 관측된 App cgroup OOM과 host headroom 부족이 남고, 최적화 효과와 인스턴스 부족을 분리해 확인하기 어렵다. | 제외 |
| App1·App2만 `t4g.small`로 상향 | 관측된 App 메모리 병목에만 2GiB 호스트를 추가하고 PostgreSQL·Redis 비교 조건을 유지한다. | App EC2 비용이 증가하고, t4g CPU credit·Docker memory limit을 함께 관측해야 한다. | **선택** |
| 네 역할을 모두 `t4g.small`로 상향 | 모든 호스트에 메모리 여유가 생긴다. | 데이터 계층 병목 근거가 없고 비용·변수·rollback 범위가 불필요하게 커진다. | 제외 |
| `t4g.medium`을 상시 기준으로 채택 | 더 큰 메모리 headroom을 기대할 수 있다. | 현재 증거보다 큰 사양이며 P1 저비용 검증 목표와 맞지 않는다. | 제외 |

## 결정

### 역할별 EC2 사양

- App1·App2는 `t4g.small`로 상향한다. `t4g.small`은 2 vCPU·2GiB 메모리의 ARM64 burstable 인스턴스다.
- PostgreSQL·Redis는 `t4g.micro`를 유지한다. 데이터 계층의 별도 증설은 해당 역할의 직접 병목 증거가 생긴 뒤 새 이슈·ADR에서 판단한다.
- CPU credit 모드는 App·PostgreSQL·Redis 모두 `standard`로 유지한다. `unlimited`는 CPU credit 과금과 성능을 섞으므로 이 ADR의 P1 비교 기준으로 사용하지 않는다.
- App1은 Nginx와 Spring이 한 호스트에 동거하고 App2는 Spring만 실행한다. 두 App의 호스트·컨테이너 지표를 역할별로 저장하며 App1의 web 메모리까지 headroom 판정에 포함한다.

### JVM·Docker 프로파일

인스턴스 유형 변경의 효과와 애플리케이션 설정 변경을 분리하기 위해 다음 값을 고정한다.

| 항목 | App1·App2 측정값 | 이유 |
| --- | --- | --- |
| JVM maximum heap | `-Xmx256m` | 기존 인프라 변수 `jvm_max_heap`의 기본값을 유지해 heap 증설을 별도 변수로 만들지 않는다. |
| Spring Docker memory limit | `1GiB` | 기존 `512MiB` cgroup OOM을 반복하지 않고, `t4g.small`의 호스트 headroom 안에서 native/thread memory를 관측한다. |
| App1 web Docker memory limit | `128MiB` | 현재 production Compose의 Nginx 기준을 유지한다. |
| Tomcat max threads | `200` | Tomcat 64 후보는 별도 재측정 계획이므로 이번 사양 판단과 섞지 않는다. |
| Hikari maximum pool size | `8` | 기존 알림·인증 측정 조건을 유지한다. |
| CPU credit | `standard` | unlimited surplus charge를 배제하고 credit balance 자체를 관측한다. |

위 값 중 Spring `1GiB`는 측정 bundle의 override가 실제 컨테이너 inspect 결과와 일치해야 한다. `512MiB` 또는 다른 JVM·thread·pool 값으로 실행한 Run은 이 ADR의 비교 Run이 아니다.

### 관측 및 판정 기준

각 Run은 App1·App2의 Spring과 App1 web에 대해 15초 간격 container metric, CloudWatch host memory·CPU credit, Docker inspect의 `OOMKilled`, App 로그, k6 summary와 relay 진단을 함께 보존한다.

- 프로파일 계약(`t4g.small`, `standard`, `-Xmx256m`, Spring `1GiB`, web `128MiB`, Tomcat `200`, Hikari `8`)이 실제 설정과 다르거나 필수 원자료·collector sample이 빠지면 `INVALID`로 분류하고 경계 계산에서 제외한다.
- 계약이 맞고 원자료가 완전한 Run에서 `OOMKilled=true`, kernel cgroup OOM, Spring 또는 web cgroup peak가 limit의 `85%` 이상, host `mem_used_percent`가 `85%` 이상 또는 available memory가 `256MiB` 미만이면 유효 `FAIL`이다. App1은 Spring과 web의 동시 사용량과 host 지표를 함께 판정한다.
- App1·App2의 `CPUCreditBalance`는 시작·최소·종료 값을 모두 기록한다. 시작값이 `10` 미만이거나 필수 구간의 표본이 없으면 credit 조건이 통제되지 않은 `INVALID`다. 완전한 Run에서 최소 balance가 `0`에 도달하면 유효 `FAIL`이며, `standard`가 아닌 모드는 이 비교에서 제외한다.
- latency·error는 기존 k6/evaluator의 시나리오 임계를 그대로 적용하고, 새 운영 SLO를 만들지 않는다. 보고서에는 목록·unread·relay p95, HTTP 오류율, 처리·실패·retry 수를 Run별로 기록한다. relay는 최소 100개 서버 표본, `failedCount=0`, `retryScheduledCount=0`, 관찰 종료 시 `processableOutboxCount=0`, waiting lock `0`을 함께 확인한다.
- 0.5×와 1× 각각 세 번의 Run은 위 자원·계약 증거와 기존 evaluator 판정을 모두 충족해야 유효 `PASS`로 인정한다. 한 번이라도 유효 `FAIL`이면 해당 단계는 실패로 종료하고 1×로 상승하지 않는다.

### Terraform 변수와 적용 경계

인프라 저장소의 P1 root module은 [stacks/aws/p1/variables.tf](https://github.com/bamsongi-club/albam-mate-infra/blob/develop/stacks/aws/p1/variables.tf)의 `app_instance_type`으로 두 App EC2에 같은 유형을 전달한다. 승인 후 별도 infra 구현에서 다음만 바꾼다.

- `app_instance_type` 기본값과 P1 예시 tfvars를 `t4g.small`로 변경한다.
- `postgres_instance_type`과 `redis_instance_type`의 `t4g.micro` 기본값은 유지한다.
- `jvm_max_heap`의 `256m` 기본값은 유지한다.
- Terraform 변경과 함께 P1 README·검증 테스트·측정 runner가 `App=t4g.small`, `Spring cgroup=1g`, `CPU credit=standard`를 같은 계약으로 읽는지 갱신·검증한다.

이 ADR과 현재 albam-mate PR은 Terraform 변경, 실제 AWS `plan/apply`, 인스턴스 교체, 배포·부하 실행을 수행하지 않는다. 승인 전에는 기본값을 바꾸거나 증설을 완료로 표시하지 않는다.

### 비용 상한

이 결정은 상시 운영 사양이 아니라 만료가 있는 P1 검증 창에만 적용한다.

- 기존 네 역할·볼륨·load generator 구성은 추가하지 않는다. 새로 커지는 범위는 App 두 대의 `t4g.micro → t4g.small` compute 유형뿐이다.
- 한 검증 스택의 `expires_at`은 최대 24시간으로 제한한다. 두 App의 EC2 사용량 상한은 48 instance-hours이며, 기존 all-micro 기준 대비 증분 비용 상한은 `48 × (t4g.small 시간당 가격 - t4g.micro 시간당 가격)`이다.
- 비용 계산은 free trial, Savings Plan, Spot 할인과 환급을 제외한 적용 시점의 서울 리전 Linux On-Demand 가격으로 산출하고, AWS Price List/청구 예상값을 apply 전 receipt에 남긴다. `standard` credit이므로 unlimited surplus credit 비용은 상한에 포함하지 않는다.
- 24시간을 넘기거나, App 외 역할을 `t4g.small` 이상으로 바꾸거나, CPU credit `unlimited`를 켜야 하는 plan은 이 ADR의 비용 상한 밖이므로 중단한다.

### 적용 순서

승인 후 별도 infra PR에서 다음 순서를 지킨다.

1. 기존 stack ID·release SHA·AMI·image digest·`expires_at`과 현재 `app_instance_type`을 receipt에 기록하고, App1/App2의 마지막 정상 사양을 rollback 값으로 보존한다.
2. `terraform fmt`, `terraform validate`, contract test와 `terraform plan`을 실행한다. plan에서 App1/App2만 교체 대상인지, PostgreSQL·Redis·네트워크·볼륨·IAM에 변경이 없는지 확인한다.
3. 비용 상한과 plan을 승인한 뒤에만 Terraform apply로 App 두 대를 교체한다. 데이터 노드는 재생성하거나 유형을 변경하지 않는다.
4. 같은 release를 데이터 노드 health 확인 → App2 → App1 순서로 배포하고, App1 Nginx가 두 Spring upstream을 모두 health 상태로 보는지 확인한다.
5. Spring cgroup `1GiB`, JVM `-Xmx256m`, CPU credit `standard`가 실제 설정과 일치하는지 inspect·CloudWatch·receipt로 확인한 뒤 0.5×를 먼저 세 번 실행한다. 세 번 모두 유효 `PASS`일 때만 같은 fixture로 1×를 세 번 실행한다.
6. 각 Run의 원자료를 회수하고 `terraform destroy` 후 state와 AWS live resource가 비었는지 확인한다. 결과가 `INVALID`이면 PASS/FAIL 경계에 포함하지 않는다.

### Rollback

- apply 전에는 기존 `app_instance_type`과 측정 프로파일을 반드시 저장한다. 기본 rollback 값은 App1·App2 `t4g.micro`, PostgreSQL·Redis `t4g.micro`, JVM `256m`, 마지막 정상 Spring cgroup 값, CPU credit `standard`다.
- plan/apply 중 오류가 나면 새 App 노드의 추가 배포를 멈추고, 저장한 변수로 App 유형만 이전 값으로 되돌린다. PostgreSQL·Redis 데이터와 볼륨을 destroy하지 않는다.
- health 확인이나 측정에서 필수 bundle 누락·프로파일 불일치가 발생하면 해당 Run을 `INVALID`로 보존한다. 완전한 bundle에서 OOMKilled, host memory headroom 위반 또는 credit 소진이 발생하면 유효 `FAIL`로 보존하고 다음 Run을 시작하지 않는다. 어느 경우든 마지막 정상 사양으로 복귀한 뒤 같은 release health를 확인한다.
- `t4g.small`이 OOM을 제거했지만 latency·error·backlog 기준을 통과하지 못하면 자동으로 더 큰 유형으로 올리지 않는다. 원인별 후속 이슈·ADR에서 판단하며, 이 ADR의 기본 rollback은 `t4g.micro` 또는 기록된 마지막 정상 사양이다.

## 결과

- 얻는 것:
    - App 메모리 병목에만 호스트 메모리를 추가해 데이터 계층 증설 없이 원인을 분리한다.
    - JVM heap과 web/Spring cgroup을 고정한 채 host headroom, cgroup OOM, CPU credit을 같은 Run에서 비교할 수 있다.
    - 동일 fixture·0.5×·1× 시나리오를 다시 실행해 기존 `INVALID` 관측과 유효한 후속 결과를 구분할 수 있다.
- 감수할 비용·위험:
    - App 두 대의 On-Demand compute 비용과 임시 인스턴스 교체 시간이 증가한다.
    - burstable CPU credit이 소진되면 latency가 악화될 수 있으며 `t4g.small`이 용량 합격이나 운영 SLO를 보장하지 않는다.
    - App1은 Nginx와 Spring이 동거하는 단일 외부 진입점이므로 App1 장애 위험과 고가용성 부재는 해결하지 않는다.
- 후속 작업:
    - 승인 후 [albam-mate-infra](https://github.com/bamsongi-club/albam-mate-infra)에서 Terraform 기본값·예시·runner·contract test를 별도 PR로 갱신한다.
    - 같은 release·fixture로 0.5× 세 번, 통과 시 1× 세 번을 실행하고 OOMKilled, cgroup/host memory, CPU credit, latency·error·backlog를 Run별로 비교한다.
    - 결과가 유효한 PASS/FAIL 경계를 만들지 못하면 인스턴스 유형을 추가로 키우지 않고 측정 계약 또는 애플리케이션 병목을 별도 판단한다.

## 보류 및 재검토

- 지금 하지 않는 것: PostgreSQL·Redis 증설, `t4g.medium` 채택, CPU credit `unlimited`, JVM heap 증설, Tomcat thread 64 변경, Terraform 실제 apply, AWS 재배포와 부하 측정
- 보류 이유: 현재 직접 증거는 App 메모리 OOM이며, 다른 역할·설정까지 동시에 바꾸면 병목과 비용을 분리할 수 없다. 이 PR의 소유 범위는 ADR과 ADR 인덱스다.
- 다시 검토할 조건:
    - App 두 대가 `t4g.small + 1GiB cgroup + standard`에서 유효한 0.5× 또는 1×를 만들지 못할 때
    - CPU credit이 반복적으로 소진되거나 `standard`에서 지연·오류가 재현될 때
    - PostgreSQL waiting lock·CPU·메모리·디스크 또는 Redis 메모리·연결 지표가 직접 병목으로 확인될 때
    - P1 검증이 만료된 임시 환경을 넘어 상시 운영·고가용성 요구로 바뀔 때

## 참고 자료

- [ADR-0051: P1 저비용 4 EC2 자체 운영 인프라와 Nginx 진입점](0051-p1-self-managed-aws-infrastructure.md)
- [2026-08-11 인증·알림 AWS 용량 측정](../../measurements/k6/jiho/auth-notification-capacity-2026-08-11.md)
- [2026-08-13 인증·알림 병목 진단 캠페인](../../measurements/k6/jiho/auth-notification-bottleneck-campaign-2026-08-13.md)
- [albam-mate-infra P1 Terraform variables](https://github.com/bamsongi-club/albam-mate-infra/blob/develop/stacks/aws/p1/variables.tf)
- [Amazon EC2 On-Demand Pricing](https://aws.amazon.com/ec2/pricing/on-demand/)
- [Amazon EC2 general purpose instance types](https://aws.amazon.com/ec2/instance-types/general-purpose/)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 팀 승인과 ADR 상태를 `승인됨`으로 전환할지 여부
    - 승인 후 infra PR의 Terraform·runner·contract test 변경과 plan 결과
    - `t4g.small + 1GiB cgroup + standard` 실제 AWS 배포 및 App1/App2 health
    - 동일 fixture의 0.5×·1× 유효 Run과 OOMKilled·memory·CPU credit·latency·error·backlog 비교

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
