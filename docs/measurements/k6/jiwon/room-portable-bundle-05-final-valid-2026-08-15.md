# [05/05] ROOM portable bundle 최종 유효 매트릭스 — PASS (2026-08-15)

## 결론

공식 matrix 25개가 모두 `PASS`했으므로, 이 release·전용 환경·고정 fixture 조건에서는 T1~T5의 HTTP 응답 계약과 사후 DB 불변식 회귀를 발견하지 못했다. 이 문서는 이후 변경 전후에 같은 correctness 조건을 대조할 현재 기준선이다.

- Campaign ID: `room-k6-final-clean-2026-08-15`
- 캠페인 종료 상태: [`completed-with-limitations`](README.md)
- 측정 증거 판정: 25/25 `PASS`, T5 comparison 6/6 `PASS`
- 문서 상태: [`current`](README.md)
- 문서 인덱스: [Jiwon k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json)
- 대체 관계: 01~04 실패·중단 이력을 성능 기준선에서 제외하고 현재 correctness 기준선으로 사용

- 모든 Run의 prepare, resource query, before snapshot, k6, after snapshot phase가 `0`이었다.
- 예상 밖 4xx, server failure, contract failure는 모든 Run에서 0건이었다.
- p50·p95·RPS는 조건별 단일 관찰값이며 p99를 수집하지 않았으므로 성능 SLO·최대 용량이나 락 전략을 결정하지 않는다.

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-14 15:39:20~17:01:09 / KST 2026-08-15 00:39:20~02:01:09 |
| source / 배포 release | `92f5f667b732a5b6b9e6cd7dff5befd13354148a` / 동일 revision, 25개 Run 모두 정렬 |
| portable bundle | schema v2 / fixture schema v2, clean source 25/25 |
| 발생기 | k6 `1.3.0` |
| 실행 범위 | T1 6개·T2 7개·T3 3개·T4 3개·T5 6개, 총 25개를 각각 한 번 실행 |
| runner | ROOM portable bundle → infra `run.sh room-k6`; generic `loadtest` 제외 |
| 측정 전제 | clean app checkout, bundle source와 배포 release 정렬, Terraform plan 무변경 |
| 유효성 gate | final result `PASS` 25/25, 모든 remote phase `0`, T5 comparison `PASS` 6/6 |
| 원자료 | 로컬 `build/k6/room/`만 보존; 비밀번호·토큰·세션·CSRF·URL·실제 fixture/resource ID·원시 SQL·로그는 Git에 기록하지 않음 |

정본인 [ROOM k6 측정 목적과 시나리오](../../../../load-tests/k6/jiwon/README.md)는 동시성 오류·불변식 위반·공통 병목을 찾고, 같은 조건의 개선 전후를 비교하는 것을 이 측정의 우선 목적으로 둔다. 이 campaign이 답하려 한 질문은 **정렬된 release·전용 환경·고정 fixture에서 ROOM 핵심 HTTP 흐름의 응답·DB 불변식 회귀가 있는가**이다. 따라서 이번 결과는 이후 변경 전후에 같은 correctness 조건을 대조할 기준선이 된다.

동시에 이 campaign은 단일 실행 관찰값을 남기는 correctness 측정이다. production 락 전략, 성능 SLO, 최대 용량, 병목의 근본 원인을 결정하는 실험은 아니다. 특히 `ROOM_CONCURRENT_MODIFICATION`은 허용된 재시도 소진 결과를 관찰하는 지표이며, 낙관락·비관락 선택은 별도 측정과 승인이 필요하다.

## 테스트 진행 과정

