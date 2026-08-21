# ROOM 최종 Mixed·Soak campaign — Matrix PASS, Mixed INVALID, Soak 미실행 (2026-08-21)

## 결론

Issue #784의 전체 release gate는 `completed-with-limitations`이며, 최종 판정은 `NOT_RELEASE_GATE_PASS`다.

- 공식 T1–T5 matrix는 유효한 25/25 run이 `PASS`였다.
- Mixed는 승인 profile로 960개 arrival까지 실제 실행했지만, 앱이 보존하는 aggregate evidence 계약이 `INVALID`였다.
- Mixed의 나머지 2회와 15분 Soak은 잘못된 결과를 성공으로 포장하지 않기 위해 실행하지 않았다.
- 따라서 이번 결과는 **승인된 matrix 범위의 ROOM 정합성·DB 비용 근거**로는 사용할 수 있지만, **Mixed·Soak까지 포함한 최종 release gate PASS**로는 사용할 수 없다.

이번 campaign은 최대 수용량이나 운영 SLO를 정하지 않는다. c32 이상의 capacity, 실제 운영 트래픽의 안정성, Mixed·Soak의 지속성은 이 결과로 결론내리지 않는다.

## 저장소 경계

앱 저장소 worktree에는 이 보고서·manifest·소유자 README만 추가했다. production code와 `load-tests/k6/jiwon/**`는 수정하지 않았다.

Terraform apply·deploy·room-k6 실행·destroy와 runner 보정은 별도 `albam-mate-infra` worktree의 `codex/room-k6-local-runner`에서 수행했다. infra 변경은 로컬에만 남겼고 push와 PR은 생성하지 않았다.

## 실행 계약과 coverage

| Gate | 계획 | 실제 시도 | `included` | 판정 |
| --- | ---: | ---: | ---: | --- |
| T1 matrix | 6 | 6 | 6 | `PASS` |
| T2 matrix | 7 | 7 | 7 | `PASS` |
| T3 matrix | 3 | 3 | 3 | `PASS` |
| T4 matrix | 3 | 3 | 3 | `PASS` |
| T5 matrix | 6 | 7 | 6 | `PASS` · 원본 host ACTIVE 1회는 제외하고 replacement 포함 |
| Mixed | 3 | 3 | 0 | `INVALID` |
| Soak | 1 | 0 | 0 | `NOT_RUN` |

Matrix의 25개 유효 조건은 T1 hot/spread c2·c4·c8, T2 distinct hot/spread c2·c4·c8 및 duplicate hot c2, T3 race/wait-first/cancel-first, T4 c2·c4·c8, T5 public/host/participant × ACTIVE 1/10이다.

Mixed 승인 입력은 hot/spread ROOM 2/16개, tier 50/50%, T1/T2/T5 50/25/25%, `16/s × 60s`, pre-allocated/max VU 32/64, seed `78401`이다. Soak 입력은 hot/spread ROOM 1/10개, 동일한 비율, `10/s × 900s`, pre-allocated/max VU 20/40, seed `78402`이다. 각 입력의 전체 계획은 [campaign manifest](evidence/room-improvement-final-campaign-2026-08-21.json)에 보존했다.

## T1–T5 matrix 결과

25개 `included` run의 합계는 다음과 같다.

| 항목 | 결과 |
| --- | ---: |
| HTTP/ROOM request | 93,714 |
| HTTP failed request | 0 |
| success / business / concurrency / unexpected | 93,599 / 59 / 56 / 0 |
| `dropped_iterations=0` | 25/25 |
| before / after diagnosis `PASS` | 25/25 |
| resource signal `PASS` | 25/25 |
| infra phase exit code 0 | 25/25 |

모든 run에서 outcome equation이 맞았고, 예상 밖 4xx·5xx와 contract failure는 0이었다. `business`와 `concurrency` 결과는 해당 시나리오가 검증하려는 정상적인 도메인 결과이므로 HTTP 실패와 혼동하지 않는다.

| 시나리오 | run | request | success | business | concurrency | unexpected | query calls |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| T1 | 6 | 140 | 112 | 0 | 28 | 0 | 4,278 |
| T2 | 7 | 142 | 114 | 0 | 28 | 0 | 3,043 |
| T3 | 3 | 14 | 10 | 4 | 0 | 0 | 330 |
| T4 | 3 | 70 | 15 | 55 | 0 | 0 | 1,345 |
| T5 | 6 | 93,348 | 93,348 | 0 | 0 | 0 | 871,683 |

특히 T1 attempt 분포는 `140 = 112 success + 28 concurrency`, T2 attempt 분포는 `142 = 114 success + 28 concurrency`였다. 즉 이 matrix에서는 동시 참가·대기 등록의 허용된 경합 결과가 발생했지만, 정합성 diagnosis와 unexpected failure gate를 깨지 않았다.

## DB 비용과 발생기 관측

유효 matrix 전체에서 query call은 880,679회, 합산 query time은 25,248.658ms였다. 최대 관측값은 DB CPU 49.64%, active connection 26개, lock wait 2개, Hikari pending 0개, load-generator CPU 22%, RSS 59,330,560 bytes였다.

