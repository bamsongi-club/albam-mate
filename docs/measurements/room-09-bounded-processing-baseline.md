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

`ROOM-09d-T1`에 따라 현행과 후보를 **같은 측정 세션 안에서** 실행했다. 한 조합마다 현행 경로와 후보 경로를 번갈아 warm-up 1회와 실측 5회씩 돌려, 한쪽만 특정 시간대의 호스트 부하를 받지 않게 했다.

측정 대상은 **트랜잭션 범위**의 차이다. 두 경로는 같은 커밋에서 같은 fixture를 처리하며, 현행은 due ROOM 전체를 하나의 트랜잭션에서 순회하고(`coordinator.correctDueRooms`, 제한 ID 미설정) 후보는 제한 ID로 나눠 ROOM마다 독립 트랜잭션을 연다(`scheduler.correctDueRooms` → `correctBoundedDueRooms`). 시작 경계 대기열 종료는 두 경로 모두에서 due ROOM마다 실행되므로 양쪽 공통 비용이다.

다만 두 경로의 **외곽 경계가 같지 않다.** 후보는 스케줄러 진입점에서 재기 때문에 ShedLock 획득·해제와 `progressStore.claimExecution`의 `SELECT … FOR UPDATE`·`UPDATE`가 측정 구간에 들어가고, 현행은 coordinator를 직접 호출해 이 비용이 없다. 코드 경로에서 세면 run당 약 `4`개의 추가 DB 문이고, 후보 DB 호출 수 대비 소형은 `149`회 중 약 `2.7%`, 중형 `13,013`~`13,613`회와 대형 `65,044`~`68,014`회에서는 `0.1%` 미만이다. 따라서 아래 표의 차이를 트랜잭션 범위 하나만의 효과로 읽지 않는다. 이 몫은 코드에서 센 값이며 별도 측정으로 분리하지 않았다.

원자료의 `baselineSourceSha`(<code>4688316415113b4457f03628d77bdcb7f594c294</code>)와 `candidateSourceSha`(<code>8416d3254a3e9e2316bc14745959a2b42dab3c26</code>)는 각 처리 전략을 도입한 **유래 커밋**이며 실행된 커밋이 아니다. 실행 커밋은 같은 파일의 `measurementStartEnvironment.gitSha`에 따로 남으며, 두 경로 모두 그 커밋 하나에서 실행했다.

변화율은 같은 세션 현행 중앙값을 분모로 한 관찰값이며 성능 합격선이나 운영 실측 주장이 아니다.

`ROOM-09d-T2`가 요구하는 지표를 하나의 대비 표에 옮겼다. 이 표는 아래 보존 원자료에서 [`scripts/room09-measurement-report.mjs`](../../scripts/room09-measurement-report.mjs)가 생성하므로 손으로 고치지 않는다.