1. clean app checkout에서 bundle source와 배포 release 정렬을 확인하고, 각 portable bundle의 immutable 입력을 검증했다.
2. generic `loadtest`를 사용하지 않고, T1 6개·T2 7개·T3 3개·T4 3개·T5 6개, 총 25개를 `run.sh room-k6`으로 순차 실행했다. 각 조합은 한 번씩 실행했으므로 아래 latency·RPS는 반복 측정의 범위나 중앙값이 아닌 단일 관찰값이다.
3. 각 실행은 `validate → execution options → prepare → resource query → hydrate → before snapshot/diagnosis → k6 → after snapshot/diagnosis → aggregate` 순으로 진행했다.
4. T5 여섯 role×scale 실행 뒤, 동일 read profile·실행 결과 artifact·start-skew 관측을 comparison gate로 다시 확인했다.
5. [canonical campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json)의 Run ledger에 위 25개만 `included`로 명시하고, local-only 원자료의 source·입력·실행 결과 artifact 무결성 식별값을 연결했다. 앞선 `01`–`04` campaign은 이 결론 계산에 섞지 않았다.
6. 모든 Run의 final 판정과 T5 comparison을 확인한 뒤 test-owned P1 stack을 teardown하고 잔여 resource를 조회했다.

## 부하 campaign

### 공통 판정

- final result: `PASS` 25/25
- remote phase: prepare, resource query, before snapshot, k6, after snapshot 모두 `0`
- 예상 밖 4xx / server failure / contract failure: 모든 Run에서 `0`
- start skew maximum: 2 ms 이하로, 1,000 ms 미만 gate 통과
- T5 comparison: fixture 6개, failure 0개, `PASS`

### 지표 해석과 한계

- `성공/요청`은 성공 응답 수와 전체 요청 수다. `업무 결과`와 `동시성 결과`는 scenario classifier가 허용한 업무 종단 또는 재시도 소진 종단의 건수이므로, 그 수만으로 오류나 성능 저하라고 단정하지 않는다.
- 아래 p50·p95·RPS는 이 전용 환경에서 각 조합을 한 번 실행해 얻은 관찰값이다. summary가 p99를 수집하지 않았으므로 p99는 `N/A`로 남겼으며 추정하지 않는다.
- 원자료 bundle은 local-only이므로 이 Git 저장소만으로 내용을 독립 재검증할 수 없다. raw artifact에는 campaign ID와 보고 포함 여부가 없어, final-05 membership은 linked manifest의 명시적 ledger와 scenario·UTC 실행 구간의 정확 일치로 재구성했다.
- 원자료의 `PASS` 결과 중 manifest ledger 밖의 5개 Run은 이 보고서 결론에 포함하지 않았다.
- T1–T4의 write wave와 T5의 read profile은 부하 생성 모델과 분모가 다르다. 따라서 표의 RPS를 scenario 간 용량 순위로 비교하거나 단일 수치로 성능 개선 효과를 판단하지 않는다.

### 시나리오와 Run 결과

| 시나리오 | 검증한 업무 흐름 | `PASS`가 의미하는 것 | 이번 관찰의 해석 |
| --- | --- | --- | --- |
| T1 | 취소 후 FIFO 자동 승격 | 성공 취소 수와 `PROMOTED` 수가 일치하고, 정원 위반·중복 승격이 없음 | hot c8에서 허용된 동시성 결과가 23/40이었지만 불변식은 유지됐다. 재시도·경합 비용을 다음 측정에서 우선 확인할 신호이지 락 전략의 결론은 아니다. |
| T2 | 동시 대기 등록과 중복 등록 | WAITING·순번 중복이 없고, 새 대기 등록 결과와 응답이 일치하며 5xx가 없음 | hot c8에서 허용된 동시성 결과가 20/40이었지만 정합성은 유지됐다. T1과 함께 high-contention write 경로의 관찰 우선순위를 높인다. |
| T3 | 대기 등록과 취소의 경합 | 허용 종단만 남고 `RECRUITING + WAITING` 조합이 없음 | 경합 업무 종단은 허용 범위 안이었다. DB lock wait·query 시간 없이 원인이나 비용을 판단할 수 없다. |
| T4 | 마지막 자리에 동시 참가 | ACTIVE가 정확히 한 명이고 정원 초과·자동 WAITING이 없음 | 마지막 자리의 업무 종단은 모두 계약을 지켰다. 현 결과만으로 transaction·index 개선을 제안하지 않는다. |
| T5 | 역할별 ROOM 상세 조회 | 역할별 응답 shape·헤더가 맞고 조회 전후 DB snapshot이 동일함 | 여섯 role×scale 조합의 correctness는 확인했지만, 각 조건이 1회뿐이므로 역할·scale 성능 우열이나 용량을 결론 내리지 않는다. |

