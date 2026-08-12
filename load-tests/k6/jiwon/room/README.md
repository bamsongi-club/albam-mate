# ROOM 핵심 HTTP k6 테스트

이 폴더는 [#649](https://github.com/bamsongi-club/albam-mate/issues/649)의 ROOM HTTP k6 테스트 5종을 구현한다. 테스트의 우선 목적은 동시성 오류·불변식 위반·공통 병목을 찾고 성능 개선 전후를 같은 조건으로 비교하는 것이다. `ROOM_CONCURRENT_MODIFICATION`은 계약된 재시도 소진 결과로 따로 기록하며, 그 비율만으로 락 전략 변경을 결론내리지 않는다.

과거 닫힌 PR의 scenario, 결과 수치와 fixture는 재사용하지 않는다. 이 폴더의 fixture는 run ID·scenario·입력 조합별로 새 ROOM과 사용자만 만든다.

## 제공 시나리오

| ID | 스크립트 | 초기 매트릭스 | 핵심 판정 |
| --- | --- | --- | --- |
| T1 | `t1-cancel-promotion.js` | hot/spread × 동시 2·4·8 | 성공 취소 수와 FIFO `PROMOTED` 수 일치, 정원·중복 승격 0 |
| T2 | `t2-concurrent-waitlist-registration.js` | hot/spread × 동시 2·4·8, 동일 사용자 중복 | WAITING 사용자·순번 중복 0, 201과 새 WAITING 수 일치, 5xx 0 |
| T3 | `t3-waitlist-cancel-race.js` | race pair 반복, `wait-first`·`cancel-first` 사전 검증 | 허용 종단만 남고 `RECRUITING + WAITING` 0 |
| T4 | `t4-last-seat-participation.js` | 마지막 자리 × 동시 2·4·8 | ACTIVE 정확히 한 명, 정원 초과·자동 WAITING 0 |
| T5 | `t5-room-detail-by-role.js` | public/host/participant × ACTIVE 1·10 | 역할별 shape·헤더, 조회 전후 DB snapshot 동일 |

`public`은 익명이 아니라 로그인한 비관계 사용자다. T5는 `ACTIVE 1/정원 1`, `ACTIVE 10/정원 10`의 future-start `CLOSED` 만석 ROOM을 사용한다. 현재 정원 상한이 10이고 정원을 채우면 `CLOSED`가 되기 때문이다.

## 사전 조건

- 고객 데이터가 없는 Terraform 부하 환경 또는 동등한 전용 환경에서만 공식 실행한다.
- k6와 PostgreSQL `psql`이 실행 환경의 PATH에 있어야 한다. `psql`은 표준 libpq 환경 변수(`PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`) 또는 runner가 제공하는 동등한 private 연결 설정을 사용한다.
- `ROOM_K6_FIXTURE_PASSWORD`와 `ROOM_K6_FIXTURE_PASSWORD_HASH`는 같은 private test password의 bcrypt 값이어야 한다. hash는 `{bcrypt}$`로 시작해야 하며 Git에 저장하지 않는다.
- `ALBAM_MATE_RUN_ID`는 fixture의 `--run-id`와 반드시 같아야 한다. 안전한 소문자 run ID만 쓴다.
- T1~T4의 로그인·CSRF는 k6 `setup()`에서 먼저 끝낸다. VU는 준비된 세션을 각자의 CookieJar에 설치하고 공통 목표 시각에 쓰기 요청을 보낸다. k6 VU 간 shared-memory readiness barrier는 쓰지 않는다.

공통 목표 시각의 실제 지연은 `room_start_skew_ms`로 남긴다. 이 값은 동시성 자극의 품질을 해석하는 관찰값이며, 첫 실행에는 임의 합격선을 두지 않는다.

## Fixture 준비

아래는 PowerShell 예시다. 실제 비밀번호·hash·대상 URL은 private runner input으로만 전달한다.

```powershell
$runId = 'room-t1-hot-8-01'
$env:ALBAM_MATE_RUN_ID = $runId
$env:ROOM_K6_FIXTURE_PASSWORD = '<private-password>'
$env:ROOM_K6_FIXTURE_PASSWORD_HASH = '<private-bcrypt-hash>'

$prepared = node load-tests/k6/jiwon/room/tools/fixture.mjs prepare `
  --scenario t1 --run-id $runId --profile stress --mode hot --concurrency 8 |
  ConvertFrom-Json

$env:ROOM_K6_FIXTURE = $prepared.fixturePath
```

`prepare`는 아래를 하나의 fixture bundle로 `build/k6/room/<run-id>/<fixture-id>/`에 남긴다.

- 실제 ROOM·사용자 ID가 들어 있는 `fixture.json`
- secret hash가 들어 있을 수 있는 실행 전용 `prepare.sql`
- 사전 DB 불변식 결과 `before-verification.json`
- 정확한 생성 ID와 email/title을 확인한 뒤에만 삭제하는 `cleanup.sql`

같은 run ID·scenario·입력의 bundle이 있으면 `prepare`는 덮어쓰지 않고 중단한다. 다른 시나리오와 다른 run의 fixture를 prefix로 함께 지우지 않는다.

### 시나리오별 준비 예

```powershell
# T1: hot 또는 spread, concurrency 2/4/8
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t1 --run-id room-t1-spread-4 --profile stress --mode spread --concurrency 4

# T2: 서로 다른 사용자와 동일 사용자 중복은 별도 fixture로 실행
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t2 --run-id room-t2-hot-8 --profile stress --mode hot --subcase distinct --concurrency 8
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t2 --run-id room-t2-duplicate --profile spike --mode hot --subcase duplicate --concurrency 2

# T3: natural race와 두 순차 종단을 각각 새 fixture로 확인
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t3 --run-id room-t3-race --profile stress --t3-mode race
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t3 --run-id room-t3-wait-first --profile spike --t3-mode wait-first
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t3 --run-id room-t3-cancel-first --profile spike --t3-mode cancel-first

# T4: 마지막 자리 경쟁
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t4 --run-id room-t4-seat-8 --profile stress --concurrency 8

# T5: 역할과 ACTIVE 규모는 각각 별도 실행
node load-tests/k6/jiwon/room/tools/fixture.mjs prepare --scenario t5 --run-id room-t5-public-10 --t5-role public --t5-scale 10
```

`stress`의 기본값은 같은 동시성으로 독립 ROOM 5개를 연속 wave로 실행하는 것이다. `spike`의 기본값은 독립 ROOM 1개에 즉시 한 wave를 보낸다. `--rounds 1..20`으로 명시적으로 바꿀 수 있다. T5는 VU마다 측정 창 전체를 한 번 실행하며, 같은 `ROOM_K6_READ_VUS`, `ROOM_K6_READ_DURATION_SECONDS`, `ROOM_K6_READ_THINK_TIME_MS`를 역할·규모별로 고정해 별도 실행한다.

## k6 실행과 사후 검증

`prepare` 출력의 `fixturePath`, `outputDirectory`를 그대로 사용한다. T1 예시는 아래와 같다.

```powershell
$summary = Join-Path $prepared.outputDirectory 'k6-summary.json'
k6 run --summary-export $summary load-tests/k6/jiwon/room/t1-cancel-promotion.js

node load-tests/k6/jiwon/room/tools/fixture.mjs verify `
  --fixture $prepared.fixturePath --stage after --summary $summary
```

T2~T5도 같은 방식으로 각 script만 바꾼다. 실제 실행 행에는 source Git SHA, target 환경, fixture ID, 시작·종료 UTC, k6 version을 `outputDirectory`의 private run metadata에 함께 남긴다. raw summary·fixture·SQL은 Git에 커밋하지 않는다.

사후 검증은 다음 결과를 낸다.

| 상태 | 의미 |
| --- | --- |
| `PASS` | HTTP 응답 분류, 시나리오별 DB 불변식, hard correctness gate를 통과 |
| `FAIL` | 예상 밖 응답·5xx·payload 불일치·FIFO/정원/중복/무변경 gate 위반 |
| `INVALID` | fixture 사전 조건, source/환경 식별 또는 필수 artifact가 부족해 결과를 성능 근거로 쓰면 안 됨 |

T5는 `fixture.json`의 `baselineSnapshot`과 사후 snapshot을 비교한다. 미래 시작 fixture인데 GET이 ROOM·participation·waitlist를 바꾸면 FAIL이다.

## 응답 분류과 관찰 지표

- 성공: 기대 200/201과 payload shape
- 계약된 업무 실패: 예를 들어 T4의 `CAPACITY_EXCEEDED`, T3의 `WAITLIST_NOT_AVAILABLE`
- 동시성 실패: `ROOM_CONCURRENT_MODIFICATION`
- 오류: 예상 밖 4xx, 모든 5xx, payload shape 불일치

`room_success`, `room_created`, `room_business_failures`, `room_concurrent_failures`, `room_unexpected_4xx`, `room_server_failures`, `room_contract_failures`, `room_request_duration`, `room_start_skew_ms`를 k6 summary에서 확인한다. 첫 기준선에서는 p50/p95/p99/RPS/409 비율을 관찰값으로만 기록한다. DB CPU·connection·lock wait·query call/time와 application retry log는 승인된 관측 source에서 같은 measurement window로 별도 수집한다.

## 정리와 정적 검증

사후 분석이 끝난 뒤에만 해당 fixture만 정리한다. broad prefix 삭제, `TRUNCATE`, 다른 run의 bundle 교체는 하지 않는다.

```powershell
node load-tests/k6/jiwon/room/tools/fixture.mjs cleanup --fixture $prepared.fixturePath

node --test load-tests/k6/jiwon/room/tests/fixture-model.test.mjs
node --check load-tests/k6/jiwon/room/lib/room-k6.js
node --check load-tests/k6/jiwon/room/tools/fixture.mjs
Get-ChildItem load-tests/k6/jiwon/room -Filter '*.js' | ForEach-Object { node --check $_.FullName }
```

실행 환경에 k6가 있으면 아래도 추가한다.

```powershell
k6 inspect load-tests/k6/jiwon/room/t1-cancel-promotion.js
k6 inspect load-tests/k6/jiwon/room/t2-concurrent-waitlist-registration.js
k6 inspect load-tests/k6/jiwon/room/t3-waitlist-cancel-race.js
k6 inspect load-tests/k6/jiwon/room/t4-last-seat-participation.js
k6 inspect load-tests/k6/jiwon/room/t5-room-detail-by-role.js
```
