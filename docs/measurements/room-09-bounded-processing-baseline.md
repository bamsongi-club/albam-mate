# ROOM-09c 현행 일괄 처리 기준선 측정

## 범위와 상태

이 문서는 #381이 제공한 현행 `RoomRepository.findDueRooms → RoomStatusCorrectionCoordinator.correctDueRooms → RoomStatusCorrectionExecutor.correctDueRooms` 전체 Entity·단일 트랜잭션 경로의 측정 방법과 실행 원자료를 설명한다. #382의 제한 ID 후보 선별·ROOM별 독립 처리는 비교 대상이며, 이 문서는 구현하거나 측정 결과를 대신하지 않는다.

- 소형 `100/20`은 기본 `postgresTest`에서 계약을 검증한다.
- 중형 `10,000/2,000`, 대형 `50,000/10,000`은 `issue383.measurement=true`일 때만 실행한다.
- 각 규모의 `20`·`2,000`·`10,000` due ROOM은 `RECRUITING`과 `CLOSED`를 반반으로 구성한다. non-due ROOM 중 10개는 `finishedThreshold` 직후의 non-due `CLOSED`로, 나머지는 미래 `RECRUITING`으로 구성하며 `WAITING` 관계는 만들지 않는다.
- 모든 profile은 고정 `requestTime` (`2026-08-06T00:00:00Z`)과 seed (`ROOM-09c-baseline-v1`)를 사용하고, warm-up 1회 뒤 실측 5회를 실행한다.
- 중형·대형의 명시 측정은 `JAVA_TOOL_OPTIONS`로 `issue383.measurement=true`를 테스트 JVM에 전달한다. `postgresTest`가 기본으로 전달하는 시스템 속성만으로는 이 별도 측정을 활성화하지 않는다.

## 2026-08-06 기준선 결과

세 profile 모두 측정 코드가 실행된 고정 SHA `4688316415113b4457f03628d77bdcb7f594c294`에서 실행했고, 결과 JSON의 `measurementStartEnvironment`와 각 run의 `runStartEnvironment`에 같은 SHA·환경 snapshot을 남겼다. 이후 원자료와 문서를 보존하는 커밋이 추가되었으므로 이 측정 코드 SHA가 최종 PR HEAD와 다른 것은 의도된 결과다. 아래 수치는 최종 fixture 보강을 포함한 현재 일괄 처리 경로의 2026-08-06 실행 결과이지 운영 합격선이나 SLO가 아니다.

실행 환경은 Java `21.0.11`, Docker Engine 버전은 각 결과 JSON의 `measurementStartEnvironment.configuration.dockerVersion`, PostgreSQL `18.4 (Debian 18.4-1.pgdg13+1)`, Windows 11, CPU 24개, PostgreSQL image `postgres:18.4`, `shared_preload_libraries=pg_stat_statements`였다. 측정 중 notification relay·chat retention은 비활성화하고 ROOM 상태 보정·notification cleanup의 첫 스케줄 실행은 24시간 뒤로 설정했으며, 이 설정도 각 결과 JSON의 configuration에 기록했다. 모든 profile은 `SUCCESS`, WAITING `0`, warm-up `1회`, 실측 `5회`, 후보 수와 변경 수가 due 수와 같았다.

| profile | fixture (전체/due/non-due) | 실측 실행시간 최솟값/중앙값/최댓값 (ms) | 처리량 최솟값/중앙값/최댓값 (ROOM/s) |
| --- | ---: | ---: | ---: |
| small | 100 / 20 / 80 | 50.8900 / 87.0308 / 106.1030 | 188.4961 / 229.8037 / 393.0045 |
| medium | 10,000 / 2,000 / 8,000 | 4,396.2845 / 4,715.5992 / 7,152.7056 | 279.6145 / 424.1243 / 454.9296 |
| large | 50,000 / 10,000 / 40,000 | 132,745.3430 / 137,464.4264 / 145,619.3626 | 68.6722 / 72.7461 / 75.3322 |

시간은 JSON의 `elapsedNanos / 1_000_000`, 처리량은 JSON의 `throughputPerSecond`를 그대로 표시했다. 성공 결과의 각 warm-up·실측 run에는 후보 수·변경 수·run 시작 환경·`pg_stat_statements` query별 호출 수·DB 실행시간·행 수·shared buffer hit/read가 남아 있으며, 표의 원자료는 아래 버전 관리 파일과 SHA-256으로 고정한다. SHA-256은 OS 줄바꿈 차이를 제거한 Git canonical blob bytes 기준이며, working tree의 CRLF 파일을 직접 해시한 값과 다를 수 있다.

