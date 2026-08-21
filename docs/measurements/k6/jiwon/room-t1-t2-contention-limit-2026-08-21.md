# ROOM T1·T2 경합 한계 — PASS (2026-08-21)

## 결론

Issue #788의 활성 승인 계약(issuecomment-5359543332)에 따라 네 조건을 조건별 3회씩 실행했다. 유효한 run만 집계했으며, 12개 모두 필수 gate를 통과했다.

- 계획 / 실제 / 유효: 4조건 × 3회 = 12 / 12 / 12
- Run 판정: `PASS 12`, `FAIL 0`, `INVALID 0`
- outcome equation: `587 success + 0 business + 283 concurrency + 0 unexpected = 870 ROOM requests`
- `dropped_iterations=0`: 12/12; `room_start_skew_ms max < 1000ms`: 12/12, 최대 2ms
- 발생기 유효성: CPU·RSS·송수신 network 관측 12/12, 최대 k6 CPU 15%, 최대 RSS 73,695,232 bytes
- T3: `resource-signals PASS 12/12`, raw T3 artifact set 12/12, query calls 16,083, query time 24,766.97ms, lock wait 최대 0, Hikari pending 최대 0
- correctness: before/after diagnosis 및 ROOM/대기열 불변식 12/12 PASS, 예상 밖 4xx·5xx·contract failure 0

이번 결과는 승인된 매트릭스 안에서의 유효한 측정 증거다. `T1 hot c16`, 모든 `c32`, `mixed`, `soak`는 활성 계약에 따라 실행하지 않았으므로 시스템의 절대 최대 수용량으로 해석하지 않는다. 원시 bundle은 local-only이며, 비식별 SHA-256·집계·gate만 이 저장소에 보존한다.

## 이전 INVALID 실행과 이번 재실행

이전 #788 시도는 `dropped_iterations` 원시 지표가 없고 load-generator CPU/자원 CSV가 bundle에 승격되지 않아 필수 T3 증거를 충족하지 못했다. 이번 실행은 앱의 write k6 options에 `dropped_iterations: count==0` gate를 추가하고, infra의 ROOM collector/aggregator가 load-generator CSV와 network summary를 함께 수집하도록 한 뒤 새 release로 재실행했다. 이전 원시 결과는 대체 근거로 포함하지 않았고, 이번 12개 run만 공식 campaign으로 선택했다.

## 측정 provenance

| 항목 | 값 |
| --- | --- |
| 실행 구간 | UTC `2026-08-21T04:36:12Z`–`2026-08-21T05:31:25Z` |
| 앱 source / 배포 release | `d8c112a7ad11c4cae6ff5427fd40af4c7f4577ec` / 동일 release 12/12 |
| infra runner | `5607cbbd55ae902a8212d72e30716d67bf2ea4bb` |
| 대상 환경 | `aws-room-k6` |
| portable bundle | `albam-mate-room-k6-bundle`, schema 2, fixture schema 2, clean source 12/12 |
| k6 | `1.3.0`, linux/arm64 |
| 실행 경로 | portable bundle → `albam-mate-infra/run.sh room-k6` |
| 원자료 | `build/k6/room/<run-id>/<fixture-id>/` local-only |

## 조건 coverage

| 시나리오 | mode | concurrency | 필요 반복 | 실제 반복 | 유효 PASS | 판정 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| T1 | hot | 10 | 3 | 3 | 3 | PASS |
| T1 | spread | 16 | 3 | 3 | 3 | PASS |
| T2 distinct | hot | 16 | 3 | 3 | 3 | PASS |
| T2 distinct | spread | 16 | 3 | 3 | 3 | PASS |

실행 제외: T1 hot c16, 모든 c32, mixed, soak.

## 증거 gate

| 계약 | 확인한 사실 | 판정 |
| --- | --- | --- |
| T1/T2 반복·provenance | 4조건·12회, 동일 source/release/k6, UTC window, fixture/manifest/summary/resource/raw artifact digest 보존 | PASS |
| T2 outcome·지연 | 각 run의 success/business/concurrency/unexpected count와 p50/p95/p99/max 보존; equation 12/12 | PASS |
| T3 발생기·실행 | load-generator CPU/RSS/network 12/12, `dropped_iterations=0` 12/12, start skew 최대 2ms | PASS |
| T3 DB·retry | query/transaction/lock/Hikari와 common retrier/coordinator 신호 12/12 | PASS |
| T4 correctness | before/after diagnosis 및 capacity·activeParticipantCount·FIFO/order·중복 불변식 12/12 PASS, 예상 밖 4xx/5xx 0 | PASS |

## 반복별 관측값

지연은 각 run의 outcome별 p50/p95/p99/max(ms)이며, `—`는 해당 outcome 요청이 없음을 뜻한다. query/lock과 generator 값은 해당 run의 T3 관찰값이다.