### T1 — 취소 후 FIFO 자동 승격

| 조건 | UTC 시작–종료 | KST 시작–종료 | p50 ms | p95 ms | p99 | RPS | 성공/요청 | 업무 결과 | 동시성 결과 | skew max ms | 판정 |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| stress / hot / c2 | 2026-08-14 15:39:20–2026-08-14 15:42:46 | 2026-08-15 00:39:20–2026-08-15 00:42:46 | 65.42 | 80.37 | N/A | 0.10 | 10 / 10 | 0 | 0 | 1.00 | `PASS` |
| stress / hot / c4 | 2026-08-14 15:42:52–2026-08-14 15:46:18 | 2026-08-15 00:42:52–2026-08-15 00:46:18 | 87.81 | 116.13 | N/A | 0.21 | 15 / 20 | 0 | 5 | 1.00 | `PASS` |
| stress / hot / c8 | 2026-08-14 15:46:24–2026-08-14 15:49:50 | 2026-08-15 00:46:24–2026-08-15 00:49:50 | 112.99 | 144.78 | N/A | 0.42 | 17 / 40 | 0 | 23 | 1.00 | `PASS` |
| stress / spread / c2 | 2026-08-14 15:49:56–2026-08-14 15:53:22 | 2026-08-15 00:49:56–2026-08-15 00:53:22 | 47.44 | 230.96 | N/A | 0.10 | 10 / 10 | 0 | 0 | 1.00 | `PASS` |
| stress / spread / c4 | 2026-08-14 15:53:28–2026-08-14 15:56:55 | 2026-08-15 00:53:28–2026-08-15 00:56:55 | 50.90 | 56.82 | N/A | 0.21 | 20 / 20 | 0 | 0 | 1.00 | `PASS` |
| stress / spread / c8 | 2026-08-14 15:57:01–2026-08-14 16:00:28 | 2026-08-15 00:57:01–2026-08-15 01:00:28 | 60.29 | 76.55 | N/A | 0.42 | 40 / 40 | 0 | 0 | 1.00 | `PASS` |

### T2 — 동시 대기 등록과 중복 등록

| 조건 | UTC 시작–종료 | KST 시작–종료 | p50 ms | p95 ms | p99 | RPS | 성공/요청 | 업무 결과 | 동시성 결과 | skew max ms | 판정 |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| stress / distinct / hot / c2 | 2026-08-14 16:00:34–2026-08-14 16:04:01 | 2026-08-15 01:00:34–2026-08-15 01:04:01 | 45.02 | 103.06 | N/A | 0.10 | 10 / 10 | 0 | 0 | 2.00 | `PASS` |
| stress / distinct / hot / c4 | 2026-08-14 16:04:07–2026-08-14 16:07:33 | 2026-08-15 01:04:07–2026-08-15 01:07:33 | 49.90 | 64.95 | N/A | 0.21 | 15 / 20 | 0 | 5 | 1.00 | `PASS` |
| stress / distinct / hot / c8 | 2026-08-14 16:07:38–2026-08-14 16:11:04 | 2026-08-15 01:07:38–2026-08-15 01:11:04 | 73.45 | 116.17 | N/A | 0.42 | 20 / 40 | 0 | 20 | 1.00 | `PASS` |
| stress / distinct / spread / c2 | 2026-08-14 16:11:10–2026-08-14 16:14:36 | 2026-08-15 01:11:10–2026-08-15 01:14:36 | 28.17 | 35.16 | N/A | 0.10 | 10 / 10 | 0 | 0 | 1.00 | `PASS` |
| stress / distinct / spread / c4 | 2026-08-14 16:14:42–2026-08-14 16:18:07 | 2026-08-15 01:14:42–2026-08-15 01:18:07 | 30.91 | 36.13 | N/A | 0.21 | 20 / 20 | 0 | 0 | 1.00 | `PASS` |
| stress / distinct / spread / c8 | 2026-08-14 16:18:13–2026-08-14 16:21:39 | 2026-08-15 01:18:13–2026-08-15 01:21:39 | 39.72 | 55.61 | N/A | 0.42 | 40 / 40 | 0 | 0 | 1.00 | `PASS` |
| spike / duplicate / hot / c2 | 2026-08-14 16:21:45–2026-08-14 16:23:51 | 2026-08-15 01:21:45–2026-08-15 01:23:51 | 36.33 | 40.89 | N/A | 0.13 | 2 / 2 | 0 | 0 | 1.00 | `PASS` |

