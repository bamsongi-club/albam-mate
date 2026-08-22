# ROOM-LOCK-CMP 비교 측정 계약

## 범위

이 문서는 Issue #786의 `ROOM-LOCK-CMP-T1`부터 `ROOM-LOCK-CMP-T7`까지의 기존 비교 계약과 Issue #1026의 후속 full campaign 계약을 정리한다. 대상은 #785가 고정한 세 후보이다.

| 후보 | 의미 | 측정 시 식별자 |
| --- | --- | --- |
| A | 현행 낙관적 락 | #785가 확정한 candidate SHA |
| B | 낙관적 락 + bounded jitter | #785가 확정한 candidate SHA |
| C | T1·T2 비관 write lock | #785가 확정한 candidate SHA |

후보 SHA는 실행 시작부터 끝까지 바꾸지 않는다. bundle의 `candidateSha`, `sourceRevision`, infra `RELEASE_SHA`와 실제 배포 image revision이 모두 같은 후보를 가리켜야 한다.

이 문서는 최종 잠금 전략, 생산 코드 병합, ADR, SLO/SLA를 결정하지 않는다. 비교 결과는 후속 결정의 입력으로만 보존한다.

## 2026-08-20 timeboxed 실행 범위

아래 전체 matrix는 최초 600회 campaign의 설계 기록으로 보존한다. 다만 빠른 잠금 전략 결정 입력을 위한 사용자 승인 timeboxed 실행은 다음으로 한정했다.

- 시나리오·조건: `ROOM 시나리오 T1`의 `constant-arrival-rate`·`constant-mixed`·c8(초당 8건)·60초·실행당 480 ROOM 요청
- A/B: 같은 조건에서 각각 완결 PASS 4회를 보존한다. 다섯 번째 반복을 만들기 위한 재측정은 하지 않는다.
- C: p4 원자료에서 provenance가 유효한 server failure 4건·contract failure 4건과 after diagnosis의 5xx가 발생했다. `ROOM-LOCK-CMP-T7`의 1차 분류는 `FAIL`이며, C는 이 T1 조건의 성능 순위와 추가 성능 반복에서 제외한다.
- C runner artifact: p4는 nonzero k6 종료 뒤 `resource-signals.json`이 없어 runner 최종 상태가 `INVALID`다. 이는 실제 5xx를 무효화하지 않는 부가 관측 artifact 상태이며, `FAIL`과 함께 원자료·digest에 보존한다.
- 보류: T2, barrier-hot·barrier-spread·constant-hot, c2·c4·c16, T3·T4·T5, c32는 이번 실행에 포함하지 않는다.

`docs/measurements/results/room-lock-strategy-comparison/campaign-plan.json`과 `campaign-report.json`이 이 승인 범위와 포함·제외 사유의 정본이다. 이 제한된 T1 결과는 T2 또는 ROOM 전체의 잠금 전략을 자동 선택하지 않는다.

## 2026-08-23 #1026 후속 full campaign 계약

이 절은 2026-08-20 timeboxed 결과를 수정하지 않고, 공통 기준점에서 A/B/C를 다시 비교하기 위한 실행 plan·bundle·판정 계약이다. 원격 campaign 결과가 아니며, 실행 전 후보 SHA·환경·fixture·provenance를 다시 고정한다.

### 핵심 비교 matrix

핵심 비교는 `ROOM 시나리오 T1`과 `ROOM 시나리오 T2`를 별도로 판정한다.

| condition | 실행 모델 | ROOM 분포 | 동시성 | 반복 |
| --- | --- | --- | --- | ---: |
| `barrier-hot` | 기존 wave barrier | hot 최악 경합 | c2·c4·c8·c16 | 10 paired run |
| `barrier-spread` | 기존 wave barrier | spread 대조군 | c2·c4·c8·c16 | 10 paired run |
| `constant-hot` | `constant-arrival-rate` | hot 최악 경합 | c2·c4·c8·c16 req/s | 10 paired run |
| `constant-mixed` | `constant-arrival-rate` | hot 50% + spread 50% | c2·c4·c8·c16 req/s | 10 paired run |

