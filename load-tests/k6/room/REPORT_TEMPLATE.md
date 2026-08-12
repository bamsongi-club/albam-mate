# ROOM k6 측정 보고서 템플릿

> 이 문서는 실제 측정을 실행한 뒤 채운다. `[기록]`, `[미수집]`, `[unavailable]`은 placeholder이며 수치를 추정하거나 과거 원시 결과를 복사하지 않는다. 검토·승인된 문서만 `docs/measurements/k6/`로 승격한다.

## Campaign 식별

- Campaign ID: `room-k6-YYYYMMDDTHHmmssKST`
- measurementWindow: `{ startedAtUtc: [기록], endedAtUtc: [기록] }`
- Campaign 상태: `[실제 결과 뒤 completed | completed-with-limitations | partial 중 선택]`
- 판단서: `[실제 아키텍처 결론이 있을 때만 링크, 없으면 없음]`
- 실행 환경과 AWS account/stack: `[기록]`
- app source Git SHA와 fixture source Git SHA: `[기록]`
- k6 버전과 runner 버전: `[기록]`

## 시나리오 분류와 적용 부하

| 시나리오 | 분류 | Stress | Spike | Soak | 적용 부하와 해석 경계 |
| --- | --- | --- | --- | --- | --- |
| 01 취소·자동 승격 | `write-contention` (쓰기 경합) | 필수 | 권장 | 제외 | hot/spread, VU 2/4/8 wave |
| 02 대기 등록 | `write-contention` (쓰기 경합) | 필수 | 권장 | 제외 | hot/spread, VU 2/4/8 wave |
| 03 due backlog 조회 | `read-write-contention` (조회+쓰기 충돌) | 필수 | 선택 | 제외 | endpoint × VU 2/4/8 × due 0/20/2,000/10,000 |
| 04 역할별 상세 | `read-load` (읽기 부하) | 필수 | 선택 | 추후 권장 | role public/host/participant × active 1/10 |
| 05 대기 순번 | `data-scale-low-contention-comparison` (데이터 증가·저경합 비교) | 선택 (VU 1) | 불필요 | 후순위 | constant VU 1, queue 10/100/1,000/10,000 |

03의 due 10,000과 05의 queue 10,000은 공식 matrix에 포함한다. 05는 stress profile이어도 constant VU 1만 사용하므로 동시성 부하 결과로 해석하지 않는다.

### 이번 campaign 적용 범위

01/02는 Stress+Spike, 03은 Stress+Spike, 04는 Stress+Spike를 실행한다. 04 Soak은 `추후 권장` 분류를 보존하지만 이번 campaign에서는 제외한다. 05는 Stress만 VU 1로 실행하고, Spike는 불필요하며 Soak은 `후순위` 분류를 보존한 채 이번 campaign에서 제외한다.

## 실행 행과 재현 artifact

| 실행 행 | Campaign ID | scenario/profile | fixture manifest | source metadata | run metadata / result | k6/verify artifact | measurementWindow | Run 상태 | reportDisposition |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `[행 추가]` | `[기록]` | `[기록]` | `manifest.json 경로와 SHA-256` | `source-metadata.json 경로와 SHA-256` | `run-metadata.json`, `run-result.json` 경로와 SHA-256 | `summary.json`, `verify.log` | `{ startedAtUtc, endedAtUtc }` | `PASS`/`FAIL`/`INVALID` | `included`/`excluded` |

각 행은 하나의 bundle 실행만 나타낸다. 01/02는 mode × VU × profile별 bundle, 03은 endpoint × due 규모 × VU × profile별 bundle, 04는 role × active participants × profile별 bundle, 05는 queue 규모 × position의 stress bundle을 별도 행으로 남긴다. `run-metadata.json`은 Campaign ID와 k6 실행 구간을, `run-result.json`은 measurementWindow·Run 상태·reportDisposition을 제공해 fixture, k6, 관측 artifact를 연결한다. `reportDisposition=included`인 Run만 보고서 결론 계산에 사용하며 `excluded` Run은 이력으로만 남긴다. bundle의 `users.json`, `prepare.sql`과 비밀값은 artifact 또는 보고서에 넣지 않는다.

## k6 관측

| 항목 | 값 또는 artifact | 판정 |
| --- | --- | --- |
| `room_request_duration_ms` p50/p95/p99/max | `[기록]` | 관찰 |
| `room_measured_requests`와 처리량 | `[기록]` | 관찰 |
| `room_success_responses`와 `room_conflict_responses` | `[기록]` | 관찰 |
| `room_unexpected_4xx_responses`와 `room_5xx_responses` | `[기록]` | 관찰 |
| `room_unexpected_response_rate` | `[기록]` | 반드시 `rate==0` |
| `room_measurement_check_rate` | `[기록]` | 반드시 `rate==1` |
| 05 순번 SQL EXPLAIN | `verify.log`의 `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` | planning/execution time, actual rows, shared hit/read blocks를 데이터 규모별로 관찰 |

