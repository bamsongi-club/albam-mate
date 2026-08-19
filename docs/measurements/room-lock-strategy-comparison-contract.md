# ROOM 잠금 전략 비교 계약

공통 measurement harness 정본: [room-lock-comparison-harness.md](room-lock-comparison-harness.md)

## 후보와 재현 provenance

- LOCK_BASE: `49b960a1f7537574b39d67ff22df8890a3891ef6`
- 후보 B production source SHA: `433d835eadff1da25d9a83d3d0ee2dc8cd748480` (bounded jitter 재시도 구현이 확정된 커밋)
- 측정 실행 Git HEAD: `22022ed73984ec0e215ed14638a3818f6fb15138` (현재 공통 harness에서 artifact를 만든 clean repository HEAD). 후보 production source와 측정 harness의 식별자는 같은 의미가 아니므로 별도로 남긴다.
- base-to-head diff digest: `fd6bda84b96d86a45ecb6114333035aea375f4e600c6c6dbc32da6543c899b81` (`49b960a1..22022ed7`). 산식은 `sha256(git diff --binary <LOCK_BASE>..<측정 실행 SHA> bytes)`이며 `core.autocrlf=input`, `core.eol=lf` 체크아웃 기준이다. 개행·`diff` 출력 설정이 다른 환경에서는 같은 커밋 쌍이라도 값이 달라질 수 있으므로 재현 시 이 설정을 함께 고정한다.
- 실행 환경 값(Java, PostgreSQL, image, OS, CPU, 설정)은 아래 보존 artifact의 동명 필드에 고정하며, 문서와 artifact 값이 다르면 `INVALID`다.
- 후보 B는 낙관 락, 최초 시도 1회와 재시도 2회를 합쳐 최대 3회만 실행한다. retry 2는 `0~5ms`, retry 3은 `0~10ms` bounded full jitter이며 exponential backoff는 사용하지 않는다.
- 테스트는 실제 production entrypoint의 request seed, derived jitter seed, 실제 delay trace를 읽어 seed 관계와 각 상한을 검증한다.
- 고정 시각: `2026-08-17T00:00:00Z`; fixture seed: `ROOM-LOCK-01-20260817`; 동시성: 2; gate: 같은 ROOM version을 읽은 뒤 비우선 command는 우선 transaction completion까지 대기하고 실제 completion order를 assertion한다.

## T2 업무 정합성 gate

- `RoomParticipationService`의 마지막 좌석 동시 참가, `RoomWaitlistCommandService`의 대기 등록·취소·재활성, `RoomParticipationCancelService`의 취소·FIFO 자동 승격을 실제 PostgreSQL 트랜잭션으로 호출한다.
- 각 시나리오 뒤 `active_participant_count = ACTIVE participation 수`, capacity 범위, 사용자별 ACTIVE 중복 없음, waitlist 상태와 승격 결과를 확인한다.
- 두 production entrypoint를 별도 thread에서 시작한다. 두 최초 `RoomRepository.findById`가 같은 ROOM version을 읽은 뒤, 비우선 command는 즉시 대기하고 우선 command의 transaction 완료 뒤에만 업무 사전조건을 계속 판단한다. ROOM read 또는 repository write(`flush`/`claimVersion` 및 waitlist 조건부 변경) 시 현재 transaction에 synchronization을 등록하고, `afterCompletion`의 commit/rollback을 관찰한 뒤 실제 command completion order가 우선·비우선인지 assertion한다.
- full ROOM의 대기 신규 등록과 대기 재활성 등록은 각각 다른 등록 entrypoint와 양쪽 확정 순서로 경합시키고, 두 command의 write 도달과 commit order를 따르는 FIFO 순서를 assertion한다. 재활성은 단일 스레드 순차 호출로 대체하지 않는다.
- 좌석이 남은 ROOM의 대기 등록 거절은 commit order와 무관한 업무 사전조건 경계다. 순서 비교가 아니라 별도 precondition gate로 검증하며, 거절 command가 write 지점에 도달하지 않음을 assertion한다.
- 대기 취소/자동 승격은 취소 우선 순서에서 두 command의 write 도달을 assertion한다. 승격 우선 순서에서는 대기 취소가 우선 transaction 완료 뒤 최신 상태를 다시 판단해 `WAITLIST_ENTRY_NOT_FOUND`로 write 전에 끝나는지 assertion한다. 두 참가 취소 순서는 양쪽 command의 write 도달을 assertion한다.
- 시작 경계는 correction-first/mutation-first 각각에 대해 직접 참가, 대기 신규 등록, 대기 취소, 참가 취소·자동 승격을 모두 실행하고 ROOM 상태·대기 상태·활성 대기 0과 부분 변경 없음을 확인한다.
- C의 `PESSIMISTIC_WRITE` lock-acquisition gate는 후보 C에서만 사용한다.

## T3 오류·rollback gate