constant-arrival-rate의 rate는 기존 값을 유지한다.

- rate: 동시성 수준과 같은 초당 요청 수 `c` — c2=2, c4=4, c8=8, c16=16 req/s
- c2/c4 baseline: 60초, 최소 유효 표본 `c × 60` — 각각 120·240 요청
- `constant-hot`·`constant-mixed`의 c8/c16 tail: 실행당 최소 5,000 요청을 위해 `ceil(5000 / c)`초 — c8=625초·5,000 요청, c16=313초·5,008 요청
- c16의 pre-allocated VU `32`, max VU `64`를 유지하고, 다른 수준도 rate의 2배·4배 headroom 규칙을 적용한다.
- 모든 constant-arrival 실행은 측정 구간의 `room_requests`가 `minimumValidSamples`와 정확히 같고 `dropped_iterations=0`이어야 한다. setup·로그인 요청까지 포함하는 `http_reqs`는 이 gate에 사용하지 않는다. VU 부족이나 요청 수 불일치는 `INVALID`다.
- fixture 준비·로그인·정리 비용은 측정 요청 지연에 포함하지 않으며, 실행 시간 창은 k6 `run-manifest.json`의 UTC 시작·종료 시각으로 고정한다.

`constant-mixed`는 하나의 fixture 안에서 요청 절반을 같은 hot ROOM에, 나머지 절반을 서로 다른 spread ROOM에 배치한다. 따라서 hot 경합과 spread 대조군이 같은 release·환경·실행 창에 존재한다.

핵심 실행 단위는 후보 3개 × 시나리오 2개 × condition 4개 × 동시성 4개 × 반복 10개 = **960회**이며, 각 paired run의 후보 실행 순서는 seed 기반으로 섞는다. A→B→C 고정 순서는 사용하지 않는다.

### 회귀·배경 실행

T3 15회, T4 15회, T5 90회로 총 **120회**를 기존 portable bundle read-only runner로 실행한다. 회귀 반복은 후보별 5회 기준을 유지하며, 핵심 비교와 합쳐 전체 campaign plan은 **1,080회** 실행 단위다.

### 비교·정합성 판정

- 기존 정합성 기준을 유지한다: unexpected 4xx=0, 5xx=0, contract failure=0, before/after fixture 저장 불변식 통과, outcome count 합계가 실제 요청 수와 일치, provenance·raw·digest 완전성.
- 계약상 예상된 `ROOM_CONCURRENT_MODIFICATION` 409는 unexpected failure와 분리해 business outcome·retry/lock cost로 기록한다.
- 실행별 성공/전체 p50·p95·p99, throughput/RPS, 409 conflict, retry/exhaustion, lock wait, transaction/query, Hikari pending, App/PostgreSQL CPU를 같은 UTC window로 수집한다.
- 각 실행의 percentile을 먼저 계산한 뒤, 유효한 10회 paired run의 중앙값과 변동성을 비교한다. 전체 요청을 pooled percentile로 합쳐 후보를 순위화하지 않는다.
- `FAIL`·`INVALID`는 raw evidence와 사유를 보존하지만 후보 성능 순위와 winner에서 제외한다. 이 계약은 절대 SLO/SLA나 최종 lock 전략을 자동 확정하지 않는다.

### provenance 고정

