# ROOM 잠금 전략 비교 의사결정 보고서

## 결론: 이번 T1 timeboxed 근거로 A를 선택한다

이번 timeboxed 측정에서 C(비관 write lock)는 유효한 후보·source provenance가 있는 p4 실행에서 실제 5xx 4건과 response contract failure 4건을 냈다. 따라서 C는 이 T1 조건의 성능 순위와 추가 반복에서 제외한다. `INVALID`은 nonzero k6 종료 뒤 `resource-signals.json`이 없는 runner artifact 상태이며, 이 5xx를 PASS로 바꾸지 않는다.

A(현행 낙관적 락)와 B(낙관적 락 + bounded jitter)는 각각 4회 모두 T7 hard gate를 통과했다. 같은 T1·constant-mixed·c8 조건에서 A의 관측 지연 중앙값은 B보다 낮고, B가 A보다 나은 지표는 없었다. 따라서 이번 timeboxed 범위의 생산 적용 전략은 **A로 선택한다**.

선택의 근거와 경계는 다음과 같다.

- A를 생산 적용 전략으로 선택한다. A는 A/B 모두 통과한 T1 조건에서 더 낮은 p50·p95·p99를 보였다.
- B는 선택하지 않는다. bounded jitter를 추가했지만 성공률 이점은 없고, 측정한 모든 latency percentile이 A보다 높았다.
- C는 5xx correctness failure 때문에 선택 대상에서 제외한다.
- 이 선택은 T1·constant-mixed·c8의 근거에 한정한다. 모든 경합 조건에서 A가 항상 우수하다는 주장은 아니다.

`campaign-report.json`의 `winner: null`은 runner가 기계적으로 winner를 만들지 않는다는 뜻으로 유지한다. 이 문서는 원자료를 사람이 해석해 남기는 선택 기록이며, #787은 이 선택을 ADR로 공식화한다. 후보 PR의 병합을 이 보고서가 승인하는 것은 아니다.

> 측정 기준 시각: 2026-08-20 UTC. 정본 데이터는 [campaign report](campaign-report.json), [campaign plan](campaign-plan.json), [raw digest](raw-digests.json), [비교 계약](../../room-lock-strategy-comparison-contract.md)이다.

## 후보별 결과: 같은 요청 수에서 A의 관측 지연 중앙값이 더 낮다

모든 A/B 행은 `ROOM 시나리오 T1`, `constant-arrival-rate`, `constant-mixed`, c8(초당 8 ROOM 요청), 60초, 실행당 480 ROOM 요청이라는 같은 조건이다. A/B의 합계는 각각 4회, 1,920 ROOM 요청이다.

| 후보 | 잠금 방식 | 유효 실행 | ROOM 요청 | 성공 | 계약상 허용된 동시성 409 | 예상 밖 4xx | 5xx | contract failure | T7 상태 | 선택 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |
| A | 현행 낙관적 락 | 4 | 1,920 | 1,918 (99.896%) | 2 (0.104%) | 0 | 0 | 0 | PASS | 선택 |
| B | 낙관적 락 + bounded jitter | 4 | 1,920 | 1,917 (99.844%) | 3 (0.156%) | 0 | 0 | 0 | PASS | 미선택 |
| C | 비관 write lock | 성능 집계 제외 (p4 FAIL) | p4 기준 480 | 476 (99.167%) | 0 | 0 | 4 (0.833%) | 4 (0.833%) | 1차 FAIL, runner artifact INVALID | 제외 |

`계약상 허용된 동시성 409`는 응답 코드가 `ROOM_CONCURRENT_MODIFICATION`이고 응답 envelope가 계약에 맞은 경우다. 성공으로는 세지 않지만, 예상 밖 4xx·5xx나 contract failure와 달리 T7 hard gate를 실패시키지 않는다. C의 5xx 4건과 contract failure 4건은 **서로 다른 8건이 아니라 같은 4개 응답이 두 counter에 함께 기록된 것**이다.