## 측정 결과와 증거 파일

테스트는 먼저 `build/reports/measurements/`에 JSON을 생성하고, 이 결과 문서의 표에 사용한 동일 파일을 `results/room-09c/`에 보존했다. 따라서 `build/` 경로의 재생성 파일이 아니라 아래 버전 관리 파일을 결과의 증거로 사용한다.

| 파일 | 의미 | SHA-256 |
| --- | --- | --- |
| [`room-09c-small.json`](results/room-09c/room-09c-small.json) | small 성공 결과, 실측 5회 | `470CE093524CDCAA049F574308BC687DA5F4AA8129C98D1977BDFF2F7448CF3F` |
| [`room-09c-medium.json`](results/room-09c/room-09c-medium.json) | medium 성공 결과, 실측 5회 | `B5E1A3880E3C7A73B66C762C63A722D5B09E0BE7C8872273DDE833F730E86EB0` |
| [`room-09c-large.json`](results/room-09c/room-09c-large.json) | large 성공 결과, 실측 5회 | `3323DEEF404A171B8A15701BF89DA7A02D99EF5943CB446CF27BBC45FDAF1506` |
| [`room-09c-small-run-failure.json`](results/room-09c/room-09c-small-run-failure.json) | candidate-check 실패와 부분 실행 결과 | `BC81624AE3E3948E8BAEFFD9482E25F27ECFA51FBC0FFB97FDD4D00F186B1DE2` |
| [`room-09c-small-run-level-failure.json`](results/room-09c/room-09c-small-run-level-failure.json) | warm-up 일괄 트랜잭션 실패와 부분 실행 결과 | `4E281FCE05B2D72BCF6936A75102BDF2D7E9F7C925EB360FB09FBFDEA3E4F0AF` |
| [`room-09c-measurement-gate.json`](results/room-09c/room-09c-measurement-gate.json) | 기본·명시 profile과 재현 selector | `B82E749A7D0EEAEF8ED63C6AC8E5AAAF34D37EB48E335568BC3D1A8DA43A97D8` |

각 실행은 `build/reports/measurements/room-09c-{small|medium|large}.json`에 다음을 기록한다.

- 측정 전체 시작과 각 warm-up·실측·실패 run 직전의 Git SHA, Java·Docker·PostgreSQL·OS 버전, CPU 수, 시작 JVM heap 사용량·최대 heap, PostgreSQL image와 `shared_preload_libraries` 설정
- profile, 고정 시각, seed·data identifier, 총 ROOM·due/non-due/non-due CLOSED/WAITING ROOM 수
- warm-up 1회와 실측 5회의 후보 수, 변경 성공 수, 실행 시간과 `throughputPerSecond = changedCount * 1_000_000_000 / elapsedNanos` 산식의 run별 처리량
- `pg_stat_statements`의 정규화된 query text·query ID별 호출 수, PostgreSQL 실행 시간, 행 수, shared buffer hit/read 원자료
- 실측 5회의 실행시간과 처리량의 최소·중앙값·최댓값

각 profile의 fixture를 준비한 뒤 현행 Repository의 `findDueRooms`로 후보 수를 한 번 확인하고, `finishedThreshold` 직후의 non-due `CLOSED` ROOM이 후보에서 제외되는지 검증한다. 성공 warm-up·실측 run에서는 같은 고정 fixture를 다시 준비하고, run 시작 환경 snapshot을 남긴 뒤 `pg_stat_statements_reset()`을 호출하고 현행 `correctDueRooms` 경로를 시간 측정한다. 시간 측정 직후 reset 제어 쿼리를 제외한 `pgStatStatements`를 먼저 수집하고, 후보 조회가 1회·ROOM UPDATE 호출과 처리 행이 due 수와 같은지 확인한 뒤 사후 fixture 검증을 수행한다. 따라서 정상 run은 retry가 없는 단일 일괄 트랜잭션 기준선만 성공 결과로 남기며, retry나 상태 보정 SQL 누락은 RUN_FAILURE로 기록한다. 측정 중 notification relay·chat retention은 비활성화하고 ROOM 상태 보정·notification cleanup의 첫 스케줄 실행은 24시간 뒤로 설정하지만, 성공 run의 `pgStatStatements`에는 상태 보정 경로의 이벤트·부수 쿼리와 테스트 외 동시 활동이 포함될 수 있으며, 이는 `correctDueRooms`만을 분리한 profiler 결과가 아니다. 반대로 `candidate-check` 실패 원자료에는 사전 fixture 준비와 후보 조회가 포함될 수 있으며 성능 측정 결과로 해석하지 않는다.