공통 기준 SHA, A/B/C candidate SHA, bundle source revision, fixture schema, release/image revision, infra branch, seed, 실행 순서와 각 artifact SHA-256을 plan·manifest에 고정한다. `sourceRevision`·`candidateSha`·infra `RELEASE_SHA`는 배포된 후보 앱을 식별하고, comparison·portable runner와 fixture runtime은 controller checkout에서 공통으로 bundle에 넣어 후보 간 실행기를 동일하게 유지한다. 공통 runtime의 실제 내용은 bundle immutable SHA-256으로 고정하며, 후보 checkout은 clean HEAD/provenance 확인과 결과 출력 root로만 사용한다. 이슈 #1026의 infra 참조 브랜치는 `albam-mate-infra`의 `codex/room-k6-local-runner`이며, 해당 저장소는 실행 참조만 하고 commit·push·PR을 만들지 않는다.

## 기존 전체 실행 matrix (2026-08-20 timeboxed 당시 보류)

핵심 비교는 `ROOM 시나리오 T1`과 `ROOM 시나리오 T2`를 별도로 판정한다.

| condition | 실행 모델 | ROOM 분포 | 동시성 | 반복 |
| --- | --- | --- | --- | ---: |
| `barrier-hot` | 기존 wave barrier | hot 최악 경합 | c2·c4·c8·c16 | 5 paired run |
| `barrier-spread` | 기존 wave barrier | spread 대조군 | c2·c4·c8·c16 | 5 paired run |
| `constant-hot` | `constant-arrival-rate` | hot 최악 경합 | c2·c4·c8·c16 req/s | 5 paired run |
| `constant-mixed` | `constant-arrival-rate` | hot 50% + spread 50% | c2·c4·c8·c16 req/s | 5 paired run |

constant-arrival-rate의 공통값은 다음과 같다.

- rate: 동시성 수준과 같은 초당 요청 수 `c`
- duration: 60초
- 최소 유효 표본: `c × 60` 요청
- c16에서는 pre-allocated VU `32`, max VU `64`를 사용한다. 다른 수준도 rate의 2배·4배 headroom 규칙을 기록한다.
- fixture 준비·로그인·정리 비용은 측정 요청 지연에 포함하지 않는다.
- 실행 시간 창은 k6 `run-manifest.json`의 UTC 시작·종료 시각으로 고정한다.

`constant-mixed`는 하나의 fixture 안에서 각 round의 요청 절반을 같은 hot ROOM에, 나머지 절반을 서로 다른 spread ROOM에 배치한다. 따라서 hot 경합과 spread 대조군이 같은 release·환경·실행 창에 존재한다.

핵심 비교는 후보 3개 × 시나리오 2개 × condition 4개 × 동시성 4개 × 반복 5개 = **480회**이며, 각 paired run의 후보 실행 순서는 seed 기반으로 섞는다. A→B→C 고정 순서는 사용하지 않는다.

### 기존 회귀·배경 실행 (2026-08-20 timeboxed 당시 보류)

모든 후보에 다음을 같은 후보 순서·fixture·release 설정으로 적용한다.

| 구분 | 실행 | 현재 runner |
| --- | --- | --- |
| T3 정합성 회귀 | `t3 race`, stress, 5 rounds | 기존 portable bundle read-only |
| T4 정합성 회귀 | `c8`, spike, 1 round | 기존 portable bundle read-only |
| T5 배경 조회 | public/host/participant × scale 1/10, 각 조합 별도 실행 | 기존 T5 비교 runner read-only |

회귀·배경을 포함한 전체 campaign plan은 600개 실행 단위를 보존한다. 핵심 480개는 786 전용 bundle이고, 나머지 120개는 기존 T1~T5 portable bundle을 읽기 전용으로 실행하는 별도 gate다. T3 15개, T4 15개, T5 90개로 구성한다.

## 수집 metric과 원자료