### T3 — 대기 등록과 취소 경합

| 조건 | UTC 시작–종료 | KST 시작–종료 | p50 ms | p95 ms | p99 | RPS | 성공/요청 | 업무 결과 | 동시성 결과 | skew max ms | 판정 |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| stress / race | 2026-08-14 16:24:56–2026-08-14 16:27:03 | 2026-08-15 01:24:56–2026-08-15 01:27:03 | 78.48 | 101.73 | N/A | 0.65 | 7 / 10 | 3 | 0 | 1.00 | `PASS` |
| spike / wait-first | 2026-08-14 16:27:08–2026-08-14 16:29:14 | 2026-08-15 01:27:08–2026-08-15 01:29:14 | 32.07 | 33.40 | N/A | 0.13 | 2 / 2 | 0 | 0 | 1.00 | `PASS` |
| spike / cancel-first | 2026-08-14 16:29:19–2026-08-14 16:31:24 | 2026-08-15 01:29:19–2026-08-15 01:31:24 | 38.80 | 53.64 | N/A | 0.13 | 1 / 2 | 1 | 0 | 1.00 | `PASS` |

### T4 — 마지막 자리 동시 참가

| 조건 | UTC 시작–종료 | KST 시작–종료 | p50 ms | p95 ms | p99 | RPS | 성공/요청 | 업무 결과 | 동시성 결과 | skew max ms | 판정 |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| stress / last-seat / c2 | 2026-08-14 16:31:30–2026-08-14 16:34:55 | 2026-08-15 01:31:30–2026-08-15 01:34:55 | 31.49 | 43.72 | N/A | 0.10 | 5 / 10 | 5 | 0 | 1.00 | `PASS` |
| stress / last-seat / c4 | 2026-08-14 16:35:00–2026-08-14 16:38:27 | 2026-08-15 01:35:00–2026-08-15 01:38:27 | 34.45 | 41.53 | N/A | 0.21 | 5 / 20 | 15 | 0 | 1.00 | `PASS` |
| stress / last-seat / c8 | 2026-08-14 16:38:32–2026-08-14 16:41:59 | 2026-08-15 01:38:32–2026-08-15 01:41:59 | 45.13 | 56.24 | N/A | 0.42 | 5 / 40 | 35 | 0 | 1.00 | `PASS` |

### T5 — 역할별 ROOM 상세 조회

고정 read profile은 10 VU / 60 seconds / think time 0 ms였다. 이 profile 안에서만 아래 수치를 해석한다.