| # | 조건 | UTC 시작–종료 | 요청 | success/business/concurrency/unexpected | success p50/p95/p99/max ms | concurrency p50/p95/p99/max ms | query calls / lock wait | k6 CPU / dropped / skew | 판정 |
| ---: | --- | --- | ---: | --- | --- | --- | ---: | --- | --- |
| 1 | T1 / hot / c10 | 04:36–04:37 | 50 | 15/0/35/0 | 199.4 / 442.8 / 493.8 / 506.6 | 254.3 / 528.3 / 540.7 / 546.0 | 1090 / 0 | 6% / 0 / 1ms | PASS |
| 2 | T1 / hot / c10 | 04:43–04:45 | 50 | 17/0/33/0 | 160.5 / 242.3 / 272.3 / 279.8 | 195.3 / 272.1 / 278.3 / 280.7 | 1084 / 0 | 9% / 0 / 1ms | PASS |
| 3 | T1 / hot / c10 | 04:48–04:49 | 50 | 16/0/34/0 | 116.1 / 223.8 / 235.8 / 238.9 | 180.1 / 257.6 / 262.9 / 263.0 | 1072 / 0 | 7% / 0 / 1ms | PASS |
| 4 | T1 / spread / c16 | 04:52–04:54 | 80 | 80/0/0/0 | 122.7 / 591.4 / 594.8 / 595.6 | — | 1958 / 0 | 15% / 0 / 2ms | PASS |
| 5 | T1 / spread / c16 | 04:57–04:59 | 80 | 80/0/0/0 | 128.0 / 198.0 / 204.5 / 209.7 | — | 1842 / 0 | 12% / 0 / 1ms | PASS |
| 6 | T1 / spread / c16 | 05:02–05:03 | 80 | 80/0/0/0 | 110.8 / 153.8 / 190.5 / 199.2 | — | 1969 / 0 | 13% / 0 / 1ms | PASS |
| 7 | T2 distinct / hot / c16 | 05:06–05:08 | 80 | 16/0/64/0 | 95.8 / 165.2 / 171.2 / 172.7 | 143.5 / 205.0 / 221.6 / 224.5 | 1476 / 0 | 10% / 0 / 1ms | PASS |
| 8 | T2 distinct / hot / c16 | 05:11–05:13 | 80 | 21/0/59/0 | 98.5 / 161.2 / 179.1 / 183.6 | 133.8 / 177.7 / 186.3 / 192.9 | 1449 / 0 | 9% / 0 / 1ms | PASS |
| 9 | T2 distinct / hot / c16 | 05:15–05:17 | 80 | 22/0/58/0 | 75.7 / 121.1 / 123.8 / 124.3 | 106.2 / 124.0 / 129.7 / 130.1 | 1390 / 0 | 10% / 0 / 1ms | PASS |
| 10 | T2 distinct / spread / c16 | 05:20–05:22 | 80 | 80/0/0/0 | 59.6 / 98.0 / 121.8 / 129.5 | — | 911 / 0 | 13% / 0 / 2ms | PASS |
| 11 | T2 distinct / spread / c16 | 05:25–05:26 | 80 | 80/0/0/0 | 51.2 / 100.6 / 118.3 / 118.3 | — | 903 / 0 | 13% / 0 / 1ms | PASS |
| 12 | T2 distinct / spread / c16 | 05:29–05:31 | 80 | 80/0/0/0 | 62.2 / 114.5 / 127.2 / 131.6 | — | 939 / 0 | 13% / 0 / 1ms | PASS |

## 집계 관찰값

서로 다른 run percentile을 평균내지 않고 조건별 반복 범위로 보존했다.

| 조건 | 요청 / outcome | success p50 범위 | success p95 범위 | success p99 범위 | success max 범위 |
| --- | ---: | --- | --- | --- | --- |
| T1 hot c10 | 150 / 48·0·102·0 | 116.1–199.4ms | 223.8–442.8ms | 235.8–493.8ms | 238.9–506.6ms |
| T1 spread c16 | 240 / 240·0·0·0 | 110.8–128.0ms | 153.8–591.4ms | 190.5–594.8ms | 199.2–595.6ms |
| T2 distinct hot c16 | 240 / 59·0·181·0 | 75.7–98.5ms | 121.1–165.2ms | 123.8–179.1ms | 124.3–183.6ms |
| T2 distinct spread c16 | 240 / 240·0·0·0 | 51.2–62.2ms | 98.0–114.5ms | 118.3–127.2ms | 118.3–131.6ms |

`outcome` 열은 success/business/concurrency/unexpected 순서다. T3 전체 관찰은 query calls 16,083, query time 24,766.97ms, 최대 lock wait 0, Hikari pending 최대 0, common retrier exhausted 102, coordinator exhausted 0이다. 이 수치는 이 release·조건·AWS 실행 창에 한정되며 운영 SLO나 절대 capacity로 확대 해석하지 않는다.

## 경합 한계와 curve disposition

승인된 최대 단계인 c16의 네 조건이 모두 유효 PASS였으므로 이 campaign의 disposition은 `PASS_AT_APPROVED_MAX`다. c32를 실행하지 않았기 때문에 c16보다 높은 부하에서의 capacity 또는 failure boundary는 결정하지 않는다. 추가 부하 측정은 별도 승인과 별도 campaign으로만 진행한다.

## Teardown과 재현

동일 infra stack은 destroy exit 0으로 정리했다. 이후 `run.sh status`는 `올라온 스택이 없습니다. ./run.sh up 으로 만드세요.`를 반환했고, Terraform managed state는 비어 있었다. AWS 잔여 조회에서도 비종료 EC2·EBS·EIP·CloudWatch alarm·VPC·subnet·route table·IGW·ENI·security group·S3·IAM role/profile 및 `perf-jiwon` 태그 Route53 zone은 0개였다. Route53의 기존 공유 zone 1개와 공유 ECR/SSM은 campaign-owned resource가 아니므로 삭제하지 않았다. Resource Groups Tagging API의 31개 과거 태그 기록(종료 인스턴스/삭제 리소스)은 현재 실행·과금 리소스가 아니며, 상세 수치는 evidence의 `teardown.confirmedResidualQuery`에 보존했다.

재현 명령과 bundle 구조는 [ROOM k6 실행 계약](../../../../load-tests/k6/jiwon/README.md)을 따른다. 저장소에는 [campaign evidence](evidence/room-t1-t2-contention-limit-2026-08-21.json)만 보존하며, password·token·URL·fixture/resource ID·raw SQL·raw log는 커밋하지 않는다.