<!-- room09-report:comparison-table:start -->
| 규모 | 제한 ID | 경로 | 후보 수 | 성공 | 실패 | 호출 시간 최소/**중앙**/최대 (ms) | 전체 순회 최소/**중앙**/최대 (ms) | 처리량 최소/**중앙**/최대 (ROOM/s) | DB 호출 수 | DB 실행시간 중앙 (ms) | 현행 대비 시간 | 현행 대비 처리량 | 현행 대비 DB 시간 |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 10 | 현행(단일) | 20 | 20 | 0 | 56.7525 / **61.0767** / 90.0883 | 56.7525 / **61.0767** / 90.0883 | 222.0044 / **327.4571** / 352.4074 | 53 | 2.4620 | 기준 | 기준 | 기준 |
| small | 10 | 후보(분할) | 20 | 20 | 0 | 218.1029 / **233.3307** / 288.8958 | 218.1029 / **233.3307** / 288.8958 | 69.2291 / **85.7153** / 91.6998 | 149 | 5.3852 | +282.03% | −73.82% | +118.73% |
| small | 20 | 현행(단일) | 20 | 20 | 0 | 51.5494 / **53.0012** / 57.2266 | 51.5494 / **53.0012** / 57.2266 | 349.4878 / **377.3499** / 387.9774 | 53 | 2.2346 | 기준 | 기준 | 기준 |
| small | 20 | 후보(분할) | 20 | 20 | 0 | 203.6582 / **243.7481** / 258.5902 | 203.6582 / **243.7481** / 258.5902 | 77.3425 / **82.0519** / 98.2038 | 146 | 5.2518 | +359.89% | −78.26% | +135.03% |
| medium | 10 | 현행(단일) | 2,000 | 2,000 | 0 | 7,328.3884 / **7,387.3736** / 7,976.2829 | 7,328.3884 / **7,387.3736** / 7,976.2829 | 250.7434 / **270.7322** / 272.9113 | 5,003 | 175.4721 | 기준 | 기준 | 기준 |
| medium | 10 | 후보(분할) | 2,000 | 2,000 | 0 | 14,147.1113 / **14,359.9942** / 15,522.9240 | 14,147.1113 / **14,359.9942** / 15,522.9240 | 128.8417 / **139.2758** / 141.3716 | 13,613 | 390.5296 | +94.39% | −48.56% | +122.56% |
| medium | 100 | 현행(단일) | 2,000 | 2,000 | 0 | 10,829.0857 / **11,660.9395** / 11,830.1347 | 10,829.0857 / **11,660.9395** / 11,830.1347 | 169.0598 / **171.5128** / 184.6878 | 5,003 | 243.9787 | 기준 | 기준 | 기준 |
| medium | 100 | 후보(분할) | 2,000 | 2,000 | 0 | 23,607.5525 / **23,994.8042** / 25,223.8549 | 23,607.5525 / **23,994.8042** / 25,223.8549 | 79.2900 / **83.3514** / 84.7187 | 13,073 | 472.4451 | +105.77% | −51.40% | +93.64% |
| medium | 1000 | 현행(단일) | 2,000 | 2,000 | 0 | 7,886.9309 / **8,111.6719** / 8,365.5170 | 7,886.9309 / **8,111.6719** / 8,365.5170 | 239.0767 / **246.5583** / 253.5841 | 5,003 | 191.2156 | 기준 | 기준 | 기준 |
| medium | 1000 | 후보(분할) | 2,000 | 2,000 | 0 | 15,342.6804 / **15,797.3735** / 16,219.8304 | 15,342.6804 / **15,797.3735** / 16,219.8304 | 123.3059 / **126.6033** / 130.3553 | 13,019 | 366.4445 | +94.75% | −48.65% | +91.64% |
| large | 10 | 현행(단일) | 10,000 | 10,000 | 0 | 174,866.9621 / **284,453.8824** / 320,321.7204 | 174,866.9621 / **284,453.8824** / 320,321.7204 | 31.2186 / **35.1551** / 57.1863 | 25,003 | 1,744.2023 | 기준 | 기준 | 기준 |
| large | 10 | 후보(분할) | 10,000 | 10,000 | 0 | 73,919.1350 / **145,841.8110** / 151,774.4103 | 73,919.1350 / **145,841.8110** / 151,774.4103 | 65.8873 / **68.5674** / 135.2830 | 68,014 | 3,378.7362 | −48.73% | +95.04% | +93.71% |
| large | 100 | 현행(단일) | 10,000 | 10,000 | 0 | 139,126.1343 / **153,662.1539** / 214,242.8636 | 139,126.1343 / **153,662.1539** / 214,242.8636 | 46.6760 / **65.0778** / 71.8772 | 25,003 | 1,333.6814 | 기준 | 기준 | 기준 |
| large | 100 | 후보(분할) | 10,000 | 10,000 | 0 | 63,413.4151 / **67,659.9952** / 108,056.3586 | 63,413.4151 / **67,659.9952** / 108,056.3586 | 92.5443 / **147.7978** / 157.6953 | 65,314 | 1,752.2884 | −55.97% | +127.11% | +31.39% |
| large | 1000 | 현행(단일) | 10,000 | 10,000 | 0 | 155,413.3337 / **267,583.1773** / 274,361.8127 | 155,413.3337 / **267,583.1773** / 274,361.8127 | 36.4482 / **37.3716** / 64.3445 | 25,005 | 1,866.2098 | 기준 | 기준 | 기준 |
| large | 1000 | 후보(분할) | 10,000 | 10,000 | 0 | 79,431.1564 / **114,303.2526** / 126,136.4719 | 79,431.1564 / **114,303.2526** / 126,136.4719 | 79.2792 / **87.4866** / 125.8952 | 65,044 | 2,325.9530 | −57.28% | +134.10% | +24.64% |
<!-- room09-report:comparison-table:end -->

