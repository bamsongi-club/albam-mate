# ROOM 잠금 비교 공통 measurement harness 계약

후보 A·B·C의 비교 결과는 전략 구현부만 다르고, 아래 measurement harness 계약은 동일해야 한다. 공통 시나리오 ID와 metric field의 실행 검증은 `RoomLockComparisonMeasurementContract`가 담당한다.

## 고정 입력

- `LOCK_BASE`: `49b960a1f7537574b39d67ff22df8890a3891ef6`
- fixed time: `2026-08-17T00:00:00Z`
- fixture seed: `ROOM-LOCK-01-20260817`
- concurrency: `2`
- PostgreSQL: Testcontainers `postgres:18.4` with `pg_stat_statements`
- background scheduler와 외부 notification/chat relay: disabled
- 각 artifact는 candidate source SHA와 measurement execution SHA를 분리해 기록한다.

## 공통 모집단

T1은 `T1` 한 표본이다. T2는 단일 표본 3개와 반복 표본 14개로 고정한다. 반복 표본은 각 시나리오를 정확히 3회 실행한다.

단일 T2 시나리오:

- `T2-due-room-order`
- `T2-lock`
- `T2-rollback`

반복 T2 시나리오:

- `T2-waitlist-new-promotion`
- `T2-waitlist-new-cancel-first-promotion`
- `T2-waitlist-reactivation-promotion`
- `T2-waitlist-reactivation-cancel-first-promotion`
- `T2-start-direct-participation-first`
- `T2-start-correction-first`
- `T2-start-waitlist-new-registration-first`
- `T2-start-waitlist-new-correction-first`
- `T2-start-waitlist-reactivation-registration-first`
- `T2-start-waitlist-reactivation-correction-first`
- `T2-start-participation-cancel-first`
- `T2-start-participation-correction-first`
- `T2-start-waitlist-cancel-first`
- `T2-start-waitlist-correction-first`

T3는 하나의 aggregate가 아니라 아래 네 단위로 보존한다.

- `T3-lock-timeout`
- `T3-optimistic-exhausted`
- `T3-deadlock`
- `T3-unexpected-technical`

공통 scenario set digest는 `RoomLockComparisonMeasurementContract.scenarioSetDigest()`의 canonical LF 입력으로 계산한다.

## Gate와 metric

모든 T2 경합은 실제 production entrypoint와 PostgreSQL transaction을 사용하고, 두 요청의 ROOM snapshot·write 도달·transaction completion order를 확인한다. A/B는 동일 version read와 commit-order gate를 사용하고 C는 `PESSIMISTIC_WRITE` lock acquisition을 사용하는 후보별 adapter를 사용하되, 외부 관찰 계약과 fixture·반복·집계 단위는 동일하다.

시나리오마다 다음 field를 같은 이름·순서·산식으로 남긴다.

`candidate`, `scenario`, `requestCount`, `success`, `businessFailure`, `concurrencyFailure`, `technicalFailure`, `conflictCount`, `retry0`, `retry1`, `retry2`, `exhausted`, `responseNanos`, `calls`, `totalExecMs`, `rows`, `sharedBlksHit`, `sharedBlksRead`

각 요청의 `responseNanos` 개수와 outcome 합계, retry bucket 합계는 `requestCount`와 같아야 한다. T3의 lock timeout은 SQLSTATE `55P03`, deadlock은 `40P01`, optimistic exhaustion은 승인된 최대 3회와 HTTP 409, unexpected technical failure는 재시도 없는 HTTP 500 및 rollback을 실제 production 경로에서 확인한다.

## Provenance gate

artifact에는 LOCK_BASE, candidate/source SHA, measurement execution SHA, scenario set digest, fixed input, environment, exact test command와 artifact digest를 함께 기록한다. 선언한 execution SHA가 실제 clean checkout에서 해당 scenario를 호출할 수 없거나, 세 후보의 scenario set·반복·T3 단위·gate·metric schema 중 하나라도 다르면 결과는 `INVALID`다.
