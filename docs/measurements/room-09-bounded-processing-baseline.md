# ROOM-09c 현행 일괄 처리 기준선 측정

## 범위와 상태

이 문서는 #381이 제공한 현행 `RoomRepository.findDueRooms → RoomStatusCorrectionCoordinator.correctDueRooms → RoomStatusCorrectionExecutor.correctDueRooms` 전체 Entity·단일 트랜잭션 경로의 측정 방법과 실행 원자료를 설명한다. #382의 제한 ID 후보 선별·ROOM별 독립 처리는 비교 대상이며, 이 문서는 구현하거나 측정 결과를 대신하지 않는다.

- 소형 `100/20`은 기본 `postgresTest`에서 계약을 검증한다.
- 중형 `10,000/2,000`, 대형 `50,000/10,000`은 `issue383.measurement=true`일 때만 실행한다.
- 각 규모의 `20`·`2,000`·`10,000` due ROOM은 `RECRUITING`과 `CLOSED`를 반반으로 구성한다. non-due ROOM 중 10개는 `finishedThreshold` 직후의 non-due `CLOSED`로, 나머지는 미래 `RECRUITING`으로 구성하며 `WAITING` 관계는 만들지 않는다.
- 모든 profile은 고정 `requestTime` (`2026-08-06T00:00:00Z`)과 seed (`ROOM-09c-baseline-v1`)를 사용하고, warm-up 1회 뒤 실측 5회를 실행한다.
- 중형·대형의 명시 측정은 `JAVA_TOOL_OPTIONS`로 `issue383.measurement=true`를 테스트 JVM에 전달한다. `postgresTest`가 기본으로 전달하는 시스템 속성만으로는 이 별도 측정을 활성화하지 않는다.

## 2026-08-06 기준선 결과

세 profile 모두 같은 worktree의 `b7d03ede2725adaaecda5c45471e0580b828c9c5` 기준에서 실행했고, 결과 JSON의 `measurementStartEnvironment`와 각 run의 `runStartEnvironment`에 같은 SHA·환경 snapshot을 남겼다. 실행 시점은 #452 리뷰 수정 내용을 아직 커밋하지 않은 worktree였으며, 아래 수치는 현재 일괄 처리 경로의 실제 실행 결과이지 운영 합격선이나 SLO가 아니다.

실행 환경은 Java `21.0.11`, PostgreSQL `18.4 (Debian 18.4-1.pgdg13+1)`, Windows 11, CPU 24개, PostgreSQL image `postgres:18.4`, `shared_preload_libraries=pg_stat_statements`였다. 모든 profile은 `SUCCESS`, WAITING `0`, warm-up `1회`, 실측 `5회`, 후보 수와 변경 수가 due 수와 같았다.

| profile | fixture (전체/due/non-due) | 실측 실행시간 최솟값/중앙값/최댓값 (ms) | 처리량 최솟값/중앙값/최댓값 (ROOM/s) |
| --- | ---: | ---: | ---: |
| small | 100 / 20 / 80 | 41.9282 / 47.8721 / 58.9673 | 339.1710 / 417.7799 / 477.0059 |
| medium | 10,000 / 2,000 / 8,000 | 2,988.3163 / 3,256.8427 / 3,607.0838 | 554.4645 / 614.0917 / 669.2732 |
| large | 50,000 / 10,000 / 40,000 | 46,362.5152 / 113,890.1843 / 137,096.6389 | 72.9412 / 87.8039 / 215.6915 |

시간은 JSON의 `elapsedNanos / 1_000_000`, 처리량은 JSON의 `throughputPerSecond`를 그대로 표시했다. 각 실측 run에는 후보 수·변경 수·run 시작 환경·`pg_stat_statements` query별 호출 수·DB 실행시간·행 수·shared buffer hit/read가 남아 있으며, profile별 전체 원자료는 아래 JSON 경로에서 확인한다.

## 수집 원자료

각 실행은 `build/reports/measurements/room-09c-{small|medium|large}.json`에 다음을 기록한다.

- 측정 전체 시작과 각 warm-up·실측·실패 run 직전의 Git SHA, Java·PostgreSQL·OS 버전, CPU 수, 시작 JVM heap 사용량·최대 heap, PostgreSQL image와 `shared_preload_libraries` 설정
- profile, 고정 시각, seed·data identifier, 총 ROOM·due/non-due/non-due CLOSED/WAITING ROOM 수
- warm-up 1회와 실측 5회의 후보 수, 변경 성공 수, 실행 시간과 `throughputPerSecond = changedCount * 1_000_000_000 / elapsedNanos` 산식의 run별 처리량
- `pg_stat_statements`의 정규화된 query text·query ID별 호출 수, PostgreSQL 실행 시간, 행 수, shared buffer hit/read 원자료
- 실측 5회의 실행시간과 처리량의 최소·중앙값·최댓값

각 profile의 fixture를 준비한 뒤 현행 Repository의 `findDueRooms`로 후보 수를 한 번 확인하고, `finishedThreshold` 직후의 non-due `CLOSED` ROOM이 후보에서 제외되는지 검증한다. 각 run에서는 같은 고정 fixture를 다시 준비하고, 실행 결과에서도 해당 ROOM이 `CLOSED`로 남는지 확인한다. run 시작 환경 snapshot을 남긴 뒤 `pg_stat_statements_reset()`을 호출하고 즉시 현행 `correctDueRooms` 경로를 시간 측정하므로 각 run의 `pgStatStatements`는 fixture·후보 확인·환경 조회가 아닌 측정 경로의 원자료다.

현행 일괄 트랜잭션이 실패하면 `room-09c-{small|medium|large}-run-failure.json`은 `outcome: RUN_FAILURE`와 예외 유형·이미 완료된 run·실패 run의 부분 원자료를 남긴다. 변경 성공 수가 없으므로 실패 run의 `throughputPerSecond`는 `null`이다. 이는 ROOM별 실패가 아니며 `roomFailures`를 비워 별도 의미를 보존한다.

`room-09c-measurement-gate.json`은 기본 profile과 명시적 profile, 대형 측정 재현 명령, 처리량 산식을 기록한다. 환경·실행·DB 원자료 JSON들은 버전 관리 문서에 성능 수치로 복사하지 않는다.

## PostgreSQL 관측 경계

측정 클래스 전용 Testcontainers만 `postgres -c shared_preload_libraries=pg_stat_statements`로 기동하고 `create extension if not exists pg_stat_statements`를 수행한다. 따라서 일반 `postgresTest`와 CI 기본 컨테이너 설정은 변경하지 않는다.

## 재현 명령

Docker daemon 접근을 먼저 확인한다.

```powershell
docker version
```

소형 계약·기준선 측정:

```powershell
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionBaselineMeasurementPostgresTest.작은_fixture는_현행_전체_Entity_단일_트랜잭션_경로의_입력과_결과를_기록한다" --rerun --fail-fast
```

중형·대형 승인 규모 기준선:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dissue383.measurement=true'
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionBaselineMeasurementPostgresTest.승인_규모_기준선을_측정한다" --rerun --fail-fast
Remove-Item Env:JAVA_TOOL_OPTIONS
```

후속 #390은 이 기준선 원자료를 비교 근거로 사용하며, 결과만으로 제한 ID 수·반복·재시도·주기나 조건부 직접 갱신을 확정하지 않는다.