| 조건 | UTC 시작–종료 | KST 시작–종료 | p50 ms | p95 ms | p99 | RPS | 성공/요청 | 업무 결과 | 동시성 결과 | skew max ms | 판정 |
| --- | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | --- |
| read / public / scale 1 | 2026-08-14 16:42:04–2026-08-14 16:45:12 | 2026-08-15 01:42:04–2026-08-15 01:45:12 | 20.02 | 38.36 | N/A | 353.95 | 26603 / 26603 | 0 | 0 | 1.00 | `PASS` |
| read / public / scale 10 | 2026-08-14 16:45:18–2026-08-14 16:48:24 | 2026-08-15 01:45:18–2026-08-15 01:48:24 | 17.06 | 21.89 | N/A | 458.96 | 34495 / 34495 | 0 | 0 | 1.00 | `PASS` |
| read / host / scale 1 | 2026-08-14 16:48:29–2026-08-14 16:51:35 | 2026-08-15 01:48:29–2026-08-15 01:51:35 | 18.99 | 26.13 | N/A | 407.48 | 30631 / 30631 | 0 | 0 | 1.00 | `PASS` |
| read / host / scale 10 | 2026-08-14 16:51:41–2026-08-14 16:54:47 | 2026-08-15 01:51:41–2026-08-15 01:54:47 | 18.82 | 24.40 | N/A | 419.42 | 31535 / 31535 | 0 | 0 | 1.00 | `PASS` |
| read / participant / scale 1 | 2026-08-14 16:54:53–2026-08-14 16:57:58 | 2026-08-15 01:54:53–2026-08-15 01:57:58 | 19.56 | 24.78 | N/A | 412.85 | 31039 / 31039 | 0 | 0 | 1.00 | `PASS` |
| read / participant / scale 10 | 2026-08-14 16:58:04–2026-08-14 17:01:09 | 2026-08-15 01:58:04–2026-08-15 02:01:09 | 20.46 | 28.64 | N/A | 377.56 | 28377 / 28377 | 0 | 0 | 1.00 | `PASS` |

### T5 comparison 계약 보정 및 artifact 재검증

원격 T5 여섯 실행은 처음부터 모두 final `PASS`였지만, 최초 comparison gate는 legacy direct-runner artifact만 요구해 `INVALID`가 됐다. 이는 데이터 복구나 원격 재실행이 아니라, portable bundle 결과를 host-side comparison이 읽지 못한 해석 계약 결함이었다.

보정된 comparison은 public/host/participant × scale 1/10의 여섯 논리 조합, 동일 VU·duration·think-time profile, portable 입력 계약, remote phase·before/after diagnosis·final result의 상호 일치, start-skew 관측을 확인한다. UTC 2026-08-14 17:14:00 / KST 2026-08-15 02:14:00의 artifact-only 재검증은 fixture 6개, failure 0개, `PASS`였다. AWS 호출·fixture 생성·k6 실행은 발생하지 않았다.

이 gate가 말하는 것은 “여섯 T5 결과 묶음이 같은 읽기 profile과 결과 계약으로 유효하다”는 것이다. 역할·scale 간 p50·p95·RPS 우열이나 용량을 비교하는 성능 분석은 아니다.

## 해석과 한계

[Campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json)는 결론 계산에 쓴 25개와 local-only 원자료의 무결성 식별값을 분리해 보존한다.

### 현재 판정

지금 즉시 코드·구조 변경을 결정할 근거는 없다. 다만 T1 hot c8의 40요청 중 23건과 T2 hot c8의 40요청 중 20건이 허용된 동시성 결과였다는 점은, high-contention write 경로를 다음 측정의 첫 우선순위로 둘 근거가 된다. 이는 불변식이 유지됐다는 증거이지 현 재시도 정책이나 락 전략이 충분하다는 증거는 아니다. T3·T4의 업무 결과도 scenario가 허용한 종단 상태 안에서 판정됐고, T5는 correctness만 확인했을 뿐 역할·scale별 성능 우열이나 최대 용량을 보여주지 않는다.

## 다음 측정

