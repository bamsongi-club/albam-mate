# ROOM T1·T2 반복 기준선 — PASS (2026-08-20)

## 결론

Issue #778의 승인된 T1~T4 계약에 따라 T1/T2 8개 조건을 조건별 3회씩, 총 24개 독립 Run으로 재실행했다. 수정된 summary provenance(6f2f1779)와 infra T3 수집 경로를 사용했으며, 24개 Run 모두 유효성 gate를 통과했다.

- 계획 / 실제: 8조건 × 3회 = 24 / 24 Run
- 유효 판정: PASS 24, FAIL 0, INVALID 0
- outcome equation: 556 success + 0 business + 164 concurrency + 0 unexpected = 720 ROOM requests
- T2 outcome별 latency p50/p95/p99/max: 24/24 Run 보존
- T3 resource signal: 24/24 PASS; raw T3 metrics/log 5종: 24/24
- before/after diagnosis: 24/24, 24/24 PASS
- start-skew: 24/24 gate 통과, 최대 2ms
- summary hash와 run-manifest 연결: 24/24
- teardown: Terraform destroy 94개 전용 리소스, run.sh status에서 stack 없음; 잔여 확인 EC2/EBS/EIP/CloudWatch alarm 0

이번 PASS는 승인된 T1~T4 측정 증거가 완비된 유효 기준선이라는 뜻이다. 운영 SLO나 최대 수용량을 결정하는 결과로 확대 해석하지 않는다. 원시 bundle은 local-only로 보존한다.

## 이전 INVALID 실행과 이번 재실행

이전 campaign room-t1-t2-repeated-baseline-2026-08-18은 T2 outcome별 latency/p99와 T3 계측이 없어 INVALID였다. 이번 재실행에서는 summary를 manifest 기록 뒤 다시 쓰던 provenance 오류를 6f2f1779에서 제거하고, 같은 UTC 실행 구간에 T3 collector·resource-signals를 연결했다. 이전 문서와 evidence는 삭제하지 않고 이 campaign으로 대체 관계를 기록한다.

실행 없이 생성된 local-only bundle ...v2-t1-hot-c4-r02는 24개 공식 Run에 포함하지 않았다.

## 측정 provenance

| 항목 | 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-19T16:47:15Z–2026-08-19T18:43:40Z |
| source / 배포 release | 6f2f1779fb3798380b92d80a4eda77387b22f9bc / 동일 release 24/24 |
| 대상 환경 | aws-room-k6 |
| portable bundle | albam-mate-room-k6-bundle, schema 2, fixture schema 2, clean source 24/24 |
| k6 | 1.3.0 |
| 실행 경로 | portable bundle → albam-mate-infra/run.sh room-k6 |
| 원자료 | build/k6/room/<run-id>/<fixture-id>/ local-only |

## 조건 coverage

| 시나리오 | mode | concurrency | 필요 반복 | 실제 반복 | 유효 PASS | 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| T1 | undefined | undefined | 3 | 3 | 3 | PASS |
| T1 | undefined | undefined | 3 | 3 | 3 | PASS |
| T1 | undefined | undefined | 3 | 3 | 3 | PASS |
| T1 | undefined | undefined | 3 | 3 | 3 | PASS |
| T2 distinct | undefined | undefined | 3 | 3 | 3 | PASS |
| T2 distinct | undefined | undefined | 3 | 3 | 3 | PASS |
| T2 distinct | undefined | undefined | 3 | 3 | 3 | PASS |
| T2 distinct | undefined | undefined | 3 | 3 | 3 | PASS |

## 증거 gate

| 계약 | 확인한 사실 | 판정 |
| --- | --- | --- |
| T1 반복·provenance | 8조건·24회, source/release/k6/UTC/artifact digest 정렬 | PASS |
| T2 outcome·지연 | success/concurrency 등 outcome별 p50/p95/p99/max과 outcome equation 24/24 | PASS |
| T3 retrier·DB·connection | resource-signals 24/24, retry·DB·transaction·lock·Hikari 신호와 raw metrics/log 5종 | PASS |
| T4 저장 불변식·실행 상태 | before/after diagnosis 24/24 PASS, k6 exit 0, final PASS | PASS |

## 반복별 관측값

latency는 각 Run의 resource-signals.json outcome coverage를 사용했다. success outcome 지연과 concurrency outcome 지연을 분리했으며, —는 해당 outcome 요청이 없음을 뜻한다. 표의 query/lock은 T3 연결성을 보여주는 관찰값이며 운영 DB SLO가 아니다.

