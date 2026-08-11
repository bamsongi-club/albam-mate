# Tomcat 64 알림 혼합 부하 재측정 보고서 (2026-08-11 21:50:40~22:06:03 KST)

## 결론

Tomcat 최대 thread를 App1·App2 모두 `200`에서 `64`로 제한한 뒤 알림 혼합 부하를 다시 측정했다. 설정은 실제 컨테이너에서 `64`로 검증됐지만, 첫 `0.5×` Run에서 App2 Java 프로세스가 512MiB cgroup OOM으로 종료됐다. Run과 campaign은 모두 `INVALID`이며 0.5× 2·3회차와 1× 3회는 실행하지 않았다.

- Campaign ID: `tomcat64-20260811`
- Campaign 상태: `INVALID`
- 문서 상태: `current`
- 선행 기준선: [인증·알림 AWS 용량 측정](auth-notification-capacity-2026-08-11.md)
- 근거 식별자: [campaign evidence](evidence/notification-tomcat64-capacity-2026-08-11.json)
- 대체 관계: 선행 기준선의 후속 실험이지만, 유효 Run을 얻지 못해 기존 결론을 대체하지 않는다.

이번 실행으로 확정할 수 있는 것은 다음 세 가지다.

1. performance 배포의 `SERVER_TOMCAT_THREADS_MAX=64` 계약은 두 App에서 실제 적용됐다.
2. thread 상한 64만으로는 App2의 512MiB cgroup OOM을 제거하지 못했다.
3. 유효한 정상점·실패점과 200 대비 개선 효과는 여전히 미확정이다. k6 오류율이나 p95의 숫자 차이를 개선으로 해석하지 않는다.