| 후보 | 성공 ROOM 요청 지연 p50 중앙값 | p95 중앙값 (실행별 범위) | p99 중앙값 (실행별 범위) | 보고된 평균 HTTP RPS |
| --- | ---: | ---: | ---: | ---: |
| A | 51.689 ms | 87.731 ms (65.946–110.741) | 179.609 ms (81.033–331.018) | 8.140 |
| B | 58.445 ms | 102.966 ms (65.667–106.222) | 287.297 ms (80.338–350.009) | 8.111 |
| C | 비교 불가 | 비교 불가 | 비교 불가 | 비교 불가 |

지연 수치는 각 실행의 `room_request_duration{outcome:success}` percentile을 먼저 계산하고, A/B 각각 4개 실행 percentile의 중앙값을 다시 계산한 값이다. 즉 1,920 요청을 한꺼번에 합친 pooled percentile이 아니다.

## A와 B의 차이: A를 선택한 근거

| 지표 | A | B | A - B | 읽는 법 |
| --- | ---: | ---: | ---: | --- |
| 성공률 | 99.896% | 99.844% | +0.052%p | 차이는 1,920 요청에서 성공 1건이다. 성공률 우열 근거로는 작다. |
| p50 중앙값 | 51.689 ms | 58.445 ms | -6.756 ms (-11.560%) | 일반적인 성공 요청에서 A가 더 낮게 관측됐다. |
| p95 중앙값 | 87.731 ms | 102.966 ms | -15.235 ms (-14.796%) | 느린 5% 성공 요청에서도 A가 더 낮게 관측됐다. |
| p99 중앙값 | 179.609 ms | 287.297 ms | -107.688 ms (-37.483%) | 아주 느린 성공 요청에서 A가 더 낮게 관측됐지만, 실행 간 범위가 넓어 주의가 필요하다. |
| 평균 HTTP RPS | 8.140 | 8.111 | +0.029 (+0.358%) | 부하 발생기가 유사하게 동작했는지 보는 보조값이며, 업무 처리량 우열은 아니다. |

A의 p99 실행별 범위는 81.033–331.018 ms, B는 80.338–350.009 ms다. 두 후보 모두 tail 값이 실행마다 크게 움직인다. 이 변동은 A가 모든 조건에서 항상 더 빠르다는 증명은 아니지만, 이번에 승인한 T1 범위에서 A를 선택하지 않을 근거는 아니다. B는 추가한 bounded jitter의 이점을 이 조건에서 보이지 못했다.

## 숫자의 의미와 분모

| 지표 | 정의 | 분모·단위 | 의사결정에서의 의미 |
| --- | --- | --- | --- |
| ROOM 요청 | ROOM 시나리오가 보낸 업무 요청 수 | 실행당 480, A/B 합계 1,920 요청 | 모든 성공·동시성·5xx 비율의 기준 분모 |
| 성공 | response contract가 맞고 업무 성공으로 분류된 ROOM 요청 | ROOM 요청 수 | 실제 업무 성공 비율 |
| 동시성 409 | `ROOM_CONCURRENT_MODIFICATION`을 계약대로 반환한 응답 | ROOM 요청 수 | 예상된 동시성 경쟁 결과. 성공과 별도지만 hard gate 실패는 아님 |
| 5xx | HTTP status가 500 이상인 응답 | ROOM 요청 수 | 0이어야 하는 correctness hard gate |
| contract failure | HTTP/response envelope/domain 결과가 해당 시나리오 계약과 맞지 않은 응답 | ROOM 요청 수, 5xx와 겹칠 수 있음 | 0이어야 하는 correctness hard gate |
| p50·p95·p99 | 성공으로 분류된 ROOM 요청 지연의 50·95·99 percentile | 밀리초, 실행별 percentile의 4회 중앙값 | 낮을수록 해당 조건의 성공 응답 지연이 짧게 관측됨 |
| 평균 HTTP RPS | k6 `http_reqs` rate의 실행별 평균 | 초당 모든 HTTP 요청. 실행당 504 HTTP 요청 | 부하 발생기 비교용 보조 신호. ROOM 업무 처리량·SLO로 사용하지 않음 |

