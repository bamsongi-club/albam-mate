# ROOM-09c 현행 일괄 처리 기준선 측정

## 범위와 상태

이 문서는 #381이 제공한 현행 `RoomRepository.findDueRooms → RoomStatusCorrectionCoordinator.correctDueRooms → RoomStatusCorrectionExecutor.correctDueRooms` 전체 Entity·단일 트랜잭션 경로의 측정 방법과 실행 원자료를 설명한다. #382의 제한 ID 후보 선별·ROOM별 독립 처리는 비교 대상이며, 이 문서는 구현하거나 측정 결과를 대신하지 않는다.

- 소형 `100/20`은 기본 `postgresTest`에서 계약을 검증한다.
- 중형 `10,000/2,000`, 대형 `50,000/10,000`은 `issue383.measurement=true`일 때만 실행한다.
- 각 규모의 `20`·`2,000`·`10,000` due ROOM은 `RECRUITING`과 `CLOSED`를 반반으로 구성한다. non-due ROOM 중 10개는 `finishedThreshold` 직후의 non-due `CLOSED`로, 나머지는 미래 `RECRUITING`으로 구성하며 `WAITING` 관계는 만들지 않는다.
- 모든 profile은 고정 `requestTime` (`2026-08-06T00:00:00Z`)과 seed (`ROOM-09c-baseline-v1`)를 사용하고, warm-up 1회 뒤 실측 5회를 실행한다.
- 중형·대형의 명시 측정은 `JAVA_TOOL_OPTIONS`로 `issue383.measurement=true`를 테스트 JVM에 전달한다. `postgresTest`가 기본으로 전달하는 시스템 속성만으로는 이 별도 측정을 활성화하지 않는다.

## 2026-08-06 기준선 결과

세 profile 모두 고정 실행 SHA `15ef30221558a454645c9a7080cc92fce3fece97`에서 실행했고, 결과 JSON의 `measurementStartEnvironment`와 각 run의 `runStartEnvironment`에 같은 SHA·환경 snapshot을 남겼다. 아래 수치는 최종 fixture 보강을 포함한 현재 일괄 처리 경로의 2026-08-06 실행 결과이지 운영 합격선이나 SLO가 아니다.

실행 환경은 Java `21.0.11`, Docker Engine 버전은 각 결과 JSON의 `measurementStartEnvironment.configuration.dockerVersion`, PostgreSQL `18.4 (Debian 18.4-1.pgdg13+1)`, Windows 11, CPU 24개, PostgreSQL image `postgres:18.4`, `shared_preload_libraries=pg_stat_statements`였다. 모든 profile은 `SUCCESS`, WAITING `0`, warm-up `1회`, 실측 `5회`, 후보 수와 변경 수가 due 수와 같았다.

| profile | fixture (전체/due/non-due) | 실측 실행시간 최솟값/중앙값/최댓값 (ms) | 처리량 최솟값/중앙값/최댓값 (ROOM/s) |
| --- | ---: | ---: | ---: |
| small | 100 / 20 / 80 | 49.5015 / 56.6138 / 68.8686 | 290.4081 / 353.2708 / 404.0282 |
| medium | 10,000 / 2,000 / 8,000 | 6,369.3210 / 6,948.1471 / 7,405.4067 | 270.0729 / 287.8465 / 314.0052 |
| large | 50,000 / 10,000 / 40,000 | 60,924.5092 / 67,613.3752 / 117,814.5419 | 84.8792 / 147.8997 / 164.1376 |

시간은 JSON의 `elapsedNanos / 1_000_000`, 처리량은 JSON의 `throughputPerSecond`를 그대로 표시했다. 성공 결과의 각 warm-up·실측 run에는 후보 수·변경 수·run 시작 환경·`pg_stat_statements` query별 호출 수·DB 실행시간·행 수·shared buffer hit/read가 남아 있으며, 표의 원자료는 아래 버전 관리 파일과 SHA-256으로 고정한다.

## 측정 결과와 증거 파일

테스트는 먼저 `build/reports/measurements/`에 JSON을 생성하고, 이 결과 문서의 표에 사용한 동일 파일을 `results/room-09c/`에 보존했다. 따라서 `build/` 경로의 재생성 파일이 아니라 아래 버전 관리 파일을 결과의 증거로 사용한다.

