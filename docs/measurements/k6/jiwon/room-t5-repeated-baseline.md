# ROOM T5 역할·ACTIVE 규모별 반복 조회 기준선 — INVALID (2026-08-18)

## 결론

Issue #779의 승인된 T1~T4 계약에 따라 public·host·participant × ACTIVE scale 1·10의 6개 조건을 고정 read profile로 실행했다. canonical bundle 6개는 모두 k6/remote phase/diagnosis transport `PASS`였지만, 조건별 3회 요구 중 각 1회만 실행했고 T5 comparison은 `INVALID`였다.

- 계획: 6조건 × 3회 = 18 independent run
- 실제: canonical fixture 6개, 조건별 1회; 미실행 12회
- 유효 판정: `PASS` 0, `FAIL` 0, `INVALID` 6
- 공통 profile: 10 VU / 60 seconds / think time 0ms
- comparison: expected 6, accepted 0; canonical bundle의 run-manifest.json 0개
- start-skew: 6/6 gate 통과, 최대 1ms
- response contract check 실패 run 0, 조회 전후 DB snapshot/diagnosis PASS 6/6
- teardown: `run.sh down` exit 0, active EC2/EBS/EIP/CloudWatch/Route53 0, issue SSM 9 retained

개별 `final-result.json`의 `PASS`는 원격 실행과 snapshot/diagnosis 결과를 뜻하지만, 승인된 T4 comparison gate가 유효하지 않아 기준선 `PASS`로 승격하지 않는다. 비교에 필요한 artifact를 만들기 위해 별도의 두 번째 k6 실행을 합성하거나, 누락 run을 채우지 않았다.

근거 ledger와 비밀 없는 artifact SHA-256은 [campaign evidence](evidence/room-t5-repeated-baseline.json)에 있다. 원시 bundle은 local-only다.

## 측정 provenance

| 항목 | 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-18 03:56:50–04:19:30 |
| source / 배포 release | `92f5f667b732a5b6b9e6cd7dff5befd13354148a` / 동일 release 6/6 |
| 대상 환경 | `aws-dedicated-private-loadtest` |
| Issue 전용 stack | `perf-jiwon-779` |
| portable bundle | `albam-mate-room-k6-bundle`, schema 2, fixture schema 2, clean source 6/6 |
| k6 | `1.3.0` |
| read profile | 10 VU / 60 seconds / 0ms think time, execution-options 6/6 일치 |
| 실행 경로 | portable bundle → infra `run.sh room-k6`; generic loadtest 미사용 |

## T1~T4 판정

| 계약 | 확인한 사실 | 판정 |
| --- | --- | --- |
| T1 반복·provenance | 6개 role/scale fixture, source/release/profile/options/UTC/artifact digest 정렬; 각 조건 1회 | 반복 부족으로 `INVALID` |
| T2 response shape·security header | k6 response contract checks 실패 0/6, role/scale 실행 결과는 존재 | comparison invalid과 필수 반복 부족으로 유효 결과 제외 |
| T3 DB 무변경·자원 연결 | before/after snapshot·diagnosis 6/6 PASS; query call/time/buffer와 HTTP·Tomcat·Hikari·JVM·PostgreSQL 신호는 없음 | `INVALID` |
| T4 비교 묶음 | comparison status INVALID, fixtureCount 0, accepted 0, run-manifest 0 | `INVALID` |

## 조건 coverage

| role | ACTIVE scale | 필요 반복 | 실제 반복 | transport PASS | 유효 PASS | 판정 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| public | 1 | 3 | 1 | 1 | 0 | `INVALID` |
| public | 10 | 3 | 1 | 1 | 0 | `INVALID` |
| host | 1 | 3 | 1 | 1 | 0 | `INVALID` |
| host | 10 | 3 | 1 | 1 | 0 | `INVALID` |
| participant | 1 | 3 | 1 | 1 | 0 | `INVALID` |
| participant | 10 | 3 | 1 | 1 | 0 | `INVALID` |

각 조건의 단일 실행은 관찰 artifact로만 보존한다. 반복 부족과 comparison invalid 때문에 18개 계획을 충족한 것으로 세지 않는다.

## 반복별 관측값

