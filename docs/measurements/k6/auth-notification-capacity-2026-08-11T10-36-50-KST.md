# 인증·알림 AWS 용량 측정 보고서 (2026-08-11 02:10:40~10:36:50 KST)

## 결론

이 문서는 `t4g.micro` App 2대·PostgreSQL 1대·Redis 1대와 `c7g.large` k6 발생기로 인증·알림 경계를 측정한 실행 결과다. 운영 트래픽 통계나 운영 SLO가 아니라 팀이 정한 1× 목표 규모를 임시 AWS 스택에서 검증한 결과다.

- Campaign ID: `auth-notification-20260811T021040KST`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 문서 인덱스: [k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](manifests/auth-notification-20260811T021040KST.json)
- 대체 관계: 최초 캠페인, 후속 없음

- 인증 계약 3종, 알림 전달 계약, 인증 제한 계약 4종은 고정 release에서 모두 통과했다.
- 인증은 1 req/s 3분 탐색은 통과했지만 같은 1 req/s 15분 실행이 실패했다. 재현 가능한 정상 경계가 없으므로 `normal=1, failure=2`를 확정하지 않는다.
- 알림 혼합 부하는 1×와 하한 0.5×가 모두 측정 무효였다. 두 실행 모두 강한 과부하 신호를 남겼지만 유효 정상점과 유효 최초 실패점을 확보하지 못했다.
- 첫 재현 병목은 PostgreSQL relay가 아니라 App 컨테이너 메모리다. 두 App의 512MiB cgroup 사용량이 95.9~99.6%에 도달했고 EC2 console에서 Java cgroup OOM kill이 반복됐다.
- fan-out 단가 측정은 수신자 1·5·10명 × 100개 취소 이벤트 × 3회, 총 9회가 모두 통과했다. 서버 전달 p95는 4.210~4.968초, p99는 4.692~5.676초였고 모든 Run이 실패 0건과 최종 처리 가능 backlog 0건으로 끝났다.
- 이 결과만으로 Kafka 도입 필요성을 뒷받침하지 않는다. broker 판단은 [별도 판단서](notification-broker-decision-2026-08-11T10-47-24-KST.md)에 기록한다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | 2026-08-11 02:10:40~10:36:50 KST (UTC 2026-08-10 17:10:40~2026-08-11 01:36:50) |
| AWS | account `001606112268`, region `ap-northeast-2`, stack `perf-jiho`, `Environment=perf` |
| App | `t4g.micro` 2대, CPU credit `standard`, JVM `-Xmx256m`, container memory 512MiB, Tomcat max thread 200, Hikari max 8 |
| PostgreSQL / Redis | 각각 `t4g.micro` 1대 |
| 발생기 | `c7g.large` 1대, k6 `1.3.0` |
| relay | App 인스턴스당 poll 5초, batch 50 |
| release SHA | `b3c3bc95b77547047cae7a279f3658c39070795d` |
| backend image | `sha256:189cb1136648533f65ac7d23e0bf68a142e4d5576ffcf366f844eb7257e94074` |
| web image | `sha256:64b709fb9cb118d8222d425c6b1e25435a33dbeed134efacf0d41c3987b793d5` |
| PostgreSQL image | `sha256:a02db8cac496f15b094798a38254f14d6e00741f709360e5e00bb6668ea31636` |
| Redis image | `sha256:bd4a0d37e7cd830117ffec9329052b4a1887afa060c265e1768f82b177ff6f43` |
| 상태 격리 | 각 용량 Run 전에 App 중지, PostgreSQL `public` schema와 Redis DB 초기화, Flyway·fixture 재적용 |
| 원자료 | `albam-mate-infra/.run/results/<Run-ID>/`; teardown 뒤에도 로컬에 보존, Run별 지문은 [campaign manifest](manifests/auth-notification-20260811T021040KST.json)에 기록 |