| 우선순위 | 관찰 | 지금 결정하지 않는 것 | 다음 측정 | 그 뒤 가능한 판단 |
| --- | --- | --- | --- | --- |
| 1 | T1·T2 hot c8에서 허용된 동시성 결과가 각각 23/40, 20/40 | 낙관락·비관락 선택, 재시도 횟수·backoff·UX의 변경 | 같은 release·환경·profile에서 hot/spread c4·c8을 독립 실행 최소 3회씩 반복하고, p99·409/재시도 결과·애플리케이션 retry 로그·DB CPU/connection/lock wait/query 시간을 같은 시간 창에 수집 | 충돌 비용의 반복성 및 재시도 정책과 DB transaction/lock 개선 중 어느 쪽을 우선할지 |
| 2 | T3·T4는 경합·마지막 자리의 업무 종단과 DB 불변식을 모두 지킴 | SQL/index·transaction critical section 변경 | c4·c8 반복 측정에 DB lock wait·query 시간·connection을 추가해 허용된 업무 종단의 비용을 분리 | 실제 DB 병목이 확인될 때에만 SQL/index 또는 transaction 범위 개선을 검토 |
| 3 | T5 여섯 role×scale이 모두 PASS했으나 각 조건은 1회 관찰이고 p99가 없음 | 역할·scale 성능 순위, read 경로의 cache·조회 조립 개선 | 고정 read profile(10 VU / 60 seconds / think time 0 ms)에서 role×scale별 독립 실행 최소 3회, p99와 애플리케이션·DB 자원을 함께 수집 | 역할 또는 scale에 따라 반복되는 지연·자원 차이가 있을 때 해당 read query·응답 조립·cache 후보를 좁힘 |

반복 횟수 ‘최소 3회’는 이번 결과의 통계적 한계를 줄이기 위한 권고이지, 이번 `PASS` 판정을 소급해 바꾸는 gate는 아니다. 다음 campaign에서는 비교 전에 SLO·허용 409/재시도 정책·수집 항목을 먼저 명시하고, 같은 source/release·환경·profile의 범위와 원자료 무결성 식별값을 campaign manifest에 남긴다.

## 재현

같은 조건의 재현은 [ROOM k6 실행](../../../../load-tests/k6/jiwon/README.md#실행)과 [Terraform 원격 실행 bundle](../../../../load-tests/k6/jiwon/README.md#terraform-원격-실행-bundle)을 따른다. source/release 정렬, clean-source gate, fixture별 portable bundle 검증을 모두 통과한 새 Run만 비교에 사용한다.

## 원자료와 teardown

25개 Run ledger, 수치, phase와 artifact 무결성 식별값은 [campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json)에 있다. 원시 bundle과 실행 산출물은 로컬 `build/k6/room/`에만 보존한다.

final `PASS`와 T5 comparison `PASS`를 다시 확인한 뒤 전용 P1 stack teardown을 실행했다. teardown 및 잔여 resource 검증 완료 시각은 UTC 2026-08-14 17:30:01 / KST 2026-08-15 02:30:01이다. 비밀값은 조회하지 않았고, test-owned resource의 개수만 확인했다.

| 확인 대상 | 결과 |
| --- | --- |
| `run.sh down` | exit 0 |
| Terraform P1 state | 0 |
| 실행 중·중지 중 EC2 / stack-tagged EBS / stack-tagged EIP / stack-tagged NAT | 0 / 0 / 0 / 0 |
| P1 CloudWatch alarm / dashboard | 0 / 0 |
| 이번 release backend / web ECR tag | 0 / 0 |
| P1 SSM parameter | 0 (test-only 9개를 별도 삭제 후 재조회) |

공유 bootstrap의 Terraform state 저장소와 ECR repository 자체는 테스트 stack 소유가 아니므로 보존했다. 따라서 위 결과는 계정 전체가 아니라 이번 P1 테스트가 만든 compute·network·public IP·storage·monitoring·parameter·현재 release image tag가 0건이라는 범위의 무과금 상태를 뜻한다.