호출 시간(`callElapsedNanos`)과 전체 순회 시간(`wholeTurnElapsedNanos`)은 각각 최소·중앙·최대를 따로 낸다. 현재 fixture에서는 두 값이 모든 run에서 같다. 한 번의 호출이 곧 due 집합 전체 순회이고 순회 중 새 due ROOM 유입을 넣지 않았기 때문이다. 값이 같다는 문장에 기대지 않고 두 열을 모두 원자료에서 생성하므로, 두 지표가 갈리는 fixture가 생기면 표와 `--check`가 그 차이를 그대로 드러낸다. 실패는 모든 조합에서 `0`이고 성공 수는 초기 due 수와 같다.

소형은 due ROOM이 20개다. 제한 ID `10`은 두 배치로 나누고 `20`은 한 배치로 전부 처리한다. `100`·`1000`은 `20`과 같은 단일 배치가 되어 별도로 측정하지 않았다.

관찰되는 형태는 세 가지다.

첫째, 규모에 따라 우열이 뒤집힌다. 소형과 중형에서는 현행이 빠르고 대형에서는 후보가 빠르다. 대형에서 후보는 실행시간을 `48.73%`에서 `57.28%`까지 줄이고 처리량을 `95.04%`에서 `134.10%`까지 늘린다. 교차 구간은 due `2,000`과 `10,000` 사이다.

둘째, 소형 열세의 원인은 배치 분할이 아니라 ROOM당 트랜잭션 비용이다. 배치를 `2`개에서 `1`개로 줄여 선별 쿼리를 없애도 후보는 `233.3307 ms`와 `243.7481 ms`로 사실상 같다. 같은 20개 ROOM을 처리하는 데 후보는 DB 호출을 `146~149`회 쓴다. ROOM마다 트랜잭션을 열고 최신 상태·버전을 다시 읽어 커밋하는 고정 비용이 남는 격차를 만든다.

셋째, 제한 ID 값 자체는 우열을 뒤집지 못한다. 같은 규모 안에서 `10`·`100`·`1000`의 시간 변화율이 중형은 `+94.39%`~`+105.77%`, 대형은 `−48.73%`~`−57.28%` 범위에 머문다.

### 대기열 포함 후보 결과

시작 경계를 지난 `CLOSED` due ROOM마다 `WAITING` 10명을 둔 별도 fixture의 후보 종료 비용이다. 현행 비교 없이 후보만 측정했고 fixture 구성이 달라 위 표에 넣지 않는다.

<!-- room09-report:waiting-queue-table:start -->
| 규모 | 제한 ID | ROOM당 WAITING | 후보 수 | 성공 | 실패 | 호출 시간 최소/**중앙**/최대 (ms) | 전체 순회 최소/**중앙**/최대 (ms) | 처리량 최소/**중앙**/최대 (ROOM/s) | DB 호출 수 | DB 실행시간 중앙 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 10 | 10 | 20 | 20 | 0 | 216.4720 / **257.8804** / 268.6512 | 216.4720 / **257.8804** / 268.6512 | 74.4460 / **77.5553** / 92.3907 | 129 | 5.1126 |
<!-- room09-report:waiting-queue-table:end -->

`WAITING` 총 200건을 종료하는데도 `WAITING` 없는 같은 fixture의 후보(`233.3307 ms`, `149`회)와 같은 수준이다. 이 규모에서는 시작 경계 대기열 종료가 유의미한 추가 비용을 만들지 않는다. 다만 두 값은 서로 다른 실행에서 얻었으므로 차이를 대기열 유무의 효과로 읽지 않는다.

### 보존 원자료