| # | 조건 | UTC 시작–종료 | 요청 | success/concurrency | success p50/p95/p99/max ms | concurrency p50/p95/p99/max ms | query calls / lock wait | 판정 |
| ---: | --- | --- | ---: | ---: | --- | --- | ---: | --- |
| 1 | T1 / hot / c4 | 2026-08-19 16:47–16:48 | 20 | 15/5 | 170.2 / 775.7 / 1159.3 / 1255.1 | 203.7 / 1071.1 / 1233.7 / 1274.4 | 627 / 1 | PASS |
| 2 | T1 / hot / c4 | 2026-08-19 16:55–16:57 | 20 | 15/5 | 108.3 / 199.1 / 202.4 / 203.3 | 183.4 / 192.0 / 193.7 / 194.1 | 596 / 0 | PASS |
| 3 | T1 / hot / c4 | 2026-08-19 17:01–17:03 | 20 | 15/5 | 93.3 / 167.6 / 168.2 / 168.3 | 162.2 / 166.9 / 167.5 / 167.7 | 594 / 1 | PASS |
| 4 | T1 / hot / c8 | 2026-08-19 17:06–17:08 | 40 | 18/22 | 120.1 / 235.4 / 269.8 / 278.4 | 156.3 / 175.7 / 184.3 / 186.5 | 918 / 0 | PASS |
| 5 | T1 / hot / c8 | 2026-08-19 17:11–17:13 | 40 | 15/25 | 109.9 / 156.2 / 168.9 / 172.0 | 154.2 / 195.9 / 200.8 / 202.4 | 840 / 0 | PASS |
| 6 | T1 / hot / c8 | 2026-08-19 17:16–17:18 | 40 | 16/24 | 93.4 / 146.7 / 167.6 / 172.8 | 123.6 / 133.2 / 134.7 / 135.2 | 879 / 0 | PASS |
| 7 | T1 / spread / c4 | 2026-08-19 17:21–17:23 | 20 | 20/0 | 65.9 / 99.0 / 99.3 / 99.4 | — | 600 / 0 | PASS |
| 8 | T1 / spread / c4 | 2026-08-19 17:26–17:27 | 20 | 20/0 | 62.0 / 84.0 / 88.2 / 89.2 | — | 574 / 0 | PASS |
| 9 | T1 / spread / c4 | 2026-08-19 17:30–17:32 | 20 | 20/0 | 72.0 / 102.9 / 111.0 / 113.0 | — | 565 / 0 | PASS |
| 10 | T1 / spread / c8 | 2026-08-19 17:35–17:36 | 40 | 40/0 | 69.4 / 171.2 / 198.5 / 209.3 | — | 1049 / 0 | PASS |
| 11 | T1 / spread / c8 | 2026-08-19 17:40–17:41 | 40 | 40/0 | 72.4 / 91.5 / 94.9 / 96.1 | — | 1033 / 0 | PASS |
| 12 | T1 / spread / c8 | 2026-08-19 17:45–17:47 | 40 | 40/0 | 73.6 / 92.4 / 97.1 / 99.8 | — | 1045 / 0 | PASS |
| 13 | T2 distinct / hot / c4 | 2026-08-19 17:50–17:52 | 20 | 15/5 | 52.7 / 147.7 / 157.0 / 159.3 | 95.9 / 145.7 / 154.7 / 157.0 | 453 / 0 | PASS |
| 14 | T2 distinct / hot / c4 | 2026-08-19 17:55–17:56 | 20 | 15/5 | 44.9 / 65.5 / 70.3 / 71.5 | 75.2 / 76.8 / 76.9 / 76.9 | 443 / 0 | PASS |
| 15 | T2 distinct / hot / c4 | 2026-08-19 18:00–18:01 | 20 | 17/3 | 42.5 / 77.8 / 80.0 / 80.5 | 58.0 / 77.9 / 79.7 / 80.1 | 443 / 0 | PASS |
| 16 | T2 distinct / hot / c8 | 2026-08-19 18:05–18:06 | 40 | 20/20 | 54.9 / 90.6 / 92.6 / 93.2 | 66.9 / 107.3 / 113.8 / 115.4 | 785 / 0 | PASS |
| 17 | T2 distinct / hot / c8 | 2026-08-19 18:10–18:11 | 40 | 18/22 | 56.3 / 85.6 / 99.3 / 102.7 | 63.2 / 89.3 / 93.2 / 94.3 | 757 / 0 | PASS |
| 18 | T2 distinct / hot / c8 | 2026-08-19 18:14–18:16 | 40 | 17/23 | 46.2 / 103.6 / 112.1 / 114.3 | 72.1 / 99.3 / 109.8 / 112.8 | 758 / 0 | PASS |
| 19 | T2 distinct / spread / c4 | 2026-08-19 18:19–18:20 | 20 | 20/0 | 30.9 / 66.7 / 66.8 / 66.9 | — | 342 / 0 | PASS |
| 20 | T2 distinct / spread / c4 | 2026-08-19 18:23–18:25 | 20 | 20/0 | 34.1 / 64.7 / 80.2 / 84.1 | — | 338 / 0 | PASS |
| 21 | T2 distinct / spread / c4 | 2026-08-19 18:28–18:29 | 20 | 20/0 | 31.2 / 42.4 / 48.0 / 49.5 | — | 329 / 0 | PASS |
| 22 | T2 distinct / spread / c8 | 2026-08-19 18:32–18:34 | 40 | 40/0 | 39.2 / 68.1 / 72.9 / 74.8 | — | 530 / 0 | PASS |
| 23 | T2 distinct / spread / c8 | 2026-08-19 18:37–18:39 | 40 | 40/0 | 37.0 / 57.0 / 66.8 / 70.9 | — | 518 / 0 | PASS |
| 24 | T2 distinct / spread / c8 | 2026-08-19 18:41–18:43 | 40 | 40/0 | 32.7 / 48.3 / 60.9 / 62.1 | — | 503 / 0 | PASS |

## 집계 관찰값

- 전체 요청 720건: success 556, business 0, concurrency 164, unexpected 0
- success 지연 범위: p50 0–0ms, p95 0–0ms, p99 0–0ms, max 0–0ms
- T3 누적 query calls 15519, query time 15321.1ms, 최대 lock wait 1, Hikari pending 최대 0
- common retrier exhausted 누적 86, Coordinator exhausted 누적 0

이 수치는 이 campaign의 조건·release·환경에 한정된다. cache/SQL/lock 정책, 재시도 횟수, 운영 capacity를 이 결과만으로 결정하지 않는다.

## 재현과 원자료

재현은 [ROOM k6 실행 계약](../../../../load-tests/k6/jiwon/README.md)과 [campaign evidence](evidence/room-t1-t2-repeated-baseline-2026-08-20.json)를 따른다. 원자료 24개 bundle은 local-only이며 evidence에는 비밀 없는 artifact SHA-256과 집계값만 남겼다.
