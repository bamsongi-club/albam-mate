# MATCH-01 응답 완료 지연 측정 계약

## 목적과 범위

이 문서는 [MATCH-01 완료 기준](../p2/matching.md#완료-기준)의 `MATCH-01-AC9` 중 제안 응답 완료 지연을 후보 선점 baseline과 분리해 재현 가능하게 측정한다. 후보 선점·`WAITING → PROPOSED`·Proposal/Member 저장은 [후보 탐색 baseline 측정 계약](match-01-candidate-search-baseline-contract.md)이 소유하며, 이 문서는 열린 제안에 대한 응답 명령의 최종 영속 상태와 응답 DTO 준비까지를 소유한다.

이 측정은 네트워크 왕복·클라이언트 렌더링을 운영 SLO로 확정하지 않는다. 응답 명령의 입력·인증·인가 검증이 끝난 뒤 응답 Command Executor가 측정 시작 시각을 기록하고, 상태 전이·필요한 Party/접근 관계·멱등성 기록을 커밋한 뒤 응답 DTO를 조합한 시각까지를 하나의 응답 완료 latency 표본으로 기록한다.

## 고정 fixture와 시나리오

| 항목 | 고정값 또는 필수 기록값 |
| --- | --- |
| 기준 SHA | 실행한 `git rev-parse HEAD` 값 |
| fixture seed | `MATCH-01-RESPONSE-COMPLETION-V2` |
| 공통 Proposal 구성 | 두 연결 요청의 사용자 인원 범위 `[2, 4]`, 실제 `party_size = 2`로 고정한다. 각 `OPEN` Proposal은 서로 다른 synthetic 사용자 요청 2개와 `PENDING` Member 2개를 정확히 가지며 두 요청은 `PROPOSED`, `responded_at`은 NULL이다. 게임·플랫폼 필드는 fixture에 넣지 않는다 |
| 데이터 | 비종결 `ACCEPT`·`REQUEUE`·`CANCEL`은 각각 Proposal 1,000개·Member/연결 요청 2,000개와 명령 대상 Member 1,000명을 사용한다. 마지막 `ACCEPT`는 Proposal 500개·Member/연결 요청 1,000개를 사용하고 두 Member 모두 명령 대상이다. 모든 fixture에서 후보 선점·`PREPARING`·`ACTIVE` Party와 이전 응답 멱등성 기록은 0건이다 |
| 시나리오 | `ACCEPT` 비종결 응답, 마지막 `ACCEPT`에 의한 최종 확정, `REQUEUE`, `CANCEL`을 각각 독립 fixture로 실행한다 |
| 시각·동시성 | fixture 트랜잭션의 `transaction_timestamp()`을 `fixtureReferenceTime`으로 사용하고 모든 Proposal의 `created_at`과 `respond_by = fixtureReferenceTime + 30초`를 고정한다. barrier는 5초 안에 해제하며 모든 명령의 `operationTime < respondBy`를 검증한다. 비종결 `ACCEPT`·`REQUEUE`·`CANCEL`은 각 Proposal의 member ordinal 1만 명령하고, 마지막 `ACCEPT`는 두 Member가 같은 barrier에서 각각 최초 응답한다 |
| 표본 | 각 시나리오·round의 latency 모집단은 정확히 1,000개의 최초 유효 명령이다. 마지막 `ACCEPT`는 500건의 정상 비종결 `PROPOSED`와 500건의 정상 최종 확정을 모두 포함하며 어느 응답도 패자·중복 응답으로 분류하지 않는다. correctness-only 중복 경합 명령은 latency p50/p95/p99 모집단에서 제외하고 최종 상태 assertion에만 포함한다 |
| 배경 작업 | 응답 경로와 무관한 scheduler·relay·retention 작업은 끄거나, 끌 수 없으면 이름·설정·실행 SQL을 결과에 기록한다 |

시나리오별 fixture와 최종 상태 assertion은 다음으로 고정한다.

| 시나리오 | 명령 뒤 필수 상태 |
| --- | --- |
| 비종결 `ACCEPT` | Proposal `OPEN`; 대상 Member `ACCEPTED`·비대상 Member `PENDING`; 두 요청 `PROPOSED`; Party 0개 |
| 마지막 `ACCEPT` | Proposal `CONFIRMED`; 두 Member `ACCEPTED`; 두 요청 `MATCHED`; Proposal당 `PREPARING` Party 1개와 참가자 접근 2개 |
| `REQUEUE` | Proposal `DECLINED`; 대상 Member `REQUEUED`; 대상 요청은 새 `queuedAt`·`prioritySince`의 `WAITING`; 비대상 Member는 `PENDING`, 비대상 요청은 기존 `queuedAt`·`prioritySince`를 유지한 `WAITING`; Party 0개 |
| `CANCEL` | Proposal `CANCELED`; 대상 Member `CANCELED`; 대상 요청 `CANCELED`; 비대상 Member는 `PENDING`, 비대상 요청은 기존 `queuedAt`·`prioritySince`를 유지한 `WAITING`; Party 0개 |

fixture 생성·truncate·통계 초기화는 측정 구간 밖에서 끝낸다. 각 시나리오와 round는 위 전체 상태의 Proposal·Member·연결 요청을 새로 만들며 이전 응답·Party·잠금 상태를 재사용하지 않는다. DB 삽입 전 `scenario,proposalOrdinal,memberOrdinal,userFixtureOrdinal,minPartySize,maxPartySize,partySize,initialRequestStatus,initialResponseStatus,commandTarget` 열 순서와 Proposal·Member ordinal 오름차순, UTF-8·LF·마지막 LF를 사용하는 CSV를 만들고 SHA-256을 `fixtureInputSha256`으로 기록한다. materialized fixture manifest에는 이 입력과 실제 Proposal·Member·request ID, 기대 결과 집합을 보존하되 결과 artifact에는 사용자 식별자를 원문으로 공개하지 않는다.

correctness-only 중복 경합은 네 시나리오 각각에 대해 같은 전체 fixture와 1,000개 논리 명령을 새로 만든 뒤, 각 논리 명령을 같은 action·body·`Idempotency-Key`로 동시에 두 번 보내는 2,000개 물리 요청으로 고정한다. latency 표본에는 넣지 않고, 키별 멱등성 기록 1개·논리 명령 한 번의 상태 전이·위 표의 최종 상태를 assertion한다. 마지막 `ACCEPT`에서만 Proposal당 Party 1개를 요구하며 나머지 세 시나리오는 Party 0개를 요구한다.

## round와 수집 방식

1. 각 시나리오와 같은 fixture·topology로 warm-up round 1회를 실행하고 판정에서 제외한다.
2. fixture와 PostgreSQL 통계를 초기화한 뒤 시나리오별 measured round 3회를 실행한다.
3. 각 논리 응답 명령은 입력·인증·인가 검증이 끝나고 Command Executor가 `operationTime`과 latency 시작 시각을 고정한 뒤, 영속 상태 전이와 멱등성 결과가 commit되고 응답 DTO가 조합될 때 종료 시각을 기록한다. validation rejection은 정상 성공 latency 표본에 섞지 않고 원인별 실패 표본으로 별도 보존한다.
4. 각 표본에는 action, terminal/non-terminal 결과, retry 횟수, DB lock wait, HTTP status·고정 error code, 최종 상태 assertion 결과를 기록한다. 사용자 ID·닉네임·메시지 본문·멱등키 원문은 metric label이나 중앙 로그에 넣지 않는다.
5. measured round의 입력·결과 수, 최종 Party 수, 중복 응답·중복 Party·부분 성공·잘못된 재대기 수와 scheduler/DB 관측 창의 UTC 시작·끝 시각을 함께 보존한다.

기술 오류, timeout, matcher 하나의 조기 종료, fixture 개수 불일치, 원자료·DB 통계·lock wait 관측 누락은 표본에서 제외하지 않는다. 해당 시나리오 round 전체를 `INVALID`로 표시하고 원인과 이미 수집한 원자료를 보존한다.

## 산식과 결과 채택

- 유효한 시나리오 measured round의 응답 완료 latency p50·p95·p99는 유효 명령 표본 `n = 1,000`을 오름차순 정렬해 nearest-rank로 계산한다. p95는 `ceil(0.95 × 1,000) = 950`번째 값을 사용한다. 성공·정상 업무 거절·재시도 결과는 action과 결과 상태별로 분포를 나누고 전체 응답 완료 비교에도 포함한다.
- 각 시나리오의 세 measured round가 모두 유효하고 1,000개 표본·fixture manifest·DB 통계·lock wait·최종 상태 assertion이 모두 보존된 경우에만 결과를 비교한다. 결과 문서는 시나리오별 p50·p95·p99, 처리량, retry 수, lock wait, 실패율과 세 round p95의 중앙값·최댓값을 함께 제시한다.
- `RESPONSE_BASELINE_ACCEPTED`는 마지막 `ACCEPT` 경합에서 Proposal별 하나의 최종 확정, 패자 응답의 중복 전이 없음, `REQUEUE`·`CANCEL`의 결과 상태 일치, 중복 Party·부분 성공 0건, 모든 current-state assertion 통과일 때만 부여한다. 이는 운영 SLO 달성이나 후보 선점 baseline 통과를 뜻하지 않는다.
- `INVALID`는 실행·관측 계약을 충족하지 않아 비교에 쓸 수 없는 결과다. `FAILED`는 실행·관측 계약을 충족한 뒤 최종 상태 정합성 검증이 실패한 결과다. 기술 오류·timeout·matcher 조기 종료·fixture 개수 불일치·관측 누락은 `INVALID`로, 실행 완료 후 최종 상태 정합성 위반은 `FAILED`로 분류한다. 두 결과 모두 응답 성능 통과나 Redis 업무 락 도입의 근거로 사용하지 않는다.

## 원자료 보존과 재검토

구현 뒤 결과는 `docs/measurements/results/match-01/response-completion/`에 시나리오·round별 JSON으로 보존한다. 결과에는 실행한 40자 Git commit SHA, 환경, fixture seed·`fixtureInputSha256`·materialized manifest digest, warm-up 여부, 각 measured round의 1,000개 비식별 latency raw sample과 action·결과 상태, retry·lock wait·DB 통계, 상태 assertion, 결과 판정과 원자료 SHA-256을 포함한다. 사용자 ID·닉네임·메시지 본문·멱등키 원문은 raw sample이나 metric label에 넣지 않는다. 개선 전후 비교는 같은 시나리오·fixture·topology·환경 profile의 `RESPONSE_BASELINE_ACCEPTED`끼리만 수행한다.

다음 경우 응답 경로의 쿼리·인덱스·트랜잭션 개선을 먼저 비교하고, 해결되지 않는 근거가 있으면 별도 ADR 재검토를 연다.

- 응답 완료 p95·lock wait·retry가 지속적으로 높거나 시나리오별 결과 차이가 정합성을 훼손하는 경우
- 최종 `ACCEPT` 경합에서 중복 Party·부분 성공·패자 중복 전이가 한 건이라도 발생하는 경우
- 구현·관측 범위를 바꿔 candidate claim baseline과 응답 완료 측정을 하나로 합쳐야 하는 요구가 생기는 경우

> 문서 관리: 소유자 `밤송이클럽 MATCHING 담당` · 최종 검증일 `2026-08-15` · 폐기 조건 `MATCH-01 응답 완료 지연이 다른 단일 측정 정본으로 대체될 때`
