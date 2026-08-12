# t4g.small 알림 혼합 부하 단일 측정 보고서 (2026-08-12 10:09:50~10:25:10 KST)

## 결론

App1·App2를 `t4g.micro`에서 `t4g.small`로 올리고 Spring 컨테이너 메모리 상한을 512MiB에서 1GiB로 늘린 뒤, 선행 Tomcat 64 실험과 같은 알림 혼합 부하 `0.5×`를 한 번 측정했다.

- 두 App의 최대 컨테이너 메모리는 각각 46.16%, 48.67%였다.
- 두 App 모두 컨테이너 restart 0회, 커널 cgroup OOM 0건이었다.
- App CPU credit은 측정 창에서 감소하지 않고 증가했다.
- 따라서 `t4g.small + Spring 1GiB` 자원 봉투는 선행 Run의 직접 실패 원인이던 512MiB cgroup OOM을 제거했다.
- 그러나 실제 Run은 혼합 요청 오류율 69.63%, 주요 조회 p95 약 6초, 서버 relay 전달 표본 0건으로 evaluator `INVALID`였다.

이번 결과는 “small로 올리면 기존 OOM은 해소된다”는 진단 근거다. 반면 “small이면 0.5×를 처리한다”, “코드 개선이 불필요하다”, “유효한 실패 경계를 찾았다”는 결론은 내릴 수 없다. 다음 병목 후보는 앱 로그에 반복된 Redis 연결 timeout과 Redis error reply이며, 원인은 client/pool·네트워크·명령 오류를 분리 진단하기 전까지 미확정이다.

- Campaign ID: `t4gsmall-20260812`
- Campaign 상태: `INVALID`
- 문서 상태: `current`
- 선행 실험: [Tomcat 64 알림 혼합 부하 재측정](notification-tomcat64-capacity-2026-08-11.md)
- 후속 진단: [Redis 세션 연결 진단·A/B](redis-session-connection-diagnostic-2026-08-12.md)
- 근거 식별자: [campaign evidence](evidence/notification-t4gsmall-capacity-2026-08-12.json)
- 대체 관계: 선행 실험을 뒤따르지만 유효 Run이 아니므로 기존 용량 경계를 대체하지 않는다. 변경된 자원 봉투에서 OOM이 재현되지 않았다는 사실만 추가한다.