`room-09c-{small|medium|large}-run-failure.json`은 `candidate-check` 단계의 `outcome: RUN_FAILURE`와 예외 유형·부분 원자료를 남긴다. `room-09c-{small|medium|large}-run-level-failure.json`은 `warm-up` 또는 `measured` 단계의 현행 일괄 트랜잭션 실패와 부분 원자료를 별도 보존한다. `partialRuns[].phase`가 `candidate-check`이면 `runFailure.category`는 `후보 수 사전 검증 실패`이며 `correctDueRooms`가 실행되지 않은 사전 검증 실패다. `warm-up` 또는 `measured`이면 `현행 일괄 트랜잭션 실패`로 분류한다. 실행이 시작되지 않은 실패의 `throughputPerSecond`는 `null`이고, 이는 ROOM별 실패가 아니므로 `roomFailures`를 비워 별도 의미를 보존한다. 현재 보존한 small 원자료는 candidate-check와 warm-up 일괄 트랜잭션 실패 각각 한 건이다.

`room-09c-measurement-gate.json`은 기본 profile과 명시적 profile, 대형 측정 재현 명령, 처리량 산식을 기록한다. 실패·gate 원자료도 위 증거 파일에 함께 보존하며, 표의 모든 수치는 위 성공 JSON의 `summary`에서 직접 옮겼다.

## 2026-08-07 제한 처리 후보 결과

`ROOM-09d-T1`에 따라 현행과 후보를 **같은 측정 세션 안에서** 실행했다. 한 조합마다 현행 경로와 후보 경로를 번갈아 warm-up 1회와 실측 5회씩 돌려, 한쪽만 특정 시간대의 호스트 부하를 받지 않게 했다. 현행은 #383이 기준선으로 고정한 <code>4688316415113b4457f03628d77bdcb7f594c294</code>의 전체 Entity 단일 트랜잭션 경로이고, 후보는 #382가 병합한 <code>8416d3254a3e9e2316bc14745959a2b42dab3c26</code>의 제한 ID 순회·ROOM별 독립 트랜잭션 경로다. 변화율은 같은 세션 현행 중앙값을 분모로 한 관찰값이며 성능 합격선이나 운영 실측 주장이 아니다.

| profile | 제한 ID | 현행 실행시간 중앙값 (ms) | 현행 처리량 중앙값 (ROOM/s) | 후보 실행시간 중앙값 (ms) | 후보 처리량 중앙값 (ROOM/s) | 시간 변화 | 처리량 변화 | 후보 DB 호출 수 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 10 | 61.0767 | 327.4571 | 233.3307 | 85.7153 | +282.03% | −73.82% | 149 |
| small | 20 | 53.0012 | 377.3499 | 243.7481 | 82.0519 | +359.89% | −78.26% | 146 |
| medium | 10 | 7,387.3736 | 270.7322 | 14,359.9942 | 139.2758 | +94.39% | −48.56% | 13,613 |
| medium | 100 | 11,660.9395 | 171.5128 | 23,994.8042 | 83.3514 | +105.77% | −51.40% | 13,073 |
| medium | 1000 | 8,111.6719 | 246.5583 | 15,797.3735 | 126.6033 | +94.75% | −48.65% | 13,019 |
| large | 10 | 284,453.8824 | 35.1551 | 145,841.8110 | 68.5674 | **−48.73%** | **+95.04%** | 68,013 |
| large | 100 | 153,662.1539 | 65.0778 | 67,659.9952 | 147.7978 | **−55.97%** | **+127.11%** | 65,317 |
| large | 1000 | 267,583.1773 | 37.3716 | 114,303.2526 | 87.4866 | **−57.28%** | **+134.10%** | 65,051 |

소형은 due ROOM이 20개다. 제한 ID `10`은 두 배치로 나누고 `20`은 한 배치로 전부 처리한다. `100`·`1000`은 `20`과 같은 단일 배치가 되어 별도로 측정하지 않았다.

관찰되는 형태는 세 가지다.

첫째, 규모에 따라 우열이 뒤집힌다. 소형과 중형에서는 현행이 빠르고 대형에서는 후보가 빠르다. 대형에서 후보는 실행시간을 `48.73%`에서 `57.28%`까지 줄이고 처리량을 `95.04%`에서 `134.10%`까지 늘린다. 교차 구간은 due `2,000`과 `10,000` 사이다.

