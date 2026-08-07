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

`#382`가 병합한 제한 ID 순회·ROOM별 독립 트랜잭션 경로를 위 현행 기준선과 같은 fixture 규모로 측정했다. 후보 구현 SHA는 <code>8416d3254a3e9e2316bc14745959a2b42dab3c26</code>이며, 각 조합은 warm-up 1회 뒤 실측 5회다. `현행 대비`는 위 표의 중앙값을 분모로 한 관찰값이며 성능 합격선이나 운영 실측 주장이 아니다.

| profile | 제한 ID | 실행시간 최솟값/중앙값/최댓값 (ms) | 처리량 최솟값/중앙값/최댓값 (ROOM/s) | DB 실행시간 중앙값 (ms) | DB 호출 수 | 현행 대비 시간 | 현행 대비 처리량 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 10 | 445.0598 / 515.3794 / 553.3561 | 36.1431 / 38.8064 / 44.9378 | 5.8322 | 149 | 5.92배 | 0.17배 |
| medium | 10 | 25,096.8432 / 26,218.7676 / 29,004.4560 | 68.9549 / 76.2812 / 79.6913 | 604.0914 | 13,613 | 5.56배 | 0.18배 |
| medium | 100 | 24,291.6746 / 24,519.2801 / 25,286.6450 | 79.0931 / 81.5685 / 82.3327 | 530.2685 | 13,073 | 5.20배 | 0.19배 |
| medium | 1000 | 24,588.3628 / 24,788.5435 / 25,191.4682 | 79.3920 / 80.6824 / 81.3393 | 536.7008 | 13,019 | 5.26배 | 0.19배 |
| large | 10 | 120,823.8925 / 124,398.1296 / 125,079.8160 | 79.9490 / 80.3871 / 82.7651 | 4,140.7160 | 68,013 | 0.90배 | 1.11배 |
| large | 100 | 116,284.5130 / 121,515.0649 / 124,144.2670 | 80.5514 / 82.2943 / 85.9960 | 2,928.7800 | 65,321 | 0.88배 | 1.13배 |
| large | 1000 | 117,796.0377 / 119,569.4081 / 125,636.7408 | 79.5946 / 83.6334 / 84.8925 | 2,791.9547 | 65,043 | 0.87배 | 1.15배 |

`small`은 due ROOM이 20개다. 위 표에는 배치를 두 번으로 나누는 제한 ID `10`만 넣었고, 한 배치로 전부 처리하는 `20`은 아래 같은 세션 직접 비교에 있다. `100`·`1000`은 `20`과 같은 단일 배치가 되어 별도로 측정하지 않았다.

관찰되는 형태는 두 가지다.

첫째, 규모에 따른 처리량 곡선이 서로 다르다. 현행은 `229.8 → 424.1 → 72.7 ROOM/s`로 중형에서 정점을 찍고 대형에서 내려앉는다. 후보는 `38.8 → 81.6 → 83.6 ROOM/s`로 오른 뒤 평탄하다. 두 곡선이 교차하는 구간은 due `2,000`과 `10,000` 사이이며, 이 fixture에서는 대형에서만 후보가 앞선다.

둘째, 후보는 ROOM마다 독립 트랜잭션을 열어 ROOM당 고정 비용을 낸다. 대형 제한 `1000` 기준 due ROOM `10,000`개에 DB 호출이 `65,043`회로 ROOM당 약 `6.5`회다.

제한 ID 값 자체의 영향은 작다. 중형은 `100`, 대형은 `1000`이 가장 빨랐으나 같은 규모 안에서 최댓값과 최솟값의 차이가 중형 `6.5%`, 대형 `3.9%`에 그친다.

### 같은 세션 직접 비교와 열세 원인의 분리

위 대비 표는 서로 다른 세션에서 얻은 현행 기준선을 분모로 쓴다. 소형은 현행과 후보를 한 실행 안에서 나란히 측정한 결과가 따로 있으며, 세션 차이를 배제한 이 값이 더 강한 근거다.