`checks{phase:measure}`는 gate가 아니다. measure payload checker가 남긴 `room_measurement_check_rate`만 응답 계약 gate로 사용한다. 03~05의 첫 VU-local iteration은 warm-up이므로 measure 표본과 분리해 해석한다.

## DB와 AWS 관측

| 관측 대상 | 시간 범위와 출처 | 값 또는 artifact | 상태 |
| --- | --- | --- | --- |
| PostgreSQL CPU | `measurementWindow`, CloudWatch | `cloudwatch-capacity.json` | `[complete/partial/unavailable]` |
| DB waiting lock 집계 | `measurementWindow`, aggregate collector | `database-lock-samples.ndjson` | `[complete/partial/unavailable]` |
| 애플리케이션 관측 상태 | `measurementWindow`, 승인된 관측 경로 | `observation-status.json` | `[complete/partial/unavailable]` |
| AWS 인스턴스/ALB 관측 | `measurementWindow`, CloudWatch | `cloudwatch-capacity.json` | `[complete/partial/unavailable]` |

raw SQL, query text, session ID, 사용자 fixture, 비밀번호는 이 보고서와 artifact에 넣지 않는다. DB lock은 대기 수 등 aggregate 값만 기록한다.

## Hikari pending

- 상태: `observation-status.json`의 `[unavailable 또는 관측값]`
- unavailable 사유: 승인되고 보호된 애플리케이션 metric endpoint 또는 동등한 관측 계약이 확인되지 않았다면 `observation-status.json`에 `unavailable`으로 기록한다.
- 금지: k6 지연, DB CPU, 로그 부재로 Hikari pending을 추정하거나 connection pool이 정상이라고 결론 내리지 않는다.
- 후속 조건: 앱 측 관측 endpoint와 접근 보호 규칙이 승인된 뒤 같은 campaign 시간 축에 수집을 추가한다.

## DB 불변식과 응답 계약

- fixture `verify.sql` 결과: `[기록]`
- 01: 취소 성공 수와 승격 수, 정원, FIFO, 중복 ACTIVE: `[기록]`
- 02: 성공 수와 WAITING 수, 사용자/queue order 중복: `[기록]`
- 03: Scheduler lock, due 저장 상태·version·WAITING·chat 불변: `[기록]`
- 04: public 관계자 필드 부재, host/participant 역할, participantCount·remaining seats·participants 길이: `[기록]`
- 05: queue 길이, target WAITING, head/middle/tail 기대 순번: `[기록]`

## 결론과 한계

### 결론

`[필수 matrix 완료 여부, 각 gate, 관측 근거를 바탕으로 실제 측정 뒤 작성]`

### 한계

`[미수집 관측, partial CloudWatch, Hikari unavailable, 선택 matrix 미실행, fixture와 운영 트래픽 차이를 실제 사실만 기록]`

### 판단서 발행 조건

다음이 모두 충족될 때만 `docs/measurements/k6/` 승격을 제안한다. 판단서 링크 또는 작성은 실제 아키텍처 결론이 있을 때만 별도로 기록한다.

1. 해당 시나리오의 이번 campaign 적용 행과 필요한 fixture `verify.sql`이 통과했다.
2. `room_unexpected_response_rate=0`과 `room_measurement_check_rate=1`이 각 실행 행에서 확인됐다.
3. manifest, source metadata, `run-metadata.json`, `run-result.json`, k6 summary, verify artifact, measurementWindow가 서로 연결된다.
4. DB와 AWS 관측은 complete/partial/unavailable 상태와 이유를 함께 남겼다.
5. Hikari pending이 unavailable이면 그 한계를 결론에 명시했고, 관측하지 않은 정상성을 주장하지 않았다.

Campaign 상태는 템플릿 기본값으로 정하지 않고 실제 결과에 따라 `completed`, `completed-with-limitations`, `partial` 중에서 선택한다. `run-result.json`이 `PASS/included`을 기록한 행만 정상 결론 계산에 쓰며, k6 correctness 또는 DB 검증 실패는 `FAIL/excluded`, fixture 준비 또는 필수 결과 artifact가 없으면 `INVALID/excluded`으로 기록한다. 성능 개선·회귀·용량 판단은 `excluded` Run으로 발행하지 않는다.