| 파일 | 의미 | SHA-256 |
| --- | --- | --- |
| [`room-09c-small.json`](results/room-09c/room-09c-small.json) | small 성공 결과, 실측 5회 | `D218957B99C7FC460C827FA8BC60F72FD60338ABF338641B3387DBBECCB6B68B` |
| [`room-09c-medium.json`](results/room-09c/room-09c-medium.json) | medium 성공 결과, 실측 5회 | `86E66FC90F77834E48DC934E7825797E846228FBEA880B0D7BCBEF35ED260BC7` |
| [`room-09c-large.json`](results/room-09c/room-09c-large.json) | large 성공 결과, 실측 5회 | `82B8997468F19CD1CFD02D991367BFCFAF6AA3FE1FD9F9900689FDAB4A1FD02F` |
| [`room-09c-small-run-failure.json`](results/room-09c/room-09c-small-run-failure.json) | 후보 수 사전 검증 실패와 부분 실행 결과 | `54F7A6AAA289708B7E8FF628D9C7A959EAB945061D567AF0798A842500106B5C` |
| [`room-09c-measurement-gate.json`](results/room-09c/room-09c-measurement-gate.json) | 기본·명시 profile과 재현 selector | `DD67D15E859FB25EE48B3B4AA84BED975919E6AC27477C6DF5E893B7D7C36D07` |

각 실행은 `build/reports/measurements/room-09c-{small|medium|large}.json`에 다음을 기록한다.

- 측정 전체 시작과 각 warm-up·실측·실패 run 직전의 Git SHA, Java·Docker·PostgreSQL·OS 버전, CPU 수, 시작 JVM heap 사용량·최대 heap, PostgreSQL image와 `shared_preload_libraries` 설정
- profile, 고정 시각, seed·data identifier, 총 ROOM·due/non-due/non-due CLOSED/WAITING ROOM 수
- warm-up 1회와 실측 5회의 후보 수, 변경 성공 수, 실행 시간과 `throughputPerSecond = changedCount * 1_000_000_000 / elapsedNanos` 산식의 run별 처리량
- `pg_stat_statements`의 정규화된 query text·query ID별 호출 수, PostgreSQL 실행 시간, 행 수, shared buffer hit/read 원자료
- 실측 5회의 실행시간과 처리량의 최소·중앙값·최댓값

각 profile의 fixture를 준비한 뒤 현행 Repository의 `findDueRooms`로 후보 수를 한 번 확인하고, `finishedThreshold` 직후의 non-due `CLOSED` ROOM이 후보에서 제외되는지 검증한다. 성공 warm-up·실측 run에서는 같은 고정 fixture를 다시 준비하고, run 시작 환경 snapshot을 남긴 뒤 `pg_stat_statements_reset()`을 호출하고 현행 `correctDueRooms` 경로를 시간 측정한다. 시간 측정 직후 reset 제어 쿼리를 제외한 `pgStatStatements`를 먼저 수집하고, 후보 조회가 1회·ROOM UPDATE 호출과 처리 행이 due 수와 같은지 확인한 뒤 사후 fixture 검증을 수행한다. 따라서 정상 run은 retry가 없는 단일 일괄 트랜잭션 기준선만 성공 결과로 남기며, retry나 상태 보정 SQL 누락은 RUN_FAILURE로 기록한다. 성공 run의 `pgStatStatements`에는 상태 보정 경로의 이벤트·부수 쿼리와 외부 동시 활동이 포함될 수 있으며, 이는 `correctDueRooms`만을 분리한 profiler 결과가 아니다. 반대로 `candidate-check` 실패 원자료에는 사전 fixture 준비와 후보 조회가 포함될 수 있으며 성능 측정 결과로 해석하지 않는다.

`room-09c-{small|medium|large}-run-failure.json`은 `outcome: RUN_FAILURE`와 예외 유형·이미 완료된 run·실패 run의 부분 원자료를 남긴다. `partialRuns[].phase`가 `candidate-check`이면 `runFailure.category`는 `후보 수 사전 검증 실패`이며 `correctDueRooms`가 실행되지 않은 사전 검증 실패다. `warm-up` 또는 `measured`이면 `현행 일괄 트랜잭션 실패`로 분류한다. 실행이 시작되지 않은 실패의 `throughputPerSecond`는 `null`이고, 이는 ROOM별 실패가 아니므로 `roomFailures`를 비워 별도 의미를 보존한다. 현재 보존한 small failure 원자료는 `candidate-check` 사례다.

`room-09c-measurement-gate.json`은 기본 profile과 명시적 profile, 대형 측정 재현 명령, 처리량 산식을 기록한다. 실패·gate 원자료도 위 증거 파일에 함께 보존하며, 표의 모든 수치는 위 성공 JSON의 `summary`에서 직접 옮겼다.

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
$hadJavaToolOptions = Test-Path Env:JAVA_TOOL_OPTIONS
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        '-Dissue383.measurement=true'
    } else {
        "$previousJavaToolOptions -Dissue383.measurement=true".Trim()
    }
    .\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionBaselineMeasurementPostgresTest.승인_규모_기준선을_측정한다" --rerun --fail-fast
} finally {
    if ($hadJavaToolOptions) {
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    } else {
        Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    }
}
```

후속 #390은 이 기준선 원자료를 비교 근거로 사용하며, 결과만으로 제한 ID 수·반복·재시도·주기나 조건부 직접 갱신을 확정하지 않는다.