p50은 summary의 `med`를 사용했고 p99는 summary에 없어 `N/A`로 보존했다. 아래 수치는 성능 순위나 최대 용량을 뜻하지 않는다.

| # | role / scale | UTC 시작–종료 | p50 ms | p95 ms | p99 | max ms | RPS | 요청 수 | success | contract fail | skew max ms | transport / campaign |
| ---: | --- | --- | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | participant / 10 | 2026-08-18 04:15–04:19 | 18.468 | 160.790 | N/A | 394.641 | 268.164 | 20177 | 20177 | 0 | 1 | PASS / INVALID |
| 2 | public / 1 | 2026-08-18 03:56–04:00 | 23.715 | 162.218 | N/A | 946.722 | 187.782 | 14301 | 14301 | 0 | 1 | PASS / INVALID |
| 3 | public / 10 | 2026-08-18 04:00–04:04 | 17.381 | 161.106 | N/A | 555.362 | 214.217 | 16152 | 16152 | 0 | 1 | PASS / INVALID |
| 4 | host / 10 | 2026-08-18 04:08–04:11 | 17.554 | 161.056 | N/A | 397.151 | 233.590 | 17575 | 17575 | 0 | 1 | PASS / INVALID |
| 5 | host / 1 | 2026-08-18 04:04–04:08 | 18.538 | 225.905 | N/A | 704.260 | 195.063 | 14675 | 14675 | 0 | 1 | PASS / INVALID |
| 6 | participant / 1 | 2026-08-18 04:12–04:15 | 18.525 | 155.043 | N/A | 552.464 | 275.355 | 20719 | 20719 | 0 | 1 | PASS / INVALID |

## comparison 차단 원인

- canonical bundle 6개에는 portable `manifest.json`가 있으나 `run-manifest.json`는 0개다.
- canonical comparison artifact는 `INVALID`이고 accepted fixture 0개다. after 검증이 `fixture.mjs run`이 남긴 run-manifest를 요구해 role/scale 6개를 비교 묶음으로 채택하지 못했다.
- 현재 infra runner가 원격 k6를 이미 실행한 뒤 직접 `fixture.mjs run`을 호출하면 두 번째 별도 k6 실행이 되어 synthetic provenance가 된다. 따라서 누락 manifest를 수동 생성하지 않았다.
- role·scale별 query call/time/buffer와 승인된 애플리케이션·DB 자원 신호도 같은 UTC 구간에 연결되지 않았다.

## Teardown와 잔여 조회

| 대상 | 결과 |
| --- | --- |
| `run.sh down` | exit 0 |
| active test-owned EC2 / EBS / EIP / CloudWatch / Route53 | 0 / 0 / 0 / 0 / 0 |
| Issue-scoped SSM | 9 retained; 삭제하지 않음 |
| shared ECR | 보존; 삭제하지 않음 |

SSM 9개와 shared ECR은 stack destroy 대상과 소유 범위가 달라 삭제하지 않았다. raw bundle과 teardown log는 local-only로 보존하고 Git에는 비밀·토큰·URL·fixture/resource ID·raw SQL·raw log를 넣지 않는다.

## 검증과 다음 gate

fixture model/runner 관련 기존 검증과 `git diff --check`를 실행하고, 이 문서 변경 후 링크 검사를 다시 실행한다.

다음 유효 campaign 전에는 다음을 별도 scope로 해결해야 한다.

1. 원격 k6 완료 결과를 재실행 없이 `run-manifest.json`으로 확정하는 completion 계약을 app load-test와 infra runner 양쪽에 추가한다.
2. 18개 independent run과 comparison 6/6 accepted gate를 다시 실행한다.
3. role·scale별 p99와 query call/time/buffer, HTTP·Tomcat·Hikari·JVM·PostgreSQL 신호를 같은 UTC window로 연결한다.
4. 이 결과만으로 역할·scale 성능 순위·최대 용량·cache/SQL 변경·운영 SLO를 결정하지 않는다.

## 재현과 원자료

재현은 [ROOM k6 T5 실행 계약](../../../../load-tests/k6/jiwon/README.md#제공-시나리오)과 [campaign evidence](evidence/room-t5-repeated-baseline.json)를 따른다. 원자료 canonical 6개 bundle은 local-only이며 evidence에는 role/scale, run window, 비밀 없는 artifact SHA-256만 남겼다.
