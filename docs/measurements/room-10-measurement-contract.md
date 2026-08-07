# ROOM-10a 낙관 락 기준선 측정 계약

## 목적과 범위

이 문서는 현재 `RoomOptimisticLockRetrier`의 낙관 락 기준선을 재현하기 위한 fixture와 원자료 형식을 고정한다. 결과는 성능 합격선이나 운영 실측이 아니며, 비관적 락·조건부 갱신·연결 풀 처리량·대기 성능을 비교하거나 평가하지 않는다.

## 기준 환경

| 항목 | 고정값 또는 기록값 |
| --- | --- |
| 기준 SHA | 실행 시 `git rev-parse HEAD` 값 |
| 측정 호스트 | 로컬 PC 스냅샷 (2026-08-06) |
| 호스트 OS | Windows 11 Pro 64-bit, version `10.0.26200` (build `26200`) |
| CPU | Intel(R) Core(TM) Ultra 9 275HX, 24코어·24논리 프로세서 |
| 호스트 메모리 | 31.4 GiB |
| Java | Eclipse Temurin `21.0.11` (LTS) |
| Gradle | Gradle Wrapper `9.5.1` |
| Docker Desktop | `4.73.1.226574` |
| Docker Engine | `29.4.3`, Linux `amd64`, WSL2 kernel `6.6.114.1-microsoft-standard-WSL2`, 24 vCPU·15.3 GiB |
| PostgreSQL | Testcontainers `postgres:18.4` |
| PostgreSQL 설정 | `shared_preload_libraries=pg_stat_statements`, `pg_stat_statements` extension |
| 시간 | `Clock.fixed(2026-07-28T00:00:00Z, UTC)` |
| fixture seed | `ROOM-10A-20260806` |

위 호스트·Docker 값은 로컬 기준선 측정에 사용한 환경 스냅샷이다. 다른 PC나 CI 결과와 비교할 때는 해당 실행 환경도 같은 항목으로 별도 기록한다.

## fixture와 round

- 마지막 좌석 시나리오는 round마다 capacity 1의 새 hot ROOM 한 개, 방장 한 명, 요청 수만큼의 고유 사용자를 만든다.
- 동시 요청 수준은 `2`, `4`, `8`로 고정한다. 최대 수준 8은 기본 테스트 연결 풀 10보다 작다.
- 수준마다 준비 round는 1회, 측정 round는 3회 실행한다.
- 각 요청은 결정적 gate에서 같은 ROOM version을 읽은 뒤 함께 진행한다. 무작위 지연, jitter, 실제 scheduler 주기는 표본에 포함하지 않는다.
- fixture 생성·truncate·정리는 `measurement` 구간 시작 전에 끝내며 응답시간과 PostgreSQL 비용에 포함하지 않는다.

## 수집 방식과 산식

- 요청별 응답시간은 command 시작부터 `System.nanoTime()`의 종료까지 기록한다. 이는 monotonic time이며 결정적 gate가 추가한 barrier 대기시간만 별도 차감해 스케줄링 편차와 wall clock 변경의 영향을 표본에서 제외한다.
- 결과 분포는 `success`, `businessFailure`, `concurrencyFailure`, `technicalFailure` 네 범주로 기록한다. 네 값의 합은 요청 수와 같아야 한다.
- 요청별 재시도 bucket은 기준선 테스트가 주입한 `MeasuredRoomOptimisticLockRetrier`의 `beforeRetry` hook 호출 횟수에서 산출한다. hook은 실제 `RoomOptimisticLockRetrier` 실행 경계 안에서 다음 시도 직전에 호출되므로 `retryCount = beforeRetry 호출 횟수`이며, `0`·`1`·`2`회 bucket과 세 번째 시도 소진을 별도 기록한다.
- 실제 `RoomOptimisticLockRetrier`의 `room_participation_retry` 로그는 bucket 산출 근거와 분리한 형식 회귀 원자료로 수집한다. 로그의 `event`, 선택적 `roomId`, `attempt`를 파싱하고 DEBUG는 다음 시도, WARN은 세 번째 시도 소진으로 검증한다. 결정적 재시도 테스트는 `event`·`attempt` 순서와 로그 수준을, 마지막 좌석 측정은 `event`·`roomId`·`attempt` 형식을 assertion한다.
- 낙관 락 충돌 수는 재시도 조정자가 잡은 `OptimisticLockException` 또는 `ObjectOptimisticLockingFailureException` 수다. 충돌률은 `conflictCount / totalRequestCount`로 기록한다.
- 측정 round 직전에 `pg_stat_statements_reset()`을 실행한다. round 뒤 같은 database의 `pg_stat_statements`에서 `calls`, `total_exec_time`, `rows`, `shared_blks_hit`, `shared_blks_read` 합계를 읽는다. fixture SQL과 reset 전 statement는 이 범위에 없다.
- raw 결과는 테스트 로그에 한 줄 `ROOM10A_RAW` 레코드로 남긴다. 레코드는 결과 분포(`success`, `businessFailure`, `concurrencyFailure`, `technicalFailure`), 충돌(`conflictCount`, `conflictRate`), 재시도 bucket(`retry0`, `retry1`, `retry2`), 소진(`exhausted`), 요청별 단조 시간(`responseNanos`), PostgreSQL 비용(`calls`, `totalExecMs`, `rows`, `sharedBlksHit`, `sharedBlksRead`)을 같은 이름으로 남긴다.
- fixture를 만든 뒤 측정 직전에 통계를 초기화하므로 fixture SQL은 PostgreSQL 비용에 포함하지 않는다. 기준선 테스트가 주입한 측정용 retrier의 hook 계측과 raw logger의 형식 회귀 검증은 요청 결과와 별도로 다룬다.
- 수준 간 결과는 같은 필드명과 산식만 비교할 수 있으며, 수준 간 해석과 재시도 소진율 변곡점 판정은 ROOM-10c가 소유한다.