<!-- room09-report:preserved-data-table:start -->
| 파일 | SHA-256 |
| --- | --- |
| [`room-09d-direct-comparison-small-limit-10.json`](results/room-09d/room-09d-direct-comparison-small-limit-10.json) | `342231913DA933C8E340A5F72BF9CF160C89A0296135B8F9283C0D8386C136B2` |
| [`room-09d-direct-comparison-small-limit-20.json`](results/room-09d/room-09d-direct-comparison-small-limit-20.json) | `395ABD48F61FB838C4D443173870F732C39D4D4BD49B8A2A91163927E2B3B2DD` |
| [`room-09d-direct-comparison-medium-limit-10.json`](results/room-09d/room-09d-direct-comparison-medium-limit-10.json) | `5F4DA6AB29E0885E44FFEAEE39BA9A1CC6388315040A898C9EE6E4AC1370D04D` |
| [`room-09d-direct-comparison-medium-limit-100.json`](results/room-09d/room-09d-direct-comparison-medium-limit-100.json) | `B812E6F156CD9D0CD95BE01C43C2FAAAB281BC657CBBA95C62C3B11D6706C5C1` |
| [`room-09d-direct-comparison-medium-limit-1000.json`](results/room-09d/room-09d-direct-comparison-medium-limit-1000.json) | `CD1CC766E40527B96AA0775E22DB9CEDEBD6502A950DAE19F489CBF31F747E2C` |
| [`room-09d-direct-comparison-large-limit-10.json`](results/room-09d/room-09d-direct-comparison-large-limit-10.json) | `7EAA1730951DCCF9F8A195D1E3A6AA99749730085DC97F5AA5CE99B63221F3A7` |
| [`room-09d-direct-comparison-large-limit-100.json`](results/room-09d/room-09d-direct-comparison-large-limit-100.json) | `18F631A7E6C1679C7297DAC07F335A689215E2E019EA26A01D03C670C6BFB725` |
| [`room-09d-direct-comparison-large-limit-1000.json`](results/room-09d/room-09d-direct-comparison-large-limit-1000.json) | `D73A889156F0A8F535C2876F490A0BCCD3284EE4ED48ECFDA8A8F300A5978921` |
| [`room-09d-candidate-small-limit-10.json`](results/room-09d/room-09d-candidate-small-limit-10.json) | `C4286E1C01E263ED0CE3835CCE58636E53F22F8C8B4001C0C099FBCBBFEAF1F2` |
| [`room-09d-waiting-queue-small-limit-10.json`](results/room-09d/room-09d-waiting-queue-small-limit-10.json) | `AB2D754130A8244E4B4B8EED3CA8141AF06DF7AE150BF80E12C4C1A80AD4C5F2` |
<!-- room09-report:preserved-data-table:end -->

SHA-256 기준은 위 현행 원자료와 같다. 이 표도 생성물이며 `scripts/room09-measurement-report.mjs`가 원자료에서 다시 계산한다.

### 확정한 초기 운영값

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 제한 ID | `100` | 제한 ID가 규모별 우열을 뒤집지 못하므로 성능을 근거로 삼지 않는다. 대신 선별 오버헤드 곡선의 무릎을 택한다. 배치 하나가 DB 호출 `3`회를 쓰므로 총 호출은 `1/limit`로 준다. 대형에서 `10`→`100`이 `2,700`회를 줄이고 `100`→`1000`은 `270`회만 더 줄인다. |
| 실행시간 경고 | `180s` | `candidate-limit` `100`의 관찰 최대 실행시간은 대형 `108,056 ms`다. 쓰지 않는 제한 ID까지 포함한 관찰 최대 `151,774 ms`를 기준으로 보수적으로 잡는다. `lockAtMostFor` `600s`의 `30%` 지점이라 조기 경보로 쓴다. |
| `lockAtMostFor` | `10m` | 경고 기준과 고정 비율로 묶지 않는다. 잠금 보유 인스턴스가 죽었을 때 다른 인스턴스가 이어받기까지 감수할 복구 지연으로 정하며, `trigger-delay` `15m` 한 주기 안에 든다. **한 실행의 최장 시간이 보장된 값은 아니다.** 아래 한계를 함께 본다. |
| 실행 주기 | `15m`(jitter `3m`) | 기존 값을 유지한다. 이 측정이 조정 근거를 만들지 않았다. |

제한 ID의 **실패 파급 범위는 이 값과 무관하다.** ROOM마다 독립 트랜잭션에서 처리하고, 실패는 로그만 남기고 넘어가며, 커서도 ROOM마다 전진한다. 따라서 어떤 제한 ID에서도 한 번의 실패가 미치는 범위는 ROOM `1`건이다.

