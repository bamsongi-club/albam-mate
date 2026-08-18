# ROOM T1·T2 반복 기준선 — INVALID (2026-08-18)

## 결론

Issue #778의 승인된 T1~T4 계약에 따라 T1/T2 8개 조건을 조건별 3회씩, 총 24개 독립 run으로 실행했다. 고정 source·배포 release·portable bundle·전용 AWS stack과 원격 phase는 정렬됐고, 24개 transport 결과는 모두 `PASS`였다.

그러나 승인 계약이 요구하는 전체 측정 증거가 없어 캠페인 판정은 `INVALID`이다.

- 계획: 8조건 × 3회 = 24 run
- 실제: 24 run, transport `PASS` 24/24
- 유효 판정: `PASS` 0, `FAIL` 0, `INVALID` 24
- 전체 ROOM 요청: 720건
- outcome 방정식: 24/24 일치 — success 550 + business 0 + concurrency 170 + unexpected 0 = 720
- start-skew: 24/24 gate 통과, 최대 3ms
- before/after snapshot·diagnosis: 24/24 `PASS`, 추가 T1/T2 불변식 실패 0건
- teardown: `run.sh down` exit 0, 확인된 test-owned compute/storage/public-IP/CloudWatch 잔여 0

T2 outcome별 latency p50·p95·p99·max와 T3 retrier/Coordinator·DB/connection 신호가 수집되지 않았다. 따라서 개별 portable `final-result.json`의 `PASS`는 원격 transport·snapshot/diagnosis 결과일 뿐, 승인된 전체 T1~T4 계약을 만족한 유효 `PASS`가 아니다. 이 문서는 성능 기준선으로 승격하지 않으며, 누락된 계측을 추정하거나 run을 합성하지 않는다.

근거 ledger와 각 run의 비밀 없는 artifact SHA-256은 [campaign evidence](evidence/room-t1-t2-repeated-baseline.json)에 있다. 원시 bundle은 local-only다.

## 측정 provenance

| 항목 | 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-18 03:50:29–05:32:05 |
| source / 배포 release | `92f5f667b732a5b6b9e6cd7dff5befd13354148a` / 동일 release 24/24 |
| 대상 환경 | `aws-dedicated-private-loadtest` |
| Issue 전용 stack | `perf-jiwon-778` |
| portable bundle | `albam-mate-room-k6-bundle`, schema 2, fixture schema 2, clean source 24/24 |
| k6 | `1.3.0` |
| 실행 경로 | portable bundle → infra `run.sh room-k6`; generic loadtest 미사용 |
| raw bundle | `build/k6/room/<run-id>/<fixture-id>/` local-only |
| release alignment | source SHA와 배포 release 동일, 24/24 |

## T1~T4 판정

| 계약 | 확인한 사실 | 판정 |
| --- | --- | --- |
| T1 반복 조건·provenance·실행 유효성 | 8조건·24회, source/release/environment/profile/options/UTC/artifact digest, start-skew gate 확인 | 부분 충족; T2/T3 누락으로 run 유효 PASS 승격 불가 |
| T2 outcome·지연 집계 | outcome count 방정식 24/24 일치, 전체 RPS·success/s·p50/p95/max는 보존했으나 outcome별 지연과 p99 없음 | `INVALID` |
| T3 retrier·DB 신호 | retrier/Coordinator attempt·retry·exhausted, DB query/transaction/lock, Hikari/connection signal 없음 | `INVALID` |
| T4 저장 불변식·판정 상태 | before/after diagnosis 24/24 PASS, 추가 snapshot invariant 실패 0, unexpected 4xx/5xx/contract failure 0 | 관측 범위 PASS; 전체 run은 INVALID |

## 조건 coverage

| 시나리오 | subcase | 조건 | 필요 | 실제 | transport PASS | 유효 PASS | 판정 |
| --- | --- | --- | ---: | ---: | ---: | ---: | --- |
| T1 | — | stress / hot / c4 | 3 | 3 | 3 | 0 | `INVALID` |
| T1 | — | stress / hot / c8 | 3 | 3 | 3 | 0 | `INVALID` |
| T1 | — | stress / spread / c4 | 3 | 3 | 3 | 0 | `INVALID` |
| T1 | — | stress / spread / c8 | 3 | 3 | 3 | 0 | `INVALID` |
| T2 | distinct | stress / distinct / hot / c4 | 3 | 3 | 3 | 0 | `INVALID` |
| T2 | distinct | stress / distinct / hot / c8 | 3 | 3 | 3 | 0 | `INVALID` |
| T2 | distinct | stress / distinct / spread / c4 | 3 | 3 | 3 | 0 | `INVALID` |
| T2 | distinct | stress / distinct / spread / c8 | 3 | 3 | 3 | 0 | `INVALID` |