| 제한 ID | 배치 구성 | 현행 중앙값 | 후보 중앙값 | 시간 변화 | 처리량 변화 | DB 호출 (현행 → 후보) |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 10 | 20개를 2배치 | 83.9264 ms · 238.3040 ROOM/s | 365.1496 ms · 54.7721 ROOM/s | +335.08% | −77.02% | 53 → 150 |
| 20 | 20개를 1배치 | 83.9776 ms · 238.1587 ROOM/s | 346.6241 ms · 57.6994 ROOM/s | +312.76% | −75.77% | 53 → 147 |

이 두 행이 소형 열세의 원인을 가른다. 배치를 `2`개에서 `1`개로 줄여 선별 쿼리를 없애도 후보는 `365.1496 ms`에서 `346.6241 ms`로 약 `5%`만 줄어든다. 남은 약 네 배의 격차는 배치 분할이 아니라 ROOM마다 트랜잭션을 열고 최신 상태·버전을 다시 읽어 커밋하는 고정 비용에서 온다. 같은 20개 ROOM에 DB 호출이 `53`회에서 `147~150`회로 약 `2.8`배 늘어나는 것도 같은 방향이다.

따라서 소형·중형에서 후보가 느린 주 원인은 ROOM당 트랜잭션 고정 비용이며, 제한 ID를 키워 배치 수를 줄이는 것으로는 해소되지 않는다. 다만 그 고정 비용의 내부 구성까지 이 측정으로 나누지는 않았다.

### 대기열 포함 후보 결과

시작 경계를 지난 `CLOSED` due ROOM마다 `WAITING` 10명을 둔 별도 fixture의 후보 종료 비용이다. fixture 구성이 달라 위 현행 대비 표에 넣지 않는다.

| 원자료 | 제한 ID | 배치 구성 | 실행시간 중앙값 (ms) | 처리량 중앙값 (ROOM/s) | DB 호출 수 |
| --- | ---: | --- | ---: | ---: | ---: |
| `room-09d-waiting-queue-small.json` | 10 | 20개를 2배치 | 334.4800 | 59.8000 | 130 |
| `room-09d-waiting-queue-small.json` | 20 | 20개를 1배치 | 325.9800 | 61.4000 | 127 |
| `room-09d-waiting-queue-small-limit-10.json` | 10 | 20개를 2배치 | 449.3047 | 44.5132 | 129 |

같은 시나리오를 서로 다른 실행에서 두 번 남겼고 중앙값이 `334.4800 ms`와 `449.3047 ms`로 벌어진다. 소형 fixture는 전체 시간이 0.5초 이하라 호스트 부하가 그대로 드러나므로, 이 두 값의 차이를 대기열 유무나 제한 ID의 효과로 읽지 않는다.

ROOM당 `WAITING` 10명, 총 200건을 종료하는데도 실행시간과 DB 호출이 `WAITING` 없는 같은 fixture(`365.1496 ms`, `150`회)를 넘지 않는다. 이 규모에서는 시작 경계 대기열 종료가 유의미한 추가 비용을 만들지 않는다.

### 후보 보존 원자료