스택은 보고 근거를 회수한 뒤 `./run.sh down`으로 94개 리소스를 삭제했다. 종료 후 Terraform state는 0이고 `perf-jiho` 태그의 활성 EC2·EBS·VPC·EIP 조회는 모두 빈 배열이었다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 측정 구간 | 2026-08-11 21:50:40~22:06:03 KST (UTC 12:50:40~13:06:03) |
| AWS | account `001606112268`, region `ap-northeast-2`, stack `perf-jiho` |
| App | `t4g.micro` 2대, CPU credit `standard`, JVM `-Xmx256m`, container memory 512MiB, Tomcat max thread 64, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large` 1대, k6 `1.3.0` |
| release SHA | `1e8427328064ca4f8594d377f27272172aaa0a24` |
| infra SHA | `85ad96b7306e630dbeca4e742b4eea4c44f9fc45`, Run 시작 시 clean |
| backend image | `sha256:d352c3e69461b18a75e5873dde0554f4f1b08c74588e28c07e38c0c17aaad957` |
| web image | `sha256:af6821438f427094c351e979e326c91145ffe14c9f1fffc1762bffbfcdd28585` |
| PostgreSQL image | `sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636` |
| Redis image | `sha256:bd4a0d37e7cd830117ffec9329052b4a1887afa060c265e1768f82b177ff6f43` |
| 시나리오 | `mixed-load-capacity.js`, 0.5×, warm-up 120초·측정 600초·관찰 180초 |
| fixture | 사용자 340명, 방 10개, 사용자당 사전 알림 300건, 미확인 5%. 초기 사전 적재 예상 102,000건 |
| 시나리오 SHA-256 | `cd73bba55a5c18df402752049efbabaaa162290d32122db1ee818c93bd1c35c1` |
| 사용자 fixture SHA-256 | `52e99eec1a6da697666f572e046e89517303cf4574dc32fba554a009b8b55180` |
| 알림 fixture SHA-256 | `a385ccb42c27efcd1b5038626a6813f8f540b596ab632d52d7d44e9820a319ec` |
| 실행 명령 | `PERF_STATE_RESET_ACK=perf-jiho PERF_CAMPAIGN_ID=tomcat64-20260811 ./run.sh campaign tomcat64-notification` |
| 원자료 | `albam-mate-infra/.run/results/tomcat64-20260811-tomcat64-x05-n1/`, teardown 뒤 로컬 보존 |

Run은 App 중지, PostgreSQL `public` schema와 Redis DB 초기화, Flyway와 두 fixture 재적용 뒤 시작했다. 초기화 전에 App1·App2의 release SHA, image digest·OCI revision과 performance 설정을 검증했고 mismatch는 없었다.

## Campaign 진행과 판정

캠페인 계약은 0.5×를 3회 실행해 모두 유효 `PASS`일 때만 1×를 3회 실행한다. 첫 Run이 `INVALID`였으므로 더 높은 부하나 추가 반복으로 진행하지 않았다.

| Run ID | 조건 | k6 | evaluator | 후속 반복 |
| --- | ---: | ---: | --- | --- |
| `tomcat64-20260811-tomcat64-x05-n1` | 0.5×, 1회차 | exit 99 | `INVALID` | 즉시 중단 |

무효 사유는 `mixed_setup_failures rate==0` 위반과 서버 알림 전달 표본 0건이다. 동시에 9개 성능 임계가 실패했지만, 완결성 조건도 깨졌으므로 유효한 실패 경계로 승격하지 않는다.

## k6 결과

| 지표 | 결과 | 임계 |
| --- | ---: | ---: |
| HTTP 실패율 | 52.38% (5,452 / 10,408) | 참고 지표 |
| 혼합 요청 오류율 | 54.27% (5,348 / 9,854) | 작업별 1% 미만 |
| setup 실패 | 1 / 157, 0.637% | 0 |
| 해결된 browsing VU | 150 | 150 목표 |
| 참가 이벤트 | 32 | 참고 지표 |
| participation drop | 2 | 0 |
| unread-count 측정 p95 | 26.316초 | 1초 이하 |
| notification-list 측정 p95 | 26.071초 | 1초 이하 |
| room-join 측정 p95 | 22.658초 | 1초 이하 |
| room-cancel 측정 p95 | 23.846초 | 1초 이하 |
| 서버 relay 전달 표본 | 0 | 최소 100 |

k6 콘솔에는 500 응답과 HTTP/2 `INTERNAL_ERROR`가 이어졌고 13:03:52 UTC에 참가 이벤트 fixture의 `room-create` 요청이 status 0으로 실패했다. 이는 아래 App2 OOM·재시작 구간과 겹친다.

## 최초 실패 원인

App2 커널 로그가 직접 원인을 남겼다.

```text
2026-08-11 13:05:12 UTC runc invoked oom-killer
2026-08-11 13:05:13 UTC oom-kill ... task=java ... memcg=docker-5258f953...
2026-08-11 13:05:13 UTC Memory cgroup out of memory: Killed process 31442 (java), anon-rss:512372kB
```

post-run 조회에서 App2 컨테이너는 `restart=1`이었고, 생성 32분·실행 8분 상태였다. App 로그에는 13:05:18 UTC JVM 재기동과 13:05:53 UTC Spring 재시작이 기록됐다. 현재 실행 상태를 조회했기 때문에 Docker의 `State.OOMKilled`는 `false`였지만, 같은 컨테이너 cgroup과 Java PID를 지목한 커널 OOM 기록이 직접 증거다.

| 역할 | 최대 container memory | limit 대비 | 최대 Tomcat busy / max | 최대 Hikari pending | 최소 host available memory |
| --- | ---: | ---: | ---: | ---: | ---: |
| App1 | 505,203,916 B | 94.10% | 64 / 64 | 42 | 51,290,112 B (48.9MiB) |
| App2 | 535,298,048 B | 99.71% | 64 / 64 | 52 | 40,730,624 B (38.8MiB) |

App2는 OOM 직전 512MiB limit의 99.71%까지 사용했고, 다음 표본에서 container memory가 약 29MiB로 떨어지며 actuator 수집이 실패했다. App1도 Tomcat busy 64와 Hikari pending 42까지 쌓였다. thread 상한은 적용됐지만 대기 요청과 native/container memory를 512MiB 안에 유지하기에는 충분하지 않았다.

PostgreSQL 최대 host CPU는 4.57%, Redis는 7.02%, 발생기는 19.83%였다. Redis 최종 상태는 `rejected_connections=0`, `evicted_keys=0`, 사용 메모리 2.07MiB였으므로 Redis 자체의 CPU·메모리·연결 상한이 최초 병목이라는 근거는 없다. App 재시작 전후의 Redis 연결 실패는 App 측 장애의 연쇄 증상으로 분류한다.

App CPU credit은 시작부터 낮았다. CloudWatch 시작/종료 최소값은 App1 `0.841→0.079`, App2 `0.313→0.0005`였다. CPU credit 소진도 지연의 동시 요인이므로, OOM을 제거한 뒤에는 시작 credit 조건을 맞추지 않은 Run으로 latency 개선을 비교하면 안 된다.

## 선행 0.5× Run과 비교

| 항목 | 선행 Run (Tomcat 200) | 이번 Run (Tomcat 64) |
| --- | ---: | ---: |
| release SHA | `b3c3bc95…` | `1e842732…` |
| 판정 | `INVALID` | `INVALID` |
| App1 최대 memory / 512MiB | 98.14% | 94.10% |
| App2 최대 memory / 512MiB | 99.47% | 99.71% |
| 혼합 요청 오류율 | 61.26% | 54.27% |
| unread p95 | 25.201초 | 26.316초 |
| notification-list p95 | 24.520초 | 26.071초 |
| Java cgroup OOM | 있음 | 있음 |

두 Run 모두 무효이고 release SHA도 다르다. 따라서 App1 memory나 오류율의 숫자 감소를 Tomcat 64의 개선 효과로 귀속할 수 없다. 유일하게 재현된 결론은 App2 cgroup OOM이 제거되지 않았다는 점이다.

## 다음 재측정 조건

이번 결과는 설정 변경 구현을 승인하지 않는다. 다음 실험은 한 번에 한 변수만 바꾸되 아래 선행 조건을 먼저 충족해야 한다.

1. App cgroup OOM 0건과 컨테이너 restart 0회를 유효성 게이트에 추가한다.
2. App 시작 CPU credit 조건을 반복 간 맞추고, 낮은 absolute credit에서 시작한 Run은 비교에서 제외한다.
3. 512MiB 안에서 native/thread memory를 더 줄일지, App memory/instance 유형을 바꿀지 별도 실험으로 선택한다. 두 변경을 같은 Run에 섞지 않는다.
4. 다시 0.5× 3회를 통과한 뒤에만 1× 3회로 올라간다.

broker·relay·DB 변경은 이번 최초 실패 원인과 직접 연결되지 않는다. 알림 broker 판단은 기존 [별도 판단서](notification-broker-decision-2026-08-11.md)를 유지한다.

## 원자료와 teardown

비식별 근거 JSON은 Run bundle fingerprint와 핵심 파일 SHA-256을 기록한다. 원자료는 로컬 전용이며 Git에는 포함하지 않는다.

- `./run.sh down`: `Destroy complete! Resources: 94 destroyed.`
- teardown 뒤 Terraform state: 0
- `perf-jiho` 활성 EC2 / EBS / VPC / EIP: 각각 `[]`
- `.run/results/tomcat64-20260811-tomcat64-x05-n1/`: 보존
- `.run/campaigns/tomcat64-20260811/summary.json`: 보존
- 로컬 `load_test_access_enabled`: `false`로 복구

ECR의 두 image digest와 스택 외부 수명주기의 SSM SecureString은 후속 재현을 위해 삭제하지 않았다. 이는 실행 서버가 남아 있다는 뜻은 아니다.