모든 조건이 반복 횟수 자체는 충족했지만, T2/T3 필수 evidence가 공통으로 누락되어 유효 PASS는 0개다.

## 반복별 관측값

p50은 summary의 `med`를 사용했다. p99와 outcome별 latency artifact는 존재하지 않아 `N/A`로 남겼다. 전체 요청 outcome은 같은 요청 집합에서 합산했다. 업무 완료/s는 summary의 success outcome rate이며, 운영 SLO나 처리량 결론으로 해석하지 않는다.

| # | run ID | UTC 시작–종료 | p50 ms | p95 ms | p99 | max ms | 전체 RPS | 업무 완료/s | success/전체 | business | concurrency | unexpected | skew max ms | transport / campaign |
| ---: | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | room-t1-stress-hot-c4-r01 | 2026-08-18 03:50–03:54 | 141.629 | 645.568 | N/A | 658.065 | 0.207 | 0.155 | 15/20 | 0 | 5 | 0 | 1 | PASS / INVALID |
| 2 | room-t1-stress-hot-c4-r02 | 2026-08-18 03:59–04:03 | 110.086 | 142.451 | N/A | 151.011 | 0.209 | 0.157 | 15/20 | 0 | 5 | 0 | 1 | PASS / INVALID |
| 3 | room-t1-stress-hot-c4-r03 | 2026-08-18 04:03–04:07 | 105.639 | 147.852 | N/A | 148.593 | 0.209 | 0.157 | 15/20 | 0 | 5 | 0 | 1 | PASS / INVALID |
| 4 | room-t1-stress-hot-c8-r01 | 2026-08-18 04:07–04:11 | 142.087 | 213.146 | N/A | 217.173 | 0.416 | 0.187 | 18/40 | 0 | 22 | 0 | 1 | PASS / INVALID |
| 5 | room-t1-stress-hot-c8-r02 | 2026-08-18 04:11–04:15 | 122.360 | 154.804 | N/A | 169.670 | 0.416 | 0.156 | 15/40 | 0 | 25 | 0 | 1 | PASS / INVALID |
| 6 | room-t1-stress-hot-c8-r03 | 2026-08-18 04:15–04:19 | 110.393 | 134.206 | N/A | 134.979 | 0.415 | 0.156 | 15/40 | 0 | 25 | 0 | 2 | PASS / INVALID |
| 7 | room-t1-stress-spread-c4-r01 | 2026-08-18 04:19–04:23 | 50.102 | 63.952 | N/A | 67.630 | 0.209 | 0.209 | 20/20 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 8 | room-t1-stress-spread-c4-r02 | 2026-08-18 04:23–04:27 | 51.033 | 58.679 | N/A | 60.529 | 0.209 | 0.209 | 20/20 | 0 | 0 | 0 | 3 | PASS / INVALID |
| 9 | room-t1-stress-spread-c4-r03 | 2026-08-18 04:27–04:31 | 50.239 | 61.696 | N/A | 66.164 | 0.209 | 0.209 | 20/20 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 10 | room-t1-stress-spread-c8-r01 | 2026-08-18 04:31–04:35 | 65.230 | 99.026 | N/A | 118.525 | 0.416 | 0.416 | 40/40 | 0 | 0 | 0 | 2 | PASS / INVALID |
| 11 | room-t1-stress-spread-c8-r02 | 2026-08-18 04:36–04:39 | 62.603 | 85.406 | N/A | 89.064 | 0.416 | 0.416 | 40/40 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 12 | room-t1-stress-spread-c8-r03 | 2026-08-18 04:40–04:43 | 56.017 | 70.778 | N/A | 76.851 | 0.416 | 0.416 | 40/40 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 13 | room-t2-stress-hot-c4-r01 | 2026-08-18 04:44–04:47 | 56.946 | 129.689 | N/A | 133.345 | 0.209 | 0.157 | 15/20 | 0 | 5 | 0 | 1 | PASS / INVALID |
| 14 | room-t2-stress-hot-c4-r02 | 2026-08-18 04:48–04:51 | 44.098 | 55.273 | N/A | 56.114 | 0.209 | 0.157 | 15/20 | 0 | 5 | 0 | 1 | PASS / INVALID |
| 15 | room-t2-stress-hot-c4-r03 | 2026-08-18 04:52–04:55 | 45.841 | 56.706 | N/A | 72.912 | 0.209 | 0.167 | 16/20 | 0 | 4 | 0 | 1 | PASS / INVALID |
| 16 | room-t2-stress-hot-c8-r01 | 2026-08-18 04:56–04:59 | 62.035 | 86.818 | N/A | 102.183 | 0.416 | 0.187 | 18/40 | 0 | 22 | 0 | 1 | PASS / INVALID |
| 17 | room-t2-stress-hot-c8-r02 | 2026-08-18 04:59–05:03 | 62.262 | 99.649 | N/A | 113.474 | 0.416 | 0.167 | 16/40 | 0 | 24 | 0 | 1 | PASS / INVALID |
| 18 | room-t2-stress-hot-c8-r03 | 2026-08-18 05:03–05:07 | 57.848 | 69.917 | N/A | 75.181 | 0.416 | 0.177 | 17/40 | 0 | 23 | 0 | 1 | PASS / INVALID |
| 19 | room-t2-stress-spread-c4-r01 | 2026-08-18 05:08–05:11 | 28.708 | 36.110 | N/A | 37.943 | 0.209 | 0.209 | 20/20 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 20 | room-t2-stress-spread-c4-r02 | 2026-08-18 05:12–05:15 | 26.441 | 37.564 | N/A | 38.911 | 0.209 | 0.209 | 20/20 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 21 | room-t2-stress-spread-c4-r03 | 2026-08-18 05:16–05:19 | 27.767 | 34.898 | N/A | 41.538 | 0.209 | 0.209 | 20/20 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 22 | room-t2-stress-spread-c8-r01 | 2026-08-18 05:20–05:24 | 35.540 | 50.147 | N/A | 57.122 | 0.416 | 0.416 | 40/40 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 23 | room-t2-stress-spread-c8-r02 | 2026-08-18 05:24–05:28 | 32.312 | 47.927 | N/A | 62.065 | 0.416 | 0.416 | 40/40 | 0 | 0 | 0 | 1 | PASS / INVALID |
| 24 | room-t2-stress-spread-c8-r03 | 2026-08-18 05:28–05:32 | 31.209 | 53.641 | N/A | 58.702 | 0.416 | 0.416 | 40/40 | 0 | 0 | 0 | 1 | PASS / INVALID |