| 파일 | SHA-256 |
| --- | --- |
| [`room-09d-candidate-small-limit-10.json`](results/room-09d/room-09d-candidate-small-limit-10.json) | `D2A5B924462595EC580BCC58962B7B0CB1FB677C8ED0F2545F8B62B1EE6AE7DC` |
| [`room-09d-candidate-medium-limit-10.json`](results/room-09d/room-09d-candidate-medium-limit-10.json) | `DCECD85ADA03682FAEC7AD13CD5115A937EDEC9C9138684B756C4716E79E882E` |
| [`room-09d-candidate-medium-limit-100.json`](results/room-09d/room-09d-candidate-medium-limit-100.json) | `FB0B665720EFEDB58548BF0B3B1C1C52137BC4146A2E0F324B75F55040514C4D` |
| [`room-09d-candidate-medium-limit-1000.json`](results/room-09d/room-09d-candidate-medium-limit-1000.json) | `0E50E36E61AE884CDB695302E55B7960E20CEBA856D22AB4A521AAB4D7F87C5C` |
| [`room-09d-candidate-large-limit-10.json`](results/room-09d/room-09d-candidate-large-limit-10.json) | `41523F1D8636315851898610849B18312E25C3A728CA32D80B426B0325A9710A` |
| [`room-09d-candidate-large-limit-100.json`](results/room-09d/room-09d-candidate-large-limit-100.json) | `16B543276E081206E3FF85CF50CB72ABF28B098ECD9EAB9AE6F8E115F24CEFD8` |
| [`room-09d-candidate-large-limit-1000.json`](results/room-09d/room-09d-candidate-large-limit-1000.json) | `4884AADAB2F649D4ECD890B3F4381E49CD4F707C6F45F0A39CE67F3B1E13F858` |
| [`room-09d-waiting-queue-small-limit-10.json`](results/room-09d/room-09d-waiting-queue-small-limit-10.json) | `8EFF310070CDED746ECDBAAE732DD8E28962B32879905A71C7A7755D4D443F95` |
| [`room-09d-waiting-queue-small.json`](results/room-09d/room-09d-waiting-queue-small.json) | `AE5A1DB83A0BE0BA5640307E5680D6303E9D5220B5D042A47BBBD24053BD8369` |
| [`room-09d-direct-comparison-small.json`](results/room-09d/room-09d-direct-comparison-small.json) | `FD3EFC5814D03F66CE67837117503F849FA8E5CE2D4D0CC246B68217DEEC49BB` |

SHA-256 기준은 위 현행 원자료와 같다.

### 측정 한계와 재검토 조건

- 이 수치는 로컬 단일 인스턴스와 Testcontainers 환경의 fixture 결과이며 운영 실측이나 성능 합격선이 아니다. 배포 환경에서 다시 측정한 값과 직접 비교하지 않는다.
- 현행과 후보는 같은 규모의 fixture를 쓰지만 서로 다른 세션에서 측정했다. 절대 시간은 호스트 부하에 따라 달라지므로 `현행 대비` 배수도 그 범위에서 읽는다.
- 이 측정은 정상 처리 경로만 다룬다. 고의 실패·재시도·순회 중 새 due ROOM 유입은 포함하지 않으며, 실패 격리는 `#382`의 `ROOM-09b-T4` 검증 결과를 재사용한다.
- 소형·중형에서 후보가 느린 원인을 ROOM당 트랜잭션 고정 비용으로 단정하지 않는다. 확정하려면 트랜잭션 경계별 프로파일링이 따로 필요하다.
- 다음 조건 중 하나가 성립하면 제한 ID와 주기를 다시 측정한다. 운영 due ROOM 수가 이 fixture의 대형 규모(`10,000`)를 넘어설 때, 한 순회의 실행시간이 `lockAtMostFor`에 근접할 때, 또는 ROOM당 `WAITING` 수가 이 측정의 `10`명을 크게 넘어설 때다.

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

소형 후보 계약과 대기열 포함 후보:

```powershell
.\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionCandidateMeasurementPostgresTest" --rerun --fail-fast
```

중형·대형 승인 규모 후보:

```powershell
$hadJavaToolOptions = Test-Path Env:JAVA_TOOL_OPTIONS
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        '-Dissue390.measurement=true'
    } else {
        "$previousJavaToolOptions -Dissue390.measurement=true".Trim()
    }
    .\gradlew.bat postgresTest --tests "cloud.bamsongi.albammate.room.measurement.RoomStatusCorrectionCandidateMeasurementPostgresTest.승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다" --rerun --fail-fast
} finally {
    if ($hadJavaToolOptions) {
        $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
    } else {
        Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
    }
}
```

두 명령은 `build/reports/measurements/`에 JSON을 다시 만든다. 결과를 갱신할 때는 같은 파일을 `results/room-09d/`로 복사하고 위 표의 SHA-256을 다시 계산한다.

후속 #390은 이 기준선 원자료를 비교 근거로 사용하며, 결과만으로 제한 ID 수·반복·재시도·주기나 조건부 직접 갱신을 확정하지 않는다. 초기 운영값은 위 후보 결과를 제시한 뒤 사용자 승인으로 확정한다.
