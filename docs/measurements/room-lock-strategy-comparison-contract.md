# ROOM-LOCK-CMP 비교 측정 계약

## 범위

이 문서는 Issue #786의 `ROOM-LOCK-CMP-T1`부터 `ROOM-LOCK-CMP-T7`까지를 실행 가능한 비교 계약으로 정리한다. 대상은 #785가 고정한 세 후보이다.

| 후보 | 의미 | 측정 시 식별자 |
| --- | --- | --- |
| A | 현행 낙관적 락 | #785가 확정한 candidate SHA |
| B | 낙관적 락 + bounded jitter | #785가 확정한 candidate SHA |
| C | T1·T2 비관 write lock | #785가 확정한 candidate SHA |

후보 SHA는 실행 시작부터 끝까지 바꾸지 않는다. bundle의 `candidateSha`, `sourceRevision`, infra `RELEASE_SHA`와 실제 배포 image revision이 모두 같은 후보를 가리켜야 한다.

이 문서는 최종 잠금 전략, 생산 코드 병합, ADR, SLO/SLA를 결정하지 않는다. 비교 결과는 후속 결정의 입력으로만 보존한다.

## 실행 matrix

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

### 회귀·배경 실행

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

`p95`와 `p99`는 `constant-arrival-rate` open-model 실행에서 최소 유효 표본 `c × 60`을 충족한 경우에만 후보 우열의 입력으로 사용할 수 있다.

다음 결과로는 winner를 만들지 않는다.

- barrier 결과만 있는 경우
- barrier의 작은 표본 p99
- constant-arrival-rate 표본이 최소값보다 적은 경우
- dropped iteration, start/arrival evidence 누락 또는 resource signal 누락이 있는 경우

786의 campaign report는 정규화 metric과 eligible/excluded 상태를 만들지만 최종 winner를 자동 선택하지 않는다. 최종 선택은 이 원자료를 확인한 뒤 별도 결정으로 남긴다.

## 운영 게이트

teardown과 AWS 잔여 resource 확인은 T-ID 밖 운영 게이트다.

- 승인된 teardown 뒤 EC2, EBS, EIP, VPC, CloudWatch 등 test-owned resource를 재조회한다.
- teardown 실패 또는 잔여 resource가 있으면 운영 완료를 차단한다.
- 운영 게이트 결과를 측정 run의 FAIL/INVALID 성능 판정으로 바꾸지 않고 별도 보고한다.

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 최종 검증일 `2026-08-20` · 폐기 조건 `786 비교 계약이 후속 측정 정본으로 대체될 때`