이 값은 T5의 6개 read workload가 query call의 대부분을 차지한다는 사실과, 이 조건에서 connection pool 대기 없이 비용이 발생했다는 점을 설명한다. 하지만 특정 운영 부하에서의 처리 한계나 SLO를 의미하지 않는다.

T3 signal artifact 3개는 실행 후 infra aggregator의 empty-sample 표현(`0`과 `null`)을 정규화하여 다시 생성했다. 이는 runner evidence 보정이며 앱 production code 변경이나 새 원격 실행을 뜻하지 않는다.

## Mixed 결과

첫 번째 유효 실행 시도는 `K6_SETUP_TIMEOUT` 환경값을 적용해 setup을 통과했고, k6는 `0`으로 종료했다.

- target arrivals: 960
- actual arrivals: 960
- raw k6 dropped iterations: 0
- HTTP failed request: 0
- tier별 tagged 요청: hot T1/T2/T5 `240/120/120`, spread T1/T2/T5 `240/120/120`
- query calls / query time: 15,753 / 1,489.832ms
- DB CPU / lock wait / Hikari pending: 13.53% / 0 / 0
- load-generator CPU / RSS: 95% / 487,620,608 bytes

그럼에도 `final-result.json`의 Mixed aggregate는 `INVALID`였다.

1. raw summary의 untagged base metric이 tier·operation·outcome series로 잘못 집계되어 tag schema가 invalid가 됐다.
2. k6 summary는 dropped iteration이 0인 경우 `dropped_iterations` metric 자체를 내보내지 않았고, 앱 aggregate는 이를 `0`이 아니라 `null`로 기록했다.

따라서 실제 960 arrival과 transport 결과는 관찰했지만, 필수 evidence contract가 완전하지 않아 Mixed `PASS`로 승격하지 않았다. 이 문제는 이번 이슈에서 임의로 `load-tests` 코드를 수정해 덮지 않고 후속 작업으로 남긴다.

### Mixed 제외 이력

| 시도 | 상태 | 제외 사유 |
| --- | --- | --- |
| `room-improvement-final-mixed-r1` | `INVALID`, k6 exit 100 | 승인 profile setup이 기본 setup timeout을 초과 |
| `room-improvement-final-mixed-r1-retry-20260821` | `INVALID`, k6 exit 255 | 지원하지 않는 `--setup-timeout` CLI flag 사용 |
| `room-improvement-final-mixed-r1-retry2-20260821` | `INVALID`, k6 exit 0 | 실제 960 arrival은 완료했지만 Mixed aggregate contract INVALID |

R2·R3은 R1의 deterministic contract invalid를 해결하지 않은 상태에서 반복하지 않았고, 같은 이유로 Soak도 시작하지 않았다. Soak bundle은 static `validate --for-execution`만 통과한 `VALIDATED_ONLY` artifact다.

## 마지막 gate에서 얻는 결론

이번 결과로 확정할 수 있는 것은 다음 네 가지다.

1. 승인된 공식 T1–T5 matrix 안에서는 ROOM의 capacity·active participant·대기열/FIFO·중복 관련 사후 정합성 gate가 25/25 유지됐다.
2. T1/T2 경합 결과와 retry/DB 비용을 함께 관찰할 수 있었고, 이 matrix에서 unexpected failure나 pool pending은 발생하지 않았다.
3. T5 read workload가 DB query 비용의 대부분을 만든다는 점과 Mixed 16 arrival/s에서 load generator가 95% CPU까지 올라간다는 비용 신호를 확보했다.
4. Mixed aggregate의 증거 계약과 Soak 지속 실행이 남아 있으므로 P1 ROOM 기반을 “matrix 범위에서 유효”하다고 판단할 수는 있어도, #784 전체 release gate를 닫을 수는 없다.

다음 gate는 별도 승인 후 Mixed aggregate 계약을 고치거나 validator와 k6 summary 계약을 명시적으로 정렬하고, 동일 release·동일 입력으로 Mixed 3회와 Soak 1회를 다시 실행하는 것이다. 그때만 전체 gate를 `PASS`로 재판정할 수 있다.

## Teardown과 보존 경계

Terraform stack은 94개 managed resource를 destroy exit 0으로 정리했다. destroy 후 Terraform outputs는 `{}`였고 `run.sh status`는 올라온 stack이 없음을 반환했다. 임시 `perf.env`는 원본 SHA로 복원했다. 공유 ECR·SSM은 campaign 소유가 아니므로 삭제하지 않았다.

원시 bundle은 local-only로 보존하며, Git에는 비밀이 아닌 campaign metadata·coverage·aggregate·SHA-256만 남겼다. password, credential-derived hash, token, CSRF, target URL, fixture/resource ID, raw SQL과 raw log는 문서와 manifest에 넣지 않았다. 이 저장소의 SHA-256만으로 local-only raw bundle 내용을 독립 재검증할 수는 없다.

검증 근거는 [campaign manifest](evidence/room-improvement-final-campaign-2026-08-21.json)와 기존 [ROOM k6 실행 계약](../../../../load-tests/k6/jiwon/README.md)을 따른다.

- 소유자: 밤송이클럽 백엔드 팀
- 최종 검증일: 2026-08-21
- 폐기 조건: 동일 release에서 Mixed 3회와 Soak 1회의 필수 aggregate·정합성 gate가 유효 `PASS`가 된 후 후속 campaign으로 대체될 때