## PostgreSQL 검증 불변식

각 측정 round 뒤 다음을 실제 PostgreSQL에서 확인한다.

- `active_participant_count = ACTIVE participation 수`
- `0 <= active_participant_count <= capacity`
- 사용자별 ACTIVE participation은 최대 하나
- 마지막 좌석이 차면 `CLOSED`, 남으면 `RECRUITING`이며 종료 상태로 바뀌지 않는다.

## 원자료 보존

측정 round는 로그 출력만으로 남기지 않는다. 로그는 실행이 끝나면 사라져 후속 통합이 쓸 입력이 되지 못하므로, `room-09-bounded-processing-baseline.md`가 확립한 방식을 따른다.

1. 테스트가 `build/reports/measurements/room-10a.json`과 `room-10b.json`을 생성한다.
2. 같은 파일을 `results/room-10a/`와 `results/room-10b/`에 버전 관리로 보존한다.
3. `ROOM-10c-T6`은 `build/` 경로의 재생성 파일이 아니라 이 버전 관리 파일을 입력으로 사용한다.

각 JSON은 `reportName`, `environment`, `rounds`로 구성한다. `environment`에는 기준 SHA, Java·PostgreSQL 버전, OS와 버전, CPU 수, 시작 heap 사용량·최대 heap과 `configuration`을 남긴다. `configuration`은 PostgreSQL image, `shared_preload_libraries`, fixture seed, 고정 시각, 동시 요청 수준, 배경 스케줄러·알림 relay·채팅 만료 삭제 차단 여부 8개 키를 담는다. `rounds`에는 시나리오, 동시 요청 수준, 전체 요청 수, 성공·업무 실패·동시성 실패·기술 실패 수, 낙관 락 충돌 수와 충돌률, `0`·`1`·`2`회 재시도 수, 세 번째 시도 소진 수, 요청별 monotonic 응답시간, PostgreSQL 비용과 raw record를 남긴다.

| 파일 | 의미 | SHA-256 |
| --- | --- | --- |
| [`room-10a.json`](results/room-10a/room-10a.json) | 마지막 좌석 동시 참가, 수준 `2`·`4`·`8` 각 3 round | `E2007E34F3CB30E95078FD6A5BD52E59F83882436429348EFBC3B85BA4B30781` |
| [`room-10b.json`](results/room-10b/room-10b.json) | 대기·자동 승격 5개 경합 시나리오 21 round | `D60BFA4984D9105B604D1CD748C4DD07DAA445FAF7EDBA8837FE5A125A0F7529` |

SHA-256은 OS 줄바꿈 차이를 제거한 Git canonical blob bytes 기준이며, working tree의 CRLF 파일을 직접 해시한 값과 다를 수 있다.

보존한 두 파일은 `build -x jacocoTestCoverageVerification`·`conventionCheck`·`postgresTest`·`jacocoAllTestReport`·`jacocoAllTestCoverageVerification`을 함께 실행한 같은 세션에서 생성했다. 응답시간과 PostgreSQL 비용은 실행마다 달라지므로, 결과를 갱신할 때는 두 파일을 같은 세션에서 다시 만들고 위 SHA-256도 함께 고친다.

측정 중에는 `spring.task.scheduling.enabled=false`로 배경 스케줄러를 차단하고 알림 relay·채팅 만료 삭제도 함께 끈다. 배경 SQL이 `pg_stat_statements` 합계에 섞이면 요청 외 비용이 측정값에 포함된다.

## 재현 명령

Docker daemon 접근을 먼저 확인한다.

```powershell
docker version
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomParticipationConcurrencyBaselinePostgresTest.*" --rerun --fail-fast
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomWaitlistConcurrencyBaselinePostgresTest.*" --rerun --fail-fast
```

두 명령은 `build/reports/measurements/`에 JSON을 다시 만든다. 결과를 갱신하려면 그 파일을 위 `results/` 경로로 복사하고 표의 SHA-256을 다시 계산한다.

이 명령은 기준선 fixture를 실행할 뿐 운영 성능 수치나 잠금 전략 채택 결론을 만들지 않는다.