둘째, 소형 열세의 원인은 배치 분할이 아니라 ROOM당 트랜잭션 비용이다. 배치를 `2`개에서 `1`개로 줄여 선별 쿼리를 없애도 후보는 `233.3307 ms`와 `243.7481 ms`로 사실상 같다. 같은 20개 ROOM을 처리하는 데 후보는 DB 호출을 `146~149`회 쓴다. ROOM마다 트랜잭션을 열고 최신 상태·버전을 다시 읽어 커밋하는 고정 비용이 남는 격차를 만든다.

셋째, 제한 ID 값 자체는 우열을 뒤집지 못한다. 같은 규모 안에서 `10`·`100`·`1000`의 시간 변화율이 중형은 `+94.39%`~`+105.77%`, 대형은 `−48.73%`~`−57.28%` 범위에 머문다.

### 대기열 포함 후보 결과

시작 경계를 지난 `CLOSED` due ROOM마다 `WAITING` 10명을 둔 별도 fixture의 후보 종료 비용이다. 현행 비교 없이 후보만 측정했고 fixture 구성이 달라 위 표에 넣지 않는다.

| profile | 제한 ID | ROOM당 WAITING | 실행시간 중앙값 (ms) | 처리량 중앙값 (ROOM/s) | DB 호출 수 |
| --- | ---: | ---: | ---: | ---: | ---: |
| small | 10 | 10 | 257.8804 | 77.5553 | 129 |

`WAITING` 총 200건을 종료하는데도 `WAITING` 없는 같은 fixture의 후보(`233.3307 ms`, `149`회)와 같은 수준이다. 이 규모에서는 시작 경계 대기열 종료가 유의미한 추가 비용을 만들지 않는다. 다만 두 값은 서로 다른 실행에서 얻었으므로 차이를 대기열 유무의 효과로 읽지 않는다.

### 보존 원자료

| 파일 | SHA-256 |
| --- | --- |
| [`room-09d-direct-comparison-small-limit-10.json`](results/room-09d/room-09d-direct-comparison-small-limit-10.json) | `342231913DA933C8E340A5F72BF9CF160C89A0296135B8F9283C0D8386C136B2` |
| [`room-09d-direct-comparison-small-limit-20.json`](results/room-09d/room-09d-direct-comparison-small-limit-20.json) | `395ABD48F61FB838C4D443173870F732C39D4D4BD49B8A2A91163927E2B3B2DD` |
| [`room-09d-direct-comparison-medium-limit-10.json`](results/room-09d/room-09d-direct-comparison-medium-limit-10.json) | `F175DCDE2A47A57EBBC138CC6D6E773E7838CEDE80D66C932A16DBFEFACA15CF` |
| [`room-09d-direct-comparison-medium-limit-100.json`](results/room-09d/room-09d-direct-comparison-medium-limit-100.json) | `A32695CA53FD0145DE5B4F7574930098B10CAAF8365861E010EADC2117FB016F` |
| [`room-09d-direct-comparison-medium-limit-1000.json`](results/room-09d/room-09d-direct-comparison-medium-limit-1000.json) | `C2B332FA7F28489FFD4B08BBD003F240F9A7744B4E05EFAEFC9CAD1377615719` |
| [`room-09d-direct-comparison-large-limit-10.json`](results/room-09d/room-09d-direct-comparison-large-limit-10.json) | `0FD96F64B830477D5FACCFB002423657697F0C281011F0DC232E383987321D02` |
| [`room-09d-direct-comparison-large-limit-100.json`](results/room-09d/room-09d-direct-comparison-large-limit-100.json) | `5D9FEF7C2D9CBF342FD93728A14CCD9340F1FA0B21CA49FA86950750ABF4B5C2` |
| [`room-09d-direct-comparison-large-limit-1000.json`](results/room-09d/room-09d-direct-comparison-large-limit-1000.json) | `48B8D4B45F5D71335A2F3F40F2D5D08BECD3ABDF0C24FE40CD65A1ED694E8869` |
| [`room-09d-candidate-small-limit-10.json`](results/room-09d/room-09d-candidate-small-limit-10.json) | `C4286E1C01E263ED0CE3835CCE58636E53F22F8C8B4001C0C099FBCBBFEAF1F2` |
| [`room-09d-waiting-queue-small-limit-10.json`](results/room-09d/room-09d-waiting-queue-small-limit-10.json) | `AB2D754130A8244E4B4B8EED3CA8141AF06DF7AE150BF80E12C4C1A80AD4C5F2` |