App 실효 설정, image digest·OCI revision, release SHA가 다르면 초기화 전에 중단하도록 했다. 모든 유효 Run은 manifest, k6 summary/console, App 로그, 역할별 15초 CSV, CloudWatch 원시/요약, PostgreSQL·Redis 진단과 evaluator 판정을 갖는다.

## 계약 검증

다음 최종 Run은 모두 `PASS`, 잘못된 응답 0건, release SHA 일치였다.

| 계약 | Run ID | 판정 | 핵심 결과 |
| --- | --- | --- | --- |
| 정상 로그인 | `contract-20260811-auth-correct-final-r2` | PASS | 잘못된 응답 0건 |
| 잘못된 비밀번호 | `contract-20260811-auth-wrong-final` | PASS | 잘못된 응답 0건 |
| 없는 사용자 | `contract-20260811-auth-missing-final` | PASS | 잘못된 응답 0건 |
| 알림 전달 | `contract-20260811-notification-final` | PASS | 10표본, p50 2.741초, p95/p99 2.948초, 처리 10·실패 0·최종 backlog 0 |
| 회원가입 제한 | `contract-20260811-rate-signup-final` | PASS | 잘못된 응답 0건 |
| 로그인 실패 제한 초기화 | `contract-20260811-rate-failure-reset-final` | PASS | 잘못된 응답 0건 |
| X-Forwarded-For 계약 | `contract-20260811-rate-xff-final` | PASS | 잘못된 응답 0건 |
| 로그인 IP 제한 | `contract-20260811-rate-login-ip-final` | PASS | 다른 제한 Run 뒤 비어 있는 10분 창에서 마지막 실행 |

초기 배포·계측 보강 과정의 `contract-20260811-auth-correct`, `contract-20260811-auth-correct-r2`, `contract-20260811-auth-correct-r3`, `contract-20260811-auth-correct-final`은 최종 고정 release 전 또는 불완전 bundle이므로 용량·계약 결론에서 제외한다.

## 인증 campaign

### 최종 판단

`perf-20260811-auth-r2`의 campaign summary는 `normalBoundary=1`, `failureBoundary=2`, `status=INCONSISTENT`다. 이는 후보 값이지 확정 경계가 아니다.

| Run ID | 조건 | 판정 | 핵심 값 |
| --- | ---: | --- | --- |
| `perf-20260811-auth-r2-auth-explore-r1` | 1 req/s, 3분 | PASS | 완료 p95 169.2ms, 1초 거절 0%, drop 0 |
| `perf-20260811-auth-r2-auth-explore-r2` | 2 req/s, 3분 | FAIL | 완료 p95 5.721초, 1초 거절 22.22%, drop 0 |
| `perf-20260811-auth-r2-auth-formal-r1-n1` | 1 req/s, 15분 | FAIL | 완료 p95 4.779초, 1초 거절 3.11%, 예상 밖 응답 0, drop 0 |

1 req/s가 짧은 탐색과 지속 Run에서 다른 결과를 내 재현 가능한 정상 경계를 확보하지 못했다. formal Run 시작 시 App2 CPU credit은 사실상 0이었고 App1은 약 16.38이었다. PostgreSQL·Redis credit은 증가했고 발생기 CPU도 낮았으므로, 지속 인증 결과는 App tier의 burst credit 상태 영향을 함께 받은 것으로 해석한다.

앞선 `perf-20260811-auth` 탐색은 1·2·3 req/s PASS, 4 req/s FAIL을 보였지만 첫 formal `perf-20260811-auth-auth-formal-r3-n1`이 실행기 timeout으로 중복 시작돼 운영자 `INVALID`로 고정됐다. 이 campaign도 `INCONSISTENT`이며 경계 계산에서 제외한다.

## 알림 혼합 부하 campaign

1×는 온라인 세션 300명과 알림 이벤트 25건/분, 0.5×는 온라인 세션 150명과 절반 이벤트율이다. 두 조건 모두 사용자당 알림 backlog 300건, 미확인 5%, warm-up 2분·측정 10분·수렴 관찰 3분을 적용했다.