- optimistic conflict만 재시도하며 3회 소진은 `ROOM_CONCURRENT_MODIFICATION`/HTTP 409이다.
- capacity 업무 거절은 재시도하지 않고 업무 오류(HTTP 409)로 남긴다.
- `repository.flush()`가 실제 PostgreSQL flush를 마친 직후 test proxy가 주입하는 controlled post-flush technical failure는 재시도하지 않고 unhandled API 오류(HTTP 500)로 분리한다. 이는 예상 밖 technical 5xx 경계 확인용이며 PostgreSQL lock timeout 또는 deadlock을 가장하지 않는다.
- 실제 row-lock timeout은 별도 `REQUIRES_NEW` transaction/connection이 같은 `rooms` 행을 `SELECT ... FOR UPDATE`로 보유한 상태에서 `RoomParticipationService` production entrypoint를 호출해 재현한다. holder가 활성화된 동안에만 repository proxy가 service transaction에 바인딩된 `JdbcTemplate`으로 `delegate.flush()` 직전 `SET LOCAL lock_timeout = '100ms'`를 실행한다. 테스트는 PostgreSQL SQLSTATE `55P03`, HTTP 500, retry jitter trace 증가 없음, 참가·대기 행 없음, ROOM version과 참여 인원 불변식을 확인하고 `finally`에서 lock release·holder 완료·executor shutdown과 `SET LOCAL` 범위 종료를 보장한다.
- controlled deadlock은 두 실제 participation transaction이 서로 반대 순서로 `rooms` 행을 `SELECT ... FOR UPDATE`하도록 test proxy가 각 transaction의 최초 ROOM read 직후 primary/secondary 행 잠금을 유도해 재현한다. 테스트는 victim 하나만 실패하는 것, PostgreSQL SQLSTATE `40P01`, HTTP 500, retry jitter trace 증가 없음(재시도 없음), survivor의 참가 성공과 두 ROOM의 참여 인원 불변식을 확인하고 `finally`에서 gate 해제·각 요청 Future 완료·executor `awaitTermination`을 보장한다.

## 공통 결과와 보존 artifact

모든 T-ID의 raw scenario는 `candidate`, `scenario`, `requestCount`, `success`,
`businessFailure`, `concurrencyFailure`, `technicalFailure`, `retry0`, `retry1`,
`retry2`, `exhausted`, fixture/time과 `ROOM785_RAW`를 같은 JSON artifact에 남긴다. 별도 `retryEvidence`에는 retrier와 waitlist registration coordinator 양쪽의 각 retry request tuple을 `requestSeed`, `derivedSeed`, `attempt`, `maxDelayMillis`, `delayMillis`, `roomId`, logger/event와 함께 남긴다. `retry0/1/2`는 measured retrier의 outcome bucket이고, `retryEvidence`의 request·tuple count는 두 bounded-jitter logger의 raw tuple 집계다.

- ROOM785_RAW 보존 경로: `docs/measurements/results/room-785-b/room-785-b.json`
- artifact SHA-256: `73494CC53B0B6C19B63A85F1FF3F6E1935FE9C019C2241D1EC97080726A599E6`은 `artifactSha256` 한 줄을 제외한 canonical UTF-8 LF bytes 기준 값이다. artifact 자신은 자신의 checksum을 포함하지 않아 순환하지 않는다.
- 현재 artifact는 원격 B gate/jitter 변경 위에 공통 scenario·반복·T3 unit·metric 계약을 통합한 harness에서 clean HEAD `22022ed7`로 재측정한 결과다. T1 1개(양쪽 commit 순서 4개 요청 집계), T2 45개, T3 4개 round와 공통 `scenarioSetDigest`를 보존하며 `comparisonStatus=VALID`이다.
- `ROOM785_RAW` 필드 이름과 순서는 후보 A와 같은 `METRIC_FIELDS`로 고정해 후보 간 같은 필드로 비교한다.
- artifact는 candidate/source·execution SHA, PostgreSQL image, Java, OS, CPU, retry budget, gate와 정확한 재현 명령을 함께 보존한다.
- 현재 B artifact는 A/C와 동일한 T1/T2/T3 `1/45/4` round, 3회 반복, 공통 scenario-set digest와 metric field를 보존한다. B 고유의 bounded-jitter retry tuple은 공통 metric field와 별도 evidence로 보존하며, artifact provenance와 checksum은 현재 파일·실행 SHA와 일치한다.
- PostgreSQL 테스트는 이 tracked artifact를 쓰지 않는다. 테스트는 파일을 read-only로 읽어 존재와 canonical LF 기준 고정 SHA-256을 검증하므로 Windows checkout의 CRLF 변환에 영향을 받지 않는다. 새 실행의 일시 결과는 Gradle build 출력으로만 취급한다.

## INVALID 경계

다음 중 하나라도 성립하면 결과는 비교 근거가 아니라 INVALID다.

- LOCK_BASE·candidate/source SHA·측정 실행 SHA 또는 artifact SHA-256이 없거나 서로 다른 입력을 가리킨다.
- Docker/Testcontainers PostgreSQL이 아닌 DB, 고정 fixture/time·동시성·gate·retry budget이 다른 실행을 합친다.
- raw artifact에 T1~T3 중 실행한 scenario의 결과가 없거나, artifact checksum이 현재 파일과 다르다.
- 후보 B 외 production source, API, schema, retry 횟수 또는 잠금 전략 변경을 섞는다.

## 재현 명령

```powershell
docker version
.\\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.*" --rerun --no-daemon --stacktrace
$artifactPath = "docs\\measurements\\results\\room-785-b\\room-785-b.json"
$canonicalArtifact = (Get-Content -Path $artifactPath -Raw -Encoding UTF8) -replace "`r`n", "`n"
$artifactBytes = [Text.Encoding]::UTF8.GetBytes($canonicalArtifact)
[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($artifactBytes))
```