`ROOM-09d-T4`에 따라 위 세 값(`100`, `180s`, `10m`)을 측정 결과와 함께 제시하고 사용자 승인으로 확정했다. 승인 기록은 [#390 코멘트](https://github.com/bamsongi-club/albam-mate/issues/390)에 남긴다.

### 측정 한계와 재검토 조건

- 이 수치는 로컬 단일 인스턴스와 Testcontainers 환경의 fixture 결과이며 운영 실측이나 성능 합격선이 아니다. 배포 환경에서 다시 측정한 값과 직접 비교하지 않는다.
- 조합마다 현행과 후보는 같은 세션에서 얻었지만, 서로 다른 조합은 서로 다른 세션에서 측정했다. 같은 대형 현행이 `153,662 ms`와 `284,453 ms`로 벌어지는 것처럼 절대값은 호스트 부하에 크게 좌우된다. 따라서 조합 간 절대값을 직접 비교하지 않고 각 조합 안의 변화율만 읽는다.
- 이 측정은 정상 처리 경로만 다룬다. 고의 실패·재시도·순회 중 새 due ROOM 유입은 포함하지 않으며, 실패 격리는 `#382`의 `ROOM-09b-T4` 검증 결과를 재사용한다.
- 중형·대형 직접 비교 원자료 6개의 `executionCommand`는 측정 당시(`gitSha` `a3adcaeabf0e2a60751978767a9a0f9b9202c038`) 보고서 생성 코드의 selector 오류로 후보 단독 메서드를 가리켰고, 측정을 다시 하지 않고 `scripts/room09-measurement-report.mjs`가 정정했다. 정정은 그 필드 한 줄만 바꾸며, 스크립트가 나머지 모든 값이 동일한지 확인한 뒤에만 파일을 쓴다. 측정값은 여전히 `a3adcae`에서 실행한 결과다.
- 소형·중형 열세의 원인을 ROOM당 트랜잭션 고정 비용으로 좁혔으나, 그 고정 비용의 내부 구성까지는 나누지 않았다. 확정하려면 트랜잭션 경계별 프로파일링이 따로 필요하다.
- `candidate-limit`은 한 번에 선별할 ID 수일 뿐이고, `correctBoundedDueRooms`는 후보가 없어질 때까지 다음 배치를 계속 처리한다. 한 실행의 후보 총수·배치 수·시간에 상한이 없고 그런 설정도 없다. 따라서 대형에서 관찰한 `151,774 ms`는 상한이 아니라 이 fixture의 관찰값이며, backlog가 커져 한 실행이 `lockAtMostFor` `10m`을 넘으면 다른 인스턴스가 만료된 잠금을 얻는다. 다만 중첩 범위는 좁다. `advanceCursor`가 `progressVersion`과 `executionGeneration`을 함께 CAS하고 새 실행의 `claimExecution`이 `executionGeneration`을 올리므로, 기존 실행은 다음 ROOM 하나를 처리한 직후 CAS 실패로 멈춘다. 중첩 처리는 최대 ROOM `1`건이다. 실행 상한 도입이나 잠금 갱신·fencing은 `#382` 생산 로직 변경이라 `#390` 범위 밖이다. 어떤 형태로 후속을 남길지는 아직 정하지 않았다.
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

두 명령은 `build/reports/measurements/`에 JSON을 다시 만든다. 제한 ID 일부만 다시 재려면 `issue390.candidateLimits`에 쉼표로 값을 준다.

### 보고 단계

측정과 보고를 나눈다. 대형 한 조합이 수십 분이라, 재현 명령·대비 표·SHA-256 같은 파생물이 원자료와 어긋날 때 측정을 다시 돌릴 수 없기 때문이다. 파생물은 보존 원자료만 읽어 다시 만든다.

```powershell
node scripts/room09-measurement-report.mjs --check
```

`--check`는 보존 원자료의 재현 메타데이터와 이 문서의 생성 표가 원자료와 일치하는지만 확인하고 아무것도 쓰지 않는다. 어긋나면 실패한다.

```powershell
node scripts/room09-measurement-report.mjs --write
```

`--write`는 원자료를 정본으로 삼아 재현 메타데이터와 위 세 생성 표를 다시 만든다. 측정값은 바꾸지 않으며, 재현 메타데이터를 뺀 나머지가 모두 같은지 확인한 뒤에만 파일을 쓴다. 새로 측정했다면 `build/reports/measurements/`의 JSON을 `results/room-09d/`로 복사한 뒤 `--write`를 실행한다.

후속 #390은 이 기준선 원자료를 비교 근거로 사용하며, 결과만으로 제한 ID 수·반복·재시도·주기나 조건부 직접 갱신을 확정하지 않는다. 초기 운영값은 위 후보 결과를 제시한 뒤 사용자 승인으로 확정한다.
