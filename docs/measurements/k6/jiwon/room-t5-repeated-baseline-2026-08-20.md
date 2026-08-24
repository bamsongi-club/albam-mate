# ROOM T5 역할·ACTIVE 규모별 반복 조회 기준선 — PASS (2026-08-20)

## 결론

Issue #779의 승인된 T5 계약에 따라 public·host·participant × ACTIVE scale 1·10의 6개 조건을 고정 read profile로 조건별 3회, 총 18개 독립 Run으로 재실행했다. 수정된 profileImageUrl response contract verifier(17d5dee0)를 배포했고, 모든 Run과 3개 comparison group이 유효성 gate를 통과했다.

- 계획 / 실제: 6조건 × 3회 = 18 / 18 Run
- 유효 판정: PASS 18, FAIL 0, INVALID 0
- 공통 read profile: 10 VU / 60 seconds / think time 0ms, 18/18 일치
- T5 comparison: 3/3 group PASS, 각 fixtureCount 6, accepted fixture 18/18
- response contract failure: 0; 전체 요청 465564건 모두 success
- T3 resource signal: 18/18 PASS; raw T3 metrics/log 5종: 18/18
- before/after diagnosis: 18/18, 18/18 PASS
- start-skew: 18/18 gate 통과, 최대 2ms
- summary hash와 run-manifest 연결: 18/18
- teardown: Terraform destroy 94개 전용 리소스, run.sh status에서 stack 없음; 잔여 확인 EC2/EBS/EIP/CloudWatch alarm 0

이번 PASS는 승인된 T5 반복·response contract·comparison·T3 증거가 완비된 유효 기준선이라는 뜻이다. 역할·scale별 최대 수용량이나 운영 SLO를 결정하는 결과로 확대 해석하지 않는다. 원시 bundle은 local-only로 보존한다.

## 이전 INVALID 실행과 이번 재실행

이전 campaign room-t5-repeated-baseline-2026-08-18은 조건별 1회만 실행되어 18회 반복 gate를 충족하지 못했고 comparison accepted fixture도 0이었다. 이번 재실행 직전의 room-t5-repeated-baseline-20260820-v2b-r01은 host/1에서 현재 API의 profileImageUrl null을 k6 verifier가 허용하지 않아 중단됐다. 17d5dee0에서 nickname summary를 nickname + optional/null profileImageUrl 계약으로 정렬한 뒤 새 runId로 18회를 처음부터 실행했다. 이전 INVALID evidence와 중단 bundle은 삭제하지 않았다.

## 측정 provenance

| 항목 | 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-19T19:16:55Z–2026-08-19T20:28:24Z |
| source / 배포 release | 17d5dee05bf797ec46160e8cad95b8861473064a / 동일 release 18/18 |
| 대상 환경 | aws-room-k6 |
| portable bundle | albam-mate-room-k6-bundle, schema 2, fixture schema 2, clean source 18/18 |
| k6 | 1.3.0 |
| read profile | 10 VU / 60 seconds / 0ms think time |
| 실행 경로 | portable bundle → albam-mate-infra/run.sh room-k6 |
| 원자료 | build/k6/room/<run-id>/<fixture-id>/ local-only |

## 조건 coverage

| role | ACTIVE scale | 필요 반복 | 실제 반복 | 유효 PASS | 판정 |
| --- | ---: | ---: | ---: | ---: | --- |
| public | 1 | 3 | 3 | 3 | PASS |
| public | 10 | 3 | 3 | 3 | PASS |
| host | 1 | 3 | 3 | 3 | PASS |
| host | 10 | 3 | 3 | 3 | PASS |
| participant | 1 | 3 | 3 | 3 | PASS |
| participant | 10 | 3 | 3 | 3 | PASS |

## comparison group gate

| group runId | comparison | fixture | read profile | comparison artifact SHA-256 | 판정 |
| --- | --- | ---: | --- | --- | --- |
| room-t5-repeated-baseline-20260820-v3-r01 | PASS | 6/6 | 10 VU / 60s / 0ms | 015efd66f384e5163765b465102437d699d47efe557227cb3131b244bc50c15e |
| room-t5-repeated-baseline-20260820-v3-r02 | PASS | 6/6 | 10 VU / 60s / 0ms | a9ab2e0da02d189558e84733d3eb2db20514b514373cc005b42e0b9d5a23b9e1 |
| room-t5-repeated-baseline-20260820-v3-r03 | PASS | 6/6 | 10 VU / 60s / 0ms | 5b1acb3eae3ccba5bd7075916db33f3fef6a2e6928e79ae1b9e328d4b99ef5b6 |

## 증거 gate

| 계약 | 확인한 사실 | 판정 |
| --- | --- | --- |
| T1 반복·provenance | 6 role/scale 조건·18회, source/release/profile/UTC/artifact digest 정렬 | PASS |
| T2 response shape·security | 18/18 contract failure 0, 전체 465564 요청 success | PASS |
| T3 DB·connection | resource-signals 18/18, query/transaction/lock/Hikari/PostgreSQL 신호와 raw metrics/log 5종 | PASS |
| T4 comparison·불변식 | 3/3 comparison PASS, accepted fixture 18/18, before/after diagnosis 18/18 PASS | PASS |