SHA-256 기준은 위 현행 원자료와 같다.

### 확정한 초기 운영값

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 제한 ID | `100` | 제한 ID가 우열을 뒤집지 못하므로 성능을 근거로 삼지 않는다. 한 번에 여는 처리 범위가 작아 실패 ROOM의 재처리 폭이 좁은 값을 택한다. |
| 실행시간 경고 | `180s` | 이 측정에서 관찰한 후보 최대 실행시간은 대형 `151,774 ms`다. 여기에 여유를 더한다. |
| `lockAtMostFor` | `10m` | 경고 기준과 고정 비율로 묶지 않는다. 잠금 보유 인스턴스가 죽었을 때 다른 인스턴스가 이어받기까지 감수할 복구 지연으로 정하며, `trigger-delay` `15m` 한 주기 안에 든다. |
| 실행 주기 | `15m`(jitter `3m`) | 기존 값을 유지한다. 이 측정이 조정 근거를 만들지 않았다. |

### 측정 한계와 재검토 조건

- 이 수치는 로컬 단일 인스턴스와 Testcontainers 환경의 fixture 결과이며 운영 실측이나 성능 합격선이 아니다. 배포 환경에서 다시 측정한 값과 직접 비교하지 않는다.
- 조합마다 현행과 후보는 같은 세션에서 얻었지만, 서로 다른 조합은 서로 다른 세션에서 측정했다. 같은 대형 현행이 `153,662 ms`와 `284,453 ms`로 벌어지는 것처럼 절대값은 호스트 부하에 크게 좌우된다. 따라서 조합 간 절대값을 직접 비교하지 않고 각 조합 안의 변화율만 읽는다.
- 이 측정은 정상 처리 경로만 다룬다. 고의 실패·재시도·순회 중 새 due ROOM 유입은 포함하지 않으며, 실패 격리는 `#382`의 `ROOM-09b-T4` 검증 결과를 재사용한다.
- 중형·대형 직접 비교 원자료 6개의 `measurementStartEnvironment.configuration.executionCommand`에는 후보 단독 측정 메서드가 적혀 있다. 보고서가 자기 측정 메서드를 기록하도록 고치기 전에 생성한 값이며, 소형 4개는 수정 뒤 다시 만들어 직접 비교 메서드를 가리킨다. 재현할 때는 원자료의 그 필드가 아니라 아래 `재현 명령`을 사용한다. 중형·대형을 다시 만들면 이 항목은 지운다.
- 소형·중형 열세의 원인을 ROOM당 트랜잭션 고정 비용으로 좁혔으나, 그 고정 비용의 내부 구성까지는 나누지 않았다. 확정하려면 트랜잭션 경계별 프로파일링이 따로 필요하다.
- 다음 조건 중 하나가 성립하면 제한 ID와 주기를 다시 측정한다. 운영 due ROOM 수가 이 fixture의 대형 규모(`10,000`)를 넘어설 때, 한 순회의 실행시간이 실행시간 경고 기준에 근접할 때, 또는 ROOM당 `WAITING` 수가 이 측정의 `10`명을 크게 넘어설 때다.

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

소형 동일 세션 비교와 후보 계약, 대기열 포함 후보:

```powershell
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionCandidateMeasurementPostgresTest" --rerun --fail-fast
```

중형·대형 승인 규모의 현행·후보 동일 세션 비교:

```powershell
$hadJavaToolOptions = Test-Path Env:JAVA_TOOL_OPTIONS
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        '-Dissue390.measurement=true'
    } else {
        "$previousJavaToolOptions -Dissue390.measurement=true".Trim()
    }
    .\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionCandidateMeasurementPostgresTest.승인_규모는_현행과_후보를_같은_세션에서_제한_ID별로_비교한다" --rerun --fail-fast
} finally {
    if ($hadJavaToolOptions) {
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    } else {
        Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    }
}
```

두 명령은 `build/reports/measurements/`에 JSON을 다시 만든다. 제한 ID 일부만 다시 재려면 `issue390.candidateLimits`에 쉼표로 값을 준다. 결과를 갱신할 때는 같은 파일을 `results/room-09d/`로 복사하고 위 표의 SHA-256을 다시 계산한다.

후속 #390은 이 기준선 원자료를 비교 근거로 사용하며, 결과만으로 제한 ID 수·반복·재시도·주기나 조건부 직접 갱신을 확정하지 않는다. 초기 운영값은 위 후보 결과를 제시한 뒤 사용자 승인으로 확정한다.
