# OPS-02 지연·포화 AWS 실측 — 2026-08-19

- Campaign ID: `ops02-20260819-044303`
- 상태: `completed-with-limitations`
- 문서 상태: `current`
- 근거 식별자: [비식별 evidence](evidence/ops02-latency-saturation-2026-08-19.json)
- 구현: [앱 PR #799](https://github.com/bamsongi-club/albam-mate/pull/799), 비공개 infra PR
  [#23](https://github.com/bamsongi-club/albam-mate-infra/pull/23)~[#33](https://github.com/bamsongi-club/albam-mate-infra/pull/33)

## 상태 경계

- planned: 앱 merge `d45ac020cf689bed679a8d7f11ebdf956eb7a800`을 고정하고 한 stack·release에서
  `baseline → slow-request → db-pool-wait → recovery`를 실행하도록 계획했다.
- implemented: 앱 HTTP p50/p95/p99·JVM·Tomcat·Hikari와 bounded Nginx timing, 인프라
  host/container/PostgreSQL/Redis 수집, controlled netem, manifest·evidence·cleanup gate를 구현했다.
- deployed: 앱 merge와 infra deploy source `abb3dbc65f64d4e8a211076dd57ea999f9be2f82`를 고정해
  임시 AWS stack에 한 번 배포했다. 이후 측정 runner 보정은 Terraform/app 배포를 반복하지 않았다.
- measured: phase runner infra merge `52eca3724b5d21414a144e4b90a6eb98b65f33d4`로 네 phase를 완주하고,
  후속 native collector source merge `6021714e22c6714eea8a719b49b741b9a144081a`에 실측 중 확인한
  CloudWatch 이름·dimension 계약을 반영했다.

이 구분은 merge, 배포와 실측을 같은 완료 주장으로 합치지 않기 위한 것이다.

## 고정 조건

| 항목 | 값 |
| --- | --- |
| app release | `d45ac020cf689bed679a8d7f11ebdf956eb7a800` |
| backend image | `sha256:9b1219e7603ef769ebd67db91b23b519e61878252b45b4fcb58f61db5da172a3` |
| web image | `sha256:95a7dbc5437938df168d02c0387aad2a146f98c4adf5e4233eaadddf96485cbf` |
| fixture | users 100, notification backlog 미적용 |
| UTC window | `2026-08-18T19:44:48Z`~`20:11:13Z` |
| slow-request | App2 400ms bounded delay |
| db-pool-wait | PostgreSQL container network namespace 1500ms bounded delay, 4 req/s, 60초, preallocated/max VU 100 |

실제 endpoint, AWS account·instance·network 식별자, credential과 원시 로그는 공개 근거에 넣지 않았다.

## 네 phase 결과

| phase | request error / dropped | median | p95 | max | 판정 |
| --- | --- | ---: | ---: | ---: | --- |
| baseline | `0 / 0` | 9.17ms | 11.07ms | 18.31ms | `PASS` |
| slow-request | `0 / 0` | 1,208.49ms | 1,611.85ms | 2,014.59ms | `PASS` |
| db-pool-wait | `0 / 0` | 16,750.74ms | 19,451.15ms | 32,677.81ms | `PASS` |
| recovery | `0 / 0` | 7.84ms | 10.56ms | 17.95ms | `PASS` |

- App2 Nginx upstream 중앙값·표본 수는 baseline `0.009s / 60`, slow-request `1.609s / 121`,
  recovery `0.008s / 55`였다.
- Hikari pending은 baseline·slow-request에서 `0`, db-pool-wait에서 app1·app2 각각 최대 `35`,
  recovery에서 모두 `0`이었다.
- 정제 Nginx row 712개와 HTTP p50/p95/p99, Tomcat, Hikari, JVM, host/container, PostgreSQL,
  Redis 필수 series가 각 실제 phase window에 존재했다.
- 최종 evidence는 `VALID`, invalid reason 0개다. 이는 수집·복구 계약 통과이지 SLO·SLA나 최종
  용량 확정이 아니다.

## 복구와 teardown

App2와 PostgreSQL 주입은 각 phase 뒤 owner marker와 원래 qdisc를 대조해 제거했다. cleanup log
무결성은 비식별 evidence에 SHA-256으로 남겼고, owner marker·TTL timer·netem이 남지 않았다.

증거를 먼저 회수한 뒤 Terraform 리소스 103개를 삭제했다. 최종 Terraform state는 0개이고,
EC2 5개는 모두 terminated다. EBS, EIP, VPC, subnet, security group, CloudWatch alarm/dashboard/log
group, Route 53 private zone, IAM role/policy, SNS, operations lock과 stack prefix SSM parameter는 0개다.
공유 bootstrap state·receipt bucket, 비어 있는 PAY_PER_REQUEST lock table과 공유 ECR repository는
P1 stack 밖 비용 경계로 남겼다.

## 한계

- 등록 alarm 16개는 모두 회수했지만 4개 root disk alarm은 실제 series에 없는 `StackId`·`Role`
  dimension 때문에 무데이터 `ALARM`이었다. 실제 세 dimension 계약으로 source와 자동 검증을
  보정했지만 teardown 전 stack에 다시 배포·재측정하지 않았다.
- public PromQL label catalog가 비어 있어 최종 evidence는 같은 `CWAgent` series를 Metrics Insights와
  MetricStat으로 직접 조회했다. dashboard source도 실측에서 확인한 실제 이름·dimension으로 보정했다.
- 외부 API·AI 기능은 이 환경에 배포되지 않아 `OPS-02-AC5`의 적용 대상이 아니다. 향후 해당 기능을
  배포할 때 provider·model·feature 지연을 별도 Run으로 연결해야 한다.
- 원시 bundle은 비공개 infra 로컬에만 보존한다. 공개 JSON은 source와 artifact 무결성 식별값,
  비식별 수치만 제공하므로 이 저장소만으로 원시 로그 내용을 독립 재생할 수 있다는 뜻은 아니다.