| 영역 | 필수 값 | 원자료 |
| --- | --- | --- |
| correctness | 완료율, 성공/업무/동시성/예상 밖 응답, 409, contract failure | `k6-summary.json`, before/after snapshot, diagnosis |
| 처리량·지연 | 완료 건수/초, 전체 RPS, 성공/전체 p50·p95·p99 | k6 summary |
| 재시도 | attempt bucket, retry, exhausted | `resource-signals.json`의 structured retry log |
| 잠금·DB | lock wait, transaction count/duration, query calls/time, buffer hit/read | `resource-signals.json` |
| pool·자원 | Hikari active/idle/pending/max, App/PostgreSQL CPU | `resource-signals.json`, 같은 UTC window |
| provenance | candidate/source SHA, 실행 순서, 환경, k6 version, fixture/summary/raw SHA-256 | `run-manifest.json`, `infra-execution.json`, bundle manifest |

요청 outcome 네 범주의 count 합은 실제 `room_requests`와 같아야 한다. 저장 불변식은 after snapshot에서 다시 확인한다.

## 판정

### PASS

- before fixture가 유효하다.
- after correctness·불변식·응답 contract가 모두 통과한다.
- 예상 밖 4xx/5xx와 `room_contract_failures`가 0이다.
- k6·infra phase가 모두 완료되고 resource/retry/query/pool/CPU 원자료가 있다.
- candidate/source/environment/fixture/raw digest가 현재 실행과 일치한다.

### FAIL

- provenance는 유효하지만 correctness·저장 불변식·예상 밖 4xx/5xx·contract failure가 발생했다.
- FAIL 결과는 원자료와 실패 사유를 보존하지만 성능 순위와 winner에서 제외한다.

### INVALID

- provenance, raw snapshot/artifact, digest, 최소 유효 표본 또는 start/arrival evidence가 누락·변조·malformed·stale이다.
- 실행 phase exit code·resource signal이 완결되지 않았거나 fixture와 summary 표본이 기대값과 맞지 않는다.
- INVALID 결과도 삭제하거나 PASS로 보정하지 않고 원자료·사유를 보존한다.

FAIL/INVALID 중 하나라도 있으면 해당 candidate/condition의 성능 순위와 winner를 만들지 않는다. 유효 후보가 하나도 없으면 winner 없이 campaign을 종료한다.

## tail latency gate

`p95`와 `p99`는 `constant-arrival-rate` open-model 실행에서 해당 run의 `minimumValidSamples`를 충족한 경우에만 후보 우열의 입력으로 사용할 수 있다. #1026에서는 c2/c4가 `c × 60`, c8/c16 `constant-hot`·`constant-mixed`가 각각 5,000 이상이 되도록 설정된 `c × durationSeconds`를 사용한다.

다음 결과로는 winner를 만들지 않는다.

- barrier 결과만 있는 경우
- barrier의 작은 표본 p99
- constant-arrival-rate 표본이 최소값보다 적은 경우
- dropped iteration, start/arrival evidence 누락 또는 resource signal 누락이 있는 경우

786의 campaign report는 정규화 metric과 eligible/excluded 상태를 만들지만 최종 winner를 자동 선택하지 않는다. 최종 선택은 이 원자료를 확인한 뒤 별도 결정으로 남긴다.

이번 timeboxed report에서 A/B의 4회는 제한된 T1 metric 증거로만 표시한다. 원래 전체 matrix나 #1026의 10회 winner gate를 충족했다고 해석하지 않으며, A/B의 최종 선택·ADR은 이 이슈 밖의 사람 결정으로 남긴다.

## 운영 게이트

teardown과 AWS 잔여 resource 확인은 T-ID 밖 운영 게이트다.

- raw bundle과 SHA-256 digest를 먼저 정식 결과 경로에 보존한 뒤, 승인된 teardown으로 EC2, EBS, EIP, VPC, CloudWatch 등 test-owned resource를 재조회한다.
- teardown 실패 또는 잔여 resource가 있으면 운영 완료를 차단한다.
- 운영 게이트 결과를 측정 run의 FAIL/INVALID 성능 판정으로 바꾸지 않고 별도 보고한다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-20` · 폐기 조건 `786 비교 계약이 후속 측정 정본으로 대체될 때`