| Run ID | 조건 | 최종 판정 | 과부하·무효 근거 |
| --- | ---: | --- | --- |
| `perf-20260811-notify-r3-notification-x1` | 1×, 640명 fixture | INVALID | unread p95 32.677초, 목록 p95 23.645초, App 수집 오류 app1 47/55·app2 35/61, 서버 전달 표본 0 |
| `perf-20260811-notify-r3-notification-x05` | 0.5×, 340명 fixture | INVALID | 전체 혼합 요청 오류율 61.26%, unread p95 25.201초, 목록 p95 24.520초, participation drop 1, App1 수집 오류 11/46, 서버 전달 표본 0 |

두 Run 모두 k6 성능 임계는 여러 항목에서 실패했지만 evaluator의 완결성 조건도 함께 위반했다. 따라서 이를 유효한 `FAIL` 경계로 승격하지 않는다. 결론은 다음과 같이 제한한다.

- 지원 범위 안의 가장 낮은 0.5×에서도 유효한 정상점을 만들지 못했다.
- 1×와 0.5× 모두 강한 과부하 신호를 보였다.
- 정상·최초 실패 경계는 `미확정`이며, 병목 조정 뒤 같은 0.5×·1×를 다시 실행해야 한다.

운영 실패로 별도 보존한 Run도 있다.

- `perf-20260811-notify-notification-x1`: k6 종료 뒤 AWS SSO 만료로 필수 bundle 미완성. 부분 지표를 보존하고 `INVALID` 처리했다.
- `perf-20260811-notify-r2-notification-x1`: App 로그 SSM fetch 정체로 bundle 미완성. 부분 증거를 보존하고 `INVALID` 처리했다.
- 이후 App 로그를 원격 gzip으로 회수·로컬 복원하도록 실행기를 보강했고, r3의 x1·x0.5에서 완결 bundle을 회수했다.

## 최초 병목

알림 혼합 부하의 최초 재현 병목은 App 컨테이너 메모리다.

| 근거 | 1× | 0.5× |
| --- | ---: | ---: |
| app1 최대 container memory | 95.94% / 512MiB | 98.14% / 512MiB |
| app2 최대 container memory | 99.55% / 512MiB | 99.47% / 512MiB |
| App host 최소 available memory | app1 32.2MiB, app2 45.4MiB | app1 41.3MiB, app2 44.5MiB |
| App CPU credit | 두 노드 약 0.49에서 종료 시 0~0.02 | app1 0.49→0, app2 약 0.02 유지 |
| PostgreSQL 최대 CloudWatch CPU | 11.83% | 12.20% |
| Redis 최대 CloudWatch CPU | 5.39% | 5.43% |
| load generator 최대 CloudWatch CPU | 8.51% | 8.44% |

두 App의 EC2 console에는 같은 container memory cgroup에서 `task=java`가 약 500~512MiB RSS에 도달해 `Memory cgroup out of memory: Killed process ... (java)`로 종료된 기록이 반복됐다. App 로그에서도 같은 구간에 upstream reset·connection refused·컨테이너 재시작이 이어졌다. 반면 PostgreSQL은 waiting lock 0, Redis와 발생기는 여유가 있었고 최종 처리 가능 Outbox backlog는 0이었다.

CPU credit도 거의 소진돼 latency 악화의 동시 요인이다. 다만 프로세스를 실제 종료시킨 직접 증거가 cgroup OOM이므로 최소 개선의 첫 대상은 App native/thread memory로 정한다.

## fan-out 단가 측정

각 조건은 취소 이벤트 100개를 만들고 3회 반복했다. 클라이언트 계약은 취소 전달 표본을 100·500·1,000개 정확히 확인했다. 서버 로그 표본 200·600·1,100개에는 준비 과정에서 relay한 참가 알림도 포함된다.