## 누락된 필수 계측

- T2: 동일 outcome 요청 집합에서 outcome별 p50·p95·p99·max를 산출할 artifact가 없다. summary에는 전체 `room_request_duration`의 p50/p95/max만 있고 p99도 없다.
- T3: T1 공통 retrier와 T2 전용 Coordinator의 attempt·retry·exhausted 분포가 없다.
- T3: 같은 UTC 구간에 연결된 DB query call/time, transaction duration, lock wait, connection/Hikari pending 신호가 없다.
- T3: 위 신호를 run·condition·outcome에 연결하는 구조화 artifact가 없다.
- T4 snapshot/diagnosis는 저장 불변식 관찰에 사용했지만, 그것만으로 T3 비용 신호나 전체 T1~T4 유효성을 대체하지 않는다.

## Teardown와 잔여 조회

| 대상 | 결과 |
| --- | --- |
| `run.sh down` | exit 0 |
| test-owned EC2 / EBS / public IPv4 / EIP | 확인된 잔여 0 |
| test-owned CloudWatch alarm/dashboard | 확인된 잔여 0 |
| Terraform state resource count | HTTP 403으로 최종 수치 확인 불가 |
| SSM 잔여 | invalid input으로 최종 수치 확인 불가 |
| configured ECR 조회 | not found/count 0 반환; shared ECR 잔여 범위 확정 불가 |

확인 불가 항목을 0으로 추정하지 않았다. raw bundle은 local-only로 보존하고, Git 산출물에는 비밀·토큰·URL·fixture/resource ID·raw SQL·raw log를 넣지 않는다.

## 검증과 다음 gate

실행 worktree에서 portable fixture/bundle/diagnosis/aggregate 관련 기존 검증과 `git diff --check`가 통과했으며, 이 문서 변경 후 링크 검사와 diff를 다시 실행한다.

다음 유효 campaign 전에는 다음을 별도 gate로 해결해야 한다.

1. T2 summary/runner가 outcome별 duration과 p99를 동일 요청 집합에서 보존하도록 한다.
2. T3 retrier/Coordinator 구조화 로그와 DB/connection 신호 수집 경로를 같은 UTC run window와 연결한다.
3. 누락 계측이 있으면 aggregate가 `INVALID`로 차단하는지 검증한다.
4. AWS teardown 뒤 Terraform state/SSM/ECR 조회 권한과 selector를 고쳐 잔여 범위를 확정한다.

이번 결과만으로 backoff·재시도 횟수·낙관/비관 락·flush/SQL 변경·운영 SLO를 결정하지 않는다.

## 재현과 원자료

재현은 [ROOM k6 실행 계약](../../../../load-tests/k6/jiwon/README.md)과 [campaign evidence](evidence/room-t1-t2-repeated-baseline.json)를 따른다. 원자료 24개 bundle은 local-only이며 evidence에는 runId, 조건, 비밀 없는 artifact SHA-256만 남겼다.