## 반복별 관측값

latency는 각 Run의 resource-signals.json success outcome coverage를 사용했다. query/lock은 같은 UTC 실행 window에 연결된 T3 관찰값이며 운영 DB SLO가 아니다.

| # | role / scale | group | UTC 시작–종료 | 요청 | success p50/p95/p99/max ms | RPS | query calls / lock wait | 판정 |
| ---: | --- | --- | --- | ---: | --- | ---: | ---: | --- |
| 1 | public / 1 | r01 | 2026-08-19 19:16–19:18 | 16362 | 29.3 / 75.4 / 116.2 / 525.2 | 216.43 | 131065 / 0 | PASS |
| 2 | host / 1 | r01 | 2026-08-19 19:25–19:26 | 24214 | 21.0 / 44.0 / 58.7 / 456.0 | 322.35 | 218056 / 0 | PASS |
| 3 | participant / 1 | r01 | 2026-08-19 19:33–19:34 | 25445 | 21.3 / 38.7 / 49.3 / 138.9 | 338.73 | 254590 / 0 | PASS |
| 4 | participant / 10 | r01 | 2026-08-19 19:37–19:38 | 25011 | 21.2 / 40.7 / 51.8 / 148.2 | 332.84 | 250243 / 0 | PASS |
| 5 | public / 10 | r01 | 2026-08-19 19:21–19:22 | 27848 | 18.5 / 37.7 / 52.7 / 182.9 | 370.70 | 223002 / 0 | PASS |
| 6 | host / 10 | r01 | 2026-08-19 19:29–19:30 | 23323 | 21.5 / 46.9 / 63.4 / 264.9 | 309.32 | 210149 / 0 | PASS |
| 7 | participant / 10 | r02 | 2026-08-19 20:02–20:03 | 24025 | 21.7 / 42.8 / 54.4 / 460.9 | 319.84 | 240386 / 0 | PASS |
| 8 | participant / 1 | r02 | 2026-08-19 19:58–19:59 | 26141 | 20.9 / 37.6 / 48.5 / 79.1 | 348.01 | 261642 / 0 | PASS |
| 9 | public / 1 | r02 | 2026-08-19 19:41–19:43 | 29620 | 17.9 / 33.2 / 42.8 / 153.5 | 394.27 | 237214 / 0 | PASS |
| 10 | public / 10 | r02 | 2026-08-19 19:45–19:47 | 28742 | 18.0 / 35.8 / 48.6 / 144.0 | 382.67 | 230097 / 0 | PASS |
| 11 | host / 10 | r02 | 2026-08-19 19:54–19:55 | 26140 | 20.1 / 38.7 / 49.5 / 169.8 | 347.89 | 235490 / 0 | PASS |
| 12 | host / 1 | r02 | 2026-08-19 19:49–19:51 | 26605 | 19.9 / 37.9 / 48.5 / 121.1 | 354.17 | 239579 / 0 | PASS |
| 13 | host / 10 | r03 | 2026-08-19 20:18–20:20 | 26000 | 20.1 / 39.0 / 51.0 / 200.6 | 346.12 | 234146 / 0 | PASS |
| 14 | participant / 10 | r03 | 2026-08-19 20:27–20:28 | 24992 | 21.4 / 39.8 / 51.6 / 192.1 | 332.73 | 250055 / 0 | PASS |
| 15 | host / 1 | r03 | 2026-08-19 20:14–20:16 | 27225 | 19.7 / 35.8 / 46.1 / 337.3 | 362.40 | 245167 / 0 | PASS |
| 16 | participant / 1 | r03 | 2026-08-19 20:22–20:24 | 25162 | 21.3 / 39.4 / 51.3 / 187.8 | 335.00 | 251752 / 0 | PASS |
| 17 | public / 1 | r03 | 2026-08-19 20:06–20:07 | 29139 | 17.9 / 34.9 / 46.1 / 165.6 | 387.86 | 233271 / 0 | PASS |
| 18 | public / 10 | r03 | 2026-08-19 20:10–20:11 | 29570 | 18.0 / 32.9 / 42.3 / 111.3 | 393.66 | 236803 / 0 | PASS |

## 집계 관찰값

- 전체 요청 465564건: success 465564, business 0, concurrency 0, unexpected 0; contract failure 0
- success 지연 범위: p50 17.9–29.3ms, p95 32.9–75.4ms, p99 42.3–116.2ms, max 79.1–525.2ms
- RPS 관찰 범위 216.43–394.27, T3 누적 query calls 4182707, query time 50167.6ms
- lock wait 최대 0, Hikari pending 최대 0

이 수치는 이 campaign의 조건·release·환경에 한정된다. 역할별 성능 순위, cache/SQL 정책, 운영 capacity를 이 결과만으로 결정하지 않는다.

## 재현과 원자료

재현은 [ROOM k6 T5 실행 계약](../../../../load-tests/k6/jiwon/README.md#제공-시나리오)과 [campaign evidence](evidence/room-t5-repeated-baseline-2026-08-20.json)를 따른다. 원자료 18개 bundle과 3개 comparison artifact는 local-only이며 evidence에는 비밀 없는 artifact SHA-256과 집계값만 남겼다.