| 수신자 | Run ID | 서버 표본 | 서버 p50 / p95 / p99 | 최대 oldest processable age | batch | 결과 |
| ---: | --- | ---: | ---: | ---: | ---: | --- |
| 1 | `perf-20260811-fanout-fanout-r1-n1` | 200 | 1.547 / 4.210 / 4.692초 | 4.686초 | 13 | PASS |
| 1 | `perf-20260811-fanout-fanout-r1-n2` | 200 | 1.767 / 4.259 / 4.715초 | 3.178초 | 22 | PASS |
| 1 | `perf-20260811-fanout-fanout-r1-n3` | 200 | 2.458 / 4.607 / 4.896초 | 3.621초 | 19 | PASS |
| 5 | `perf-20260811-fanout-fanout-r5-n1` | 600 | 1.972 / 4.434 / 4.809초 | 3.655초 | 52 | PASS |
| 5 | `perf-20260811-fanout-fanout-r5-n2` | 600 | 1.961 / 4.549 / 4.845초 | 4.982초 | 59 | PASS |
| 5 | `perf-20260811-fanout-fanout-r5-n3` | 600 | 2.194 / 4.453 / 4.778초 | 3.828초 | 47 | PASS |
| 10 | `perf-20260811-fanout-fanout-r10-n1` | 1,100 | 2.218 / 4.505 / 4.897초 | 3.360초 | 80 | PASS |
| 10 | `perf-20260811-fanout-fanout-r10-n2` | 1,100 | 2.713 / 4.968 / 5.676초 | 3.402초 | 81 | PASS |
| 10 | `perf-20260811-fanout-fanout-r10-n3` | 1,100 | 2.368 / 4.742 / 5.004초 | 4.559초 | 83 | PASS |

모든 Run은 `processedCount == deliveryDelaySampleCount`, `failedCount=0`, `retryScheduledCount=0`, 최종 `processableOutboxCount=0`이었다. server-side p95 반복 표준편차는 1명 176.7ms, 5명 50.3ms, 10명 189.0ms다. 수신자 수가 1→10으로 늘어도 server-side p95 중앙값은 4.259→4.742초로 완만하게 증가했다.

클라이언트 관찰 p95는 순차 polling 비용을 포함해 1명 18.578~31.351초, 5명 28.841~29.959초, 10명 30.155~36.193초였다. 이 값은 서버 전달 지연 정본이 아니며, 특히 10명 조건은 사용자 관찰 경로의 polling 순서 비용을 별도로 줄여야 함을 보여준다.

## 최소 개선 후보와 재측정 계획

실제 변경은 이번 범위에서 구현하지 않는다. 다음 실행에서 한 번에 바꿀 후보는 **두 App의 Tomcat 최대 thread를 200에서 64로 제한하는 것 하나**다.

- Hikari max 8에 비해 Tomcat 200 thread는 blocking 요청이 쌓일 때 native stack·요청 버퍼가 cgroup memory를 먼저 소모한다.
- container memory limit을 바로 올리면 host available memory가 32~45MiB였던 `t4g.micro`에서 host OOM 위험을 옮길 뿐이다.
- broker나 DB 조정은 이번 최초 병목과 맞지 않는다.

후속 비교는 새 스택·같은 release 계열에서 CPU credit 시작 조건을 맞춘 뒤 다른 설정을 바꾸지 않고 다음 순서로 실행한다.

1. 0.5× 알림 혼합 부하 3회: OOM 0, App collector 오류 20% 이하, 최소 100개 서버 표본과 최종 backlog 0을 먼저 요구한다.
2. 0.5×가 유효 PASS이면 1×를 3회 실행한다. 0.5×가 유효 FAIL이면 상승하지 않는다.
3. 인증 1 req/s·2 req/s의 3분 탐색과 경계 15분 3회를 다시 실행한다.
4. 같은 Run별 App memory, host available memory, CPU credit, API p95·오류율, relay p95·oldest age를 현재 원자료와 비교한다.

이 후보가 OOM만 제거하고 p95를 만족시키지 못하면 다음 계획에서 CPU credit이 소진되지 않는 App 크기 또는 비-burstable App 유형을 비교한다. 두 변경을 한 Run에 함께 넣지 않는다.