## 데이터 신뢰성: 숫자는 재현·추적 가능하지만, 결론의 범위는 좁다

검증한 데이터 단위는 run ID가 중복되지 않는 13개 source bundle이다. 그중 완료 실행은 10개(A 4, B 4, C p3·p4)이며, A/B의 집계는 각 run의 원본 count와 다시 대조했다. A/B는 `성공 + 동시성 409 + 5xx = ROOM 요청`이 각 실행에서 성립하고, 예상 밖 4xx·5xx·contract failure는 모두 0이다.

후보 SHA와 완료 run의 application revision은 candidate SHA와 일치한다. source bundle 397파일 중 Git에 보존한 non-secret 371파일은 SHA-256과 바이트 크기를 원본과 대조했다. fixture bcrypt hash가 포함된 26파일은 Git에 넣지 않고 digest·크기·제외 사유만 남겼다.

C p4는 source/candidate provenance가 맞고 raw `k6-summary.json`과 after diagnosis가 남아 있으므로 실제 5xx의 증거는 유효하다. 다만 nonzero k6 exit 뒤 `resource-signals.json`이 없어 runner artifact 상태는 `INVALID`다. 이 누락은 C의 성능 수치를 비교 불가로 만들지만, 이미 관측된 correctness `FAIL`을 없애지는 않는다.

## 이 결과로 결정할 수 있는 것과 결정할 수 없는 것

### 현재 결정 가능한 것

- 이번 생산 적용 전략은 A(현행 낙관적 락)로 선택한다.
- B는 A보다 성공률 이점이 없고 p50·p95·p99가 모두 높으므로 선택하지 않는다.
- C는 이번 T1 조건에서 correctness hard gate를 통과하지 못했으므로 선택 대상에서 제외한다.
- #787은 위 선택과 근거·재검토 조건을 ADR로 공식화한다. 후보 PR 병합은 별도 변경·CI·리뷰 단계다.

### 현재 결정할 수 없는 것

- A가 B보다 모든 경합 조건과 T2에서도 우수하다는 결론
- C가 모든 환경에서 사용할 수 없다는 전역 결론 또는 5xx의 근본 원인
- 운영 SLO, 용량 한계, DB lock wait·retry 비용의 우열
- 후보 PR 병합 순서, rebase 필요성, 현재 develop 기준 CI 결과

## 한계와 최소 후속 판단

- A/B는 원래 5회 paired/crossover gate가 아니라 후보당 4회뿐이며, T1의 constant-mixed c8 한 조건만 실행했다.
- T2, hot·spread 대조 조건, c2·c4·c16, T3·T4·T5와 c32는 보류했다.
- DB lock wait·retry·pool·CPU 원자료는 bundle에 남아 있지만 이번 timeboxed report에는 후보별 비교 지표로 정규화하지 않았다.
- C는 p4가 FAIL이어서 success latency를 A/B와 비교할 수 없다.

위 한계는 이번 선택을 무효화하지 않는다. #786은 승인된 최소 범위에서 A를 선택할 근거를 제공했고, #787은 그 선택을 ADR로 공식화한다. 다섯 번째 A/B 반복이나 T2 측정은 A 선택의 선행 조건이 아니다. 새로운 고경합 조건·운영 장애·SLO 문제가 발견될 때 재검토 증거로만 추가한다.

## 향후 재검토 시 답할 질문

- C p4의 5xx는 어떤 코드 경로·DB 상태·lock interaction에서 발생했는가?
- B의 bounded jitter 효과가 T1 mixed c8 외의 hot 또는 T2에서 달라지는가?
- A/B의 실행별 p99 변동은 앱·DB 자원 신호 또는 실행 순서와 관련이 있는가?

> 문서 관리: 소유자 `밤송이클럽 개발팀` · 작성일 `2026-08-20` · 갱신 조건 `새 후보 실행 또는 #787 잠금 전략 결정이 추가될 때`