결과 회수 뒤 `./run.sh down`으로 93개 리소스를 삭제했다. 종료 후 Terraform state는 0이고 `perf-jiho` 태그의 활성 EC2·EBS·VPC·EIP 조회는 모두 빈 배열이었다. 로컬 인프라 설정은 App `t4g.micro`, 테스트 접근 `false`, Spring 512MiB 기본값으로 복구했다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 측정 구간 | 2026-08-12 10:09:50~10:25:10 KST (UTC 01:09:50~01:25:10) |
| AWS | account `001606112268`, region `ap-northeast-2`, stack `perf-jiho` |
| App | `t4g.small` 2대, CPU credit `standard`, JVM `-Xmx256m`, Spring container memory 1GiB, Tomcat max thread 64, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large` 1대, k6 `1.3.0` |
| release SHA | `1e8427328064ca4f8594d377f27272172aaa0a24` |
| infra | head `85ad96b7306e630dbeca4e742b4eea4c44f9fc45` + Run에 보존된 dirty patch `8713ad2c…` |
| backend image | `sha256:d352c3e69461b18a75e5873dde0554f4f1b08c74588e28c07e38c0c17aaad957` |
| web image | `sha256:af6821438f427094c351e979e326c91145ffe14c9f1fffc1762bffbfcdd28585` |
| PostgreSQL image | `sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636` |
| Redis image | `sha256:bd4a0d37e7cd830117ffec9329052b4a1887afa060c265e1768f82b177ff6f43` |
| 시나리오 | `mixed-load-capacity.js`, 0.5×, warm-up 120초·측정 600초·관찰 180초 |
| fixture | 사용자 340명, 방 10개, 사용자당 알림 300건, 미확인 5% |
| 시나리오 SHA-256 | `cd73bba55a5c18df402752049efbabaaa162290d32122db1ee818c93bd1c35c1` |
| 사용자 fixture SHA-256 | `52e99eec1a6da697666f572e046e89517303cf4574dc32fba554a009b8b55180` |
| 알림 fixture SHA-256 | `a385ccb42c27efcd1b5038626a6813f8f540b596ab632d52d7d44e9820a319ec` |
| 원자료 | `albam-mate-infra/.run/results/t4gsmall-20260812-tomcat64-x05-valid-n1/`, teardown 뒤 로컬 보존 |

인스턴스 생성 후 AWS API에서 App1·App2가 모두 `t4g.small`인지 확인했다. 배포 뒤와 Run 종료 뒤에는 실제 컨테이너의 memory limit `1,073,741,824` bytes, restart 0, healthy, Actuator Tomcat max `64`를 SSM으로 확인했다. Run의 `effective-settings-app1.json`과 `effective-settings-app2.json`에도 같은 값과 고정 image revision이 기록됐다.

## 실행 이력과 판정

| Run ID | 용도 | k6 | evaluator | 보고서 반영 |
| --- | --- | ---: | --- | --- |
| `t4gsmall-20260812-tomcat64-x05-n1` | 접근 경로 사전 시도 | 99 | `INVALID` | 제외 |
| `t4gsmall-20260812-tomcat64-x05-valid-n1` | 교정 후 실제 단일 측정 | 99 | `INVALID` | 포함 |

첫 시도는 외부 접근이 닫힌 상태로 App1을 배포한 뒤 보안그룹만 열어 Nginx가 loopback에 계속 바인딩된 운영 순서 오류였다. 155개 준비 요청이 모두 `status=0`이었고 App CPU가 약 4%에 머물러 workload가 도달하지 않았다. 원자료는 제외 이력으로 보존했지만 결과 해석에는 사용하지 않았다.

테스트 접근이 열린 상태로 performance 프로파일을 다시 배포한 뒤, 부하 발생기에서 인증서 검증 HTTPS 응답과 App1·App2 두 upstream을 확인했다. 그 다음 새 Run ID로 상태를 다시 초기화하고 실제 측정을 한 번 수행했다. 실제 Run은 setup 실패 0, browsing VU 150/150, participation drop 0이었지만 relay 전달 표본이 0건이라 완결성 게이트를 충족하지 못했다. 성능 임계 실패도 함께 있었으나 유효 `FAIL` 경계로 승격하지 않는다.

## k6 결과

| 지표 | 결과 | 임계/판정 |
| --- | ---: | --- |
| HTTP 실패율 | 67.85% (10,429 / 15,371) | 참고 지표 |
| 혼합 요청 오류율 | 69.63% (10,325 / 14,828) | 작업별 1% 미만 실패 |
| setup 실패 | 0 / 155 | 통과 |
| 해결된 browsing VU | 150 / 150 | 통과 |
| participation drop | 0 | 통과 |
| unread-count 오류율 | 70.23% | 실패 |
| notification-list 오류율 | 64.22% | 실패 |
| room-join 오류율 | 80.65% | 실패 |
| room-cancel 오류율 | 8.33% | 실패 |
| unread-count 측정 p95 | 6.049초 | 1초 이하 실패 |
| notification-list 측정 p95 | 6.053초 | 1초 이하 실패 |
| room-join 측정 p95 | 5.635초 | 1초 이하 실패 |
| room-cancel 측정 p95 | 0.969초 | latency 통과, 오류율 실패 |
| 서버 relay 전달 표본 | 0 | 최소 100, Run 무효 |

최종 PostgreSQL 상태는 연결 25, active 1, waiting lock 0, 처리 가능한 Outbox 0, oldest processable age 0이었다. relay 표본이 없으므로 이 상태만으로 relay 처리량이나 전달 지연을 평가하지 않는다.

## App 자원과 OOM 판정

| 역할 | 최대 container memory / 1GiB | 최소 host available memory | 최대 Tomcat busy / max | 최대 Hikari pending | 최대 JVM live thread |
| --- | ---: | ---: | ---: | ---: | ---: |
| App1 | 495,661,875 B / 46.16% | 827,895,808 B | 45 / 64 | 4 | 90 |
| App2 | 522,610,278 B / 48.67% | 827,314,176 B | 39 / 64 | 3 | 86 |

15초 수집기는 각 App에서 66개 표본을 오류 없이 기록했다. Run 종료 후 두 Spring 컨테이너는 restart 0, running, healthy였고 측정 구간 커널 로그에서 OOM·out-of-memory·killed process 문자열은 없었다. 최대 JVM heap used는 App1 약 109.2MiB, App2 약 125.9MiB였다.

CloudWatch 5분 집계에서 App1 CPU 최대 39.08%, App2 36.65%였고 CPU credit은 App1 `11.046→13.512`, App2 `11.134→13.784`로 증가했다. 선행 micro Run처럼 credit이 0에 수렴하지 않았으므로 이번 최초 실패를 CPU credit 소진으로 설명할 근거는 없다.

## 다음 병목 후보

App1 Nginx에는 warm-up 중인 01:10:55 UTC부터 unread-count 500 응답이 기록됐다. App 로그 후반에는 `RedisConnectionFailureException`과 1초 `ConnectTimeoutException`이 반복됐다. 반면 Redis 컨테이너는 restart 0, running, healthy였고 커널 OOM 기록도 없었다.

| Redis 관측 | 값 |
| --- | ---: |
| 최대 host CPU | 8.11% |
| 최대 used memory | 2,229,560 B |
| 최대 connected clients | 5 |
| 최대 ops/s | 1,340 |
| 최종 rejected connections | 0 |
| 최종 evicted keys | 0 |
| 최종 total error replies | 64,267 |

Redis 서버의 CPU·메모리·재시작·connection reject 고갈은 관찰되지 않았다. 그렇다고 Redis를 정상으로 확정할 수도 없다. 앱의 연결 timeout과 Redis error reply가 함께 있으므로 다음 실험 전에는 다음을 진단해야 한다.

1. Redis error reply를 command/error 종류별로 수집한다.
2. App의 Lettuce 연결 pool·pending acquire·event-loop 지표와 timeout 원인을 수집한다.
3. App↔Redis TCP connect/RTT/reset과 DNS resolution을 Run 구간에 함께 측정한다.
4. relay 표본 0의 원인이 이벤트 미생성, 로그 파싱 누락, relay 미실행 중 어디인지 분리한다.
5. 같은 release로 0.5×를 다시 돌리기 전 OOM/restart 0과 relay 표본 완결성 게이트를 자동화한다.

이번 결과만으로 Redis 증설, broker 도입, 애플리케이션 코드 변경 중 하나를 선택하지 않는다. 먼저 오류 종류와 연결 경로를 식별한 뒤 한 변수씩 비교한다.

## 선행 micro Run과 비교

| 항목 | `t4g.micro + 512MiB` | `t4g.small + 1GiB` |
| --- | ---: | ---: |
| Run 판정 | `INVALID` | `INVALID` |
| App1 최대 container memory | 94.10% | 46.16% |
| App2 최대 container memory | 99.71% | 48.67% |
| App2 restart | 1 | 0 |
| Java cgroup OOM | 있음 | 없음 |
| App CPU credit | 거의 소진 | 증가 |
| 혼합 요청 오류율 | 54.27% | 69.63% |
| unread p95 | 26.316초 | 6.049초 |
| notification-list p95 | 26.071초 | 6.053초 |
| relay 전달 표본 | 0 | 0 |

두 Run 모두 무효이고 자원 봉투가 동시에 바뀌었으므로 오류율·p95 차이를 일반적인 성능 향상 또는 회귀로 해석하지 않는다. 비교로 확정할 수 있는 것은 변경된 자원 봉투에서 OOM과 credit 소진이 재현되지 않았다는 점뿐이다.

## 원자료와 teardown

비식별 근거 JSON은 핵심 수치와 원자료 체크섬을 기록한다. 원자료는 로컬 전용이며 Git에는 포함하지 않는다.

- `./run.sh down`: `Destroy complete! Resources: 93 destroyed.`
- teardown 뒤 Terraform state: 0
- `perf-jiho` 활성 EC2 / EBS / VPC / EIP: 각각 `[]`
- 로컬 App instance type: `t4g.micro`로 복구
- 로컬 Spring memory override: 제거, Compose 기본 512MiB로 복구
- 로컬 `load_test_access_enabled`: `false`로 복구
- 인프라 실험 워크트리 tracked status: clean
- 실제 Run 원자료: `.run/results/t4gsmall-20260812-tomcat64-x05-valid-n1/` 보존
- 제외 Run 원자료: `.run/results/t4gsmall-20260812-tomcat64-x05-n1/` 보존

ECR의 고정 image digest와 스택 외부 수명주기의 SSM SecureString은 후속 재현을 위해 삭제하지 않았다. 이는 실행 서버가 남아 있다는 뜻은 아니다.
