# ROOM 잠금 전략 비교 계약

공통 measurement harness 정본: [room-lock-comparison-harness.md](room-lock-comparison-harness.md)

## 후보와 재현 provenance

- `LOCK_BASE`: `49b960a1f7537574b39d67ff22df8890a3891ef6`
- 후보 A의 production 전략은 현행 낙관적 잠금과 즉시 재시도 최대 3회이며 production source/schema/API 계약은 변경하지 않는다.
- 결과 artifact에는 후보 source SHA와 측정 실행 SHA를 별도 필드로 남긴다.
- 고정 시각: `2026-08-17T00:00:00Z`; fixture seed: `ROOM-LOCK-01-20260817`; 동시성: 2
- Testcontainers 이미지: `postgres:18.4`; `pg_stat_statements` 활성화; background scheduler 비활성화
- 정확한 실행 명령은 아래 재현 명령과 `docs/measurements/results/room-785-a/room-785-a.json`의 `reproductionCommand`를 사용한다.

## T2 업무 정합성 gate

- 실제 production entrypoint를 호출한다: 마지막 좌석 직접 참가, 대기 등록·재활성, 대기 취소와 FIFO 자동 승격, 직접 참가 취소, `RoomStatusCorrectionCoordinator` 시작 경계.
- 마지막 좌석 직접 참가는 양쪽 commit order로 실행하고 두 command의 write 도달과 실제 transaction commit/rollback outcome을 확인한다.
- 대기 신규 등록과 대기 재활성 등록은 full ROOM에서 다른 등록 entrypoint와 양쪽 확정 순서로 경합시키고, 두 command의 write 도달과 commit order를 따르는 FIFO 순서를 확인한다.
- 좌석이 남은 ROOM의 대기 등록 거절은 commit order와 무관한 업무 사전조건 경계다. 순서 비교가 아니라 별도 precondition gate로 검증하며, 거절 command가 write 지점에 도달하지 않음을 확인한다.
- 대기 취소/자동 승격, 시작 보정과 직접 참가·대기 등록·대기 취소·참가 취소의 양쪽 순서를 각각 실행한다. 시작 경계의 참가 취소는 production 업무 규칙에 따라 `INVALID_ROOM_STATUS_TRANSITION`으로 rollback되고, 자동 승격은 별도의 시작 전 대기 취소·참가 취소 경합에서 확인한다.
- 각 경합은 같은 ROOM `version`을 읽은 뒤 commit-order를 제어하며, 테스트가 공급자 함수만 호출하는 synthetic 경로를 비교 결과로 사용하지 않는다.
- 각 round 뒤 active 참가 수와 ROOM 저장값, capacity 범위, 사용자별 ACTIVE 중복, 대기열 FIFO·상태·승격 결과, 시작 경계 잔여 상태와 부분 변경 여부를 확인한다.

## T3 오류·rollback gate

- optimistic conflict만 최대 3회 재시도하며 소진 시 `ROOM_CONCURRENT_MODIFICATION`/HTTP 409를 확인한다.
- 실제 PostgreSQL row-lock timeout은 production 참가 entrypoint에서 재현하고 SQLSTATE `55P03`, 1회 시도, HTTP 500, 참가·대기·ROOM version rollback을 확인한다.
- 두 실제 transaction이 서로 반대 순서로 ROOM row를 `SELECT ... FOR UPDATE`하여 deadlock을 만들고 SQLSTATE `40P01` victim의 HTTP 500·rollback과 survivor 성공을 확인한다.
- timeout/deadlock/technical failure의 attempt·retry 분류는 주입 상수가 아니라 실제 failure outcome과 retry trace에서 계산하고, deadlock은 retry trace가 증가하지 않음을 확인한다.
- controlled post-flush technical failure는 별도 예상 밖 오류 경계로만 확인하며 DB contention 지표의 대체물로 사용하지 않는다.
- T3는 `T3-lock-timeout`, `T3-optimistic-exhausted`, `T3-deadlock`, `T3-unexpected-technical` 네 개 raw scenario unit으로 분리하며, 각 unit의 `responseNanos` 길이와 `requestCount`가 같아야 한다.
- T3의 PostgreSQL 비용은 요청 구간에서만 집계한다. 각 요청 직전 `pg_stat_statements`를 reset하고 요청 종료 직후 읽어 fixture 준비·lock/deadlock 준비·검증 SQL 비용을 비교 metric에서 제외한다.

## 공통 결과와 provenance artifact

- 모든 T-ID 결과는 `candidate`, `scenario`, `requestCount`, `success`, `businessFailure`, `concurrencyFailure`, `technicalFailure`, `conflictCount`, `retry0`, `retry1`, `retry2`, `exhausted`, `responseNanos`, `calls`, `totalExecMs`, `rows`, `sharedBlksHit`, `sharedBlksRead`를 같은 이름으로 남긴다.
- `ROOM785_RAW` 로그는 실행 trace이고, 비교 입력은 tracked JSON artifact다.
- artifact에는 `LOCK_BASE`, 후보/source SHA, 측정 실행 SHA, canonical LF 기준 digest, Java/PostgreSQL/image/OS/CPU/config, fixture/time/concurrency/retry budget, 정확한 command를 함께 보존한다.
- artifact의 `artifactSha256`는 해당 필드 한 줄을 제외한 canonical UTF-8 LF bytes 기준이며, 선언값·테스트 상수·현재 파일 계산값이 모두 일치해야 한다.
- 결과 경로: `docs/measurements/results/room-785-a/room-785-a.json`
- 현재 artifact는 공통 T1 1개·T2 45개(단일 3개와 14개 시나리오의 3회 반복)·T3 4개 하네스에서 clean HEAD `a575dc8a95621d7c8c5a313157d975912d7fb510`로 재측정한 결과다. `artifactSha256`는 `4D63FA71CDDC8C8DF894ED518BE957183F56008BD54A79D83B5BF40CC604502D`이며, 공통 `scenarioSetDigest`는 `ECD025F2CC7B4A565AF32FBF851190D0FE744C4DF743E571EFF6AA71E8B5D992`다.

## 범위 제외

- 후보 C 전용 `PESSIMISTIC_WRITE` production 잠금은 후보 A PR의 범위가 아니므로 변경하지 않는다.
- 최종 잠금 전략 선택과 production merge 여부는 Issue #786 및 ADR 승인 후 결정한다.

## INVALID 경계

- LOCK_BASE·candidate/source SHA·측정 실행 SHA·artifact digest 중 하나라도 없거나 서로 다른 입력을 가리키면 비교 근거로 사용하지 않는다.
- 고정 fixture/time, 동시성, gate, retry budget, DB image, production source/schema/API 계약이 달라진 실행을 합치지 않는다.
- T1~T3 중 실행한 시나리오의 raw 결과나 실제 DB contention/rollback 증거가 누락되면 해당 결과는 `INVALID`다.

## 재현 명령

```powershell
docker version
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.*" --rerun --fail-fast
```
