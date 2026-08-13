# ROOM k6 부하테스트

이 디렉터리는 Jiwon이 소유하며, [#649](https://github.com/bamsongi-club/albam-mate/issues/649)의 ROOM 핵심 HTTP k6 시나리오 5종을 관리한다. 소스와 결과의 공통 배치 규칙은 [Load Tests](../../README.md)를 따른다.

테스트의 우선 목적은 동시성 오류·불변식 위반·공통 병목을 찾고, 개선 전후를 같은 조건으로 비교하는 것이다. `ROOM_CONCURRENT_MODIFICATION`은 계약된 재시도 소진 결과로 별도 기록하며, 그 비율 하나만으로 락 전략을 바꾸지 않는다.

## 소유 범위와 보존 위치

- 이 디렉터리는 ROOM 참가·취소·대기 등록·상세 조회 k6 시나리오, fixture 생성기, 사전·사후 DB 검증을 소유한다.
- 실제 fixture, 비밀번호·세션·CSRF, 원시 summary와 실행 bundle은 Git에 추적하지 않는 `build/k6/room/<run-id>/` 아래에 둔다.
- 현재 승인해 보존한 ROOM k6 측정 문서는 없다. 실행 결과를 정본으로 승격할 때만 `docs/measurements/k6/jiwon/` 아래에 추가한다.

## 제공 시나리오

| 시나리오 | 스크립트 | 초기 입력 | 핵심 판정 |
| --- | --- | --- | --- |
| T1 취소→자동 승격 | `t1-cancel-promotion.js` | hot/spread × 동시 2·4·8 | 성공 취소 수와 FIFO `PROMOTED` 수 일치, 정원·중복 승격 0 |
| T2 동시 대기 등록 | `t2-concurrent-waitlist-registration.js` | hot/spread × 동시 2·4·8, 동일 사용자 중복 | WAITING 사용자·순번 중복 0, 201과 새 WAITING 수 일치, 5xx 0 |
| T3 등록↔취소 경합 | `t3-waitlist-cancel-race.js` | race 반복, `wait-first`·`cancel-first` | 허용 종단만 남고 `RECRUITING + WAITING` 0 |
| T4 마지막 자리 참가 | `t4-last-seat-participation.js` | 마지막 자리 × 동시 2·4·8 | ACTIVE 정확히 한 명, 정원 초과·자동 WAITING 0 |
| T5 역할별 상세 조회 | `t5-room-detail-by-role.js` | public/host/participant × ACTIVE 1·10 | 역할별 shape·헤더, 조회 전후 DB snapshot 동일 |

`public`은 익명이 아니라 로그인한 비관계 사용자다. T5는 `ACTIVE 1/정원 1`, `ACTIVE 10/정원 10`의 future-start `CLOSED` 만석 ROOM을 사용한다.

## Fixture와 격리

`tools/fixture.mjs`가 각 실행의 ROOM·사용자·참가·대기 행을 만들고, 같은 fixture의 사전·사후 DB 불변식을 판정한다. run ID·scenario·입력 조합마다 새 fixture를 만들며, 같은 조건의 bundle은 덮어쓰지 않는다.

| 산출물 | 위치 | 용도 |
| --- | --- | --- |
| `fixture.json` | `build/k6/room/<run-id>/<fixture-id>/` | 실제 ID와 k6 실행 입력 |
| `prepare.sql` | 동일 경로 | fixture 생성 SQL. bcrypt hash가 포함될 수 있어 Git 비추적 |
| `before-verification.json` | 동일 경로 | 실행 전 DB 불변식 |
| `run-manifest.json` | 동일 경로 | 대상 배포 SHA·환경·fixture·k6 버전·시작/종료 UTC를 묶은 실행 기록 |
| `k6-summary.json` | 동일 경로 | `run`이 같은 manifest와 함께 생성한 k6 summary |
| `cleanup.sql` | 동일 경로 | 정확한 생성 ID만 정리하는 SQL |

cleanup은 broad prefix 삭제나 `TRUNCATE`를 쓰지 않는다. fixture ROOM에 비-fixture 사용자의 파생 행이 섞였으면 삭제하지 않고 중단한다.

## 전제와 환경 변수

- k6와 PostgreSQL `psql`이 실행 환경 PATH에 있어야 한다.
- 공식 실행은 고객 데이터가 없는 Terraform 부하 환경 또는 동등한 전용 환경에서만 한다.
- fixture의 `start_at`은 미래로 고정한다. ROOM 상세 조회의 상태 보정이 측정 중 데이터를 바꾸지 않게 하기 위해서다.

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `ALBAM_MATE_TARGET_URL` | 없음 | 대상 서버 URL |
| `ALBAM_MATE_TARGET_ENVIRONMENT` | 없음 | 결과에 남길 대상 환경 식별자. URL·비밀값은 기록하지 않음 |
| `ALBAM_MATE_SOURCE_SHA` | 없음 | 대상 환경에 배포된 40자리 Git SHA |
| `ALBAM_MATE_RUN_ID` | 없음 | fixture `--run-id`와 같은 실행 식별자 |
| `ROOM_K6_FIXTURE_PASSWORD` | 없음 | fixture 계정 로그인 비밀번호 |
| `ROOM_K6_FIXTURE_PASSWORD_HASH` | 없음 | 같은 비밀번호의 `{bcrypt}$` hash. `prepare`에서만 사용 |
| `ROOM_K6_FIXTURE` | 없음 | `prepare`가 만든 `fixture.json` 경로 |
| `ROOM_K6_SESSION_WARMUP_SECONDS` | `15` | 쓰기 시나리오 로그인 후 공통 시작 전 대기 시간 |
| `ROOM_K6_ROUND_INTERVAL_SECONDS` | `20` | 쓰기 시나리오 wave 간격 |
| `ROOM_K6_READ_VUS` | `10` | T5 동시 VU 수 |
| `ROOM_K6_READ_DURATION_SECONDS` | `60` | T5 측정 창 |
| `ROOM_K6_READ_THINK_TIME_MS` | `0` | T5 요청 사이 think time |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | runner 설정 | fixture 생성·검증·정리용 psql 연결 |

`ROOM_K6_FIXTURE_PASSWORD`와 `ROOM_K6_FIXTURE_PASSWORD_HASH`는 같은 private test password여야 하며 Git에 저장하지 않는다.

## 실행

저장소 루트에서 실행한다. 아래는 T1 hot·동시 8명의 예시다.

```powershell
$runId = 'room-t1-hot-8-01'
$env:ALBAM_MATE_RUN_ID = $runId
$env:ALBAM_MATE_TARGET_URL = 'https://<private-target>'
$env:ALBAM_MATE_TARGET_ENVIRONMENT = 'private-loadtest'
$env:ALBAM_MATE_SOURCE_SHA = '<40-character-deployed-git-sha>'
$env:ROOM_K6_FIXTURE_PASSWORD = '<private-password>'
$env:ROOM_K6_FIXTURE_PASSWORD_HASH = '<private-bcrypt-hash>'

$prepared = node load-tests/k6/jiwon/tools/fixture.mjs prepare `
  --scenario t1 --run-id $runId --profile stress --mode hot --concurrency 8 |
  ConvertFrom-Json
$env:ROOM_K6_FIXTURE = $prepared.fixturePath

node load-tests/k6/jiwon/tools/fixture.mjs run --fixture $prepared.fixturePath

node load-tests/k6/jiwon/tools/fixture.mjs verify `
  --fixture $prepared.fixturePath --stage after
```

`run`은 fixture가 가리키는 scenario 스크립트만 실행하고, 실행 직전·직후에 같은 `run-manifest.json`을 갱신한다. `after` 검증은 이 manifest와 같은 경로의 `k6-summary.json`만 사용하므로 수동으로 다른 실행 summary를 섞을 수 없다. `ALBAM_MATE_SOURCE_SHA`에는 로컬 스크립트 checkout이 아니라 **대상 환경에 배포된** SHA를 넣는다.

시나리오별 fixture 입력은 아래처럼 바꾼다.

| 대상 | `prepare` 옵션 |
| --- | --- |
| T1 | `--scenario t1 --profile stress --mode hot|spread --concurrency 2|4|8` |
| T2 서로 다른 사용자 | `--scenario t2 --profile stress --mode hot|spread --subcase distinct --concurrency 2|4|8` |
| T2 동일 사용자 | `--scenario t2 --profile spike --mode hot --subcase duplicate --concurrency 2` |
| T3 natural race | `--scenario t3 --profile stress --t3-mode race` |
| T3 순차 검증 | `--scenario t3 --profile spike --t3-mode wait-first|cancel-first` |
| T4 | `--scenario t4 --profile stress --concurrency 2|4|8` |
| T5 | `--scenario t5 --t5-role public|host|participant --t5-scale 1|10` |

`stress`의 기본값은 독립 ROOM 5개를 같은 동시성으로 연속 wave 실행하는 것이다. 단, T3 `race`는 각 독립 ROOM의 wait/cancel 한 쌍을 같은 barrier에 병렬 배치한다. `spike`의 기본값은 독립 ROOM 1개에 즉시 한 wave를 보낸다. T5는 VU마다 측정 창 전체를 한 번 실행한다.

## 결과 확인

사후 검증은 HTTP 응답 분류와 DB snapshot을 함께 판정한다.

실행 결과를 비교하거나 정본으로 승격할 때는 `run-manifest.json`의 `sourceSha`, `targetEnvironment`, `fixtureId`, `startedAtUtc`, `finishedAtUtc`, `k6Version`을 함께 보존한다.

| 상태 | 의미 |
| --- | --- |
| `PASS` | HTTP 응답 분류, 시나리오별 DB 불변식, hard correctness gate를 통과 |
| `FAIL` | 예상 밖 응답·5xx·payload 불일치·FIFO/정원/중복/무변경 gate 위반 |
| `INVALID` | fixture 사전 조건 또는 필수 artifact가 부족해 결과를 성능 근거로 쓸 수 없음 |

`room_success`, `room_created`, `room_business_failures`, `room_concurrent_failures`, `room_unexpected_4xx`, `room_server_failures`, `room_contract_failures`, `room_request_duration`, `room_start_skew_ms`를 k6 summary에서 확인한다.

첫 기준선의 p50/p95/p99/RPS/409 비율은 관찰값이다. DB CPU·connection·lock wait·query call/time과 application retry log는 같은 측정 창의 승인된 관측 source에서 별도로 수집한다. production 락 전략은 이 스크립트가 바꾸지 않는다.

분석이 끝난 뒤에만 같은 fixture를 정리한다.

```powershell
node load-tests/k6/jiwon/tools/fixture.mjs cleanup --fixture $prepared.fixturePath
```

## 검증

```powershell
node --test load-tests/k6/jiwon/tests/fixture-model.test.mjs
node --test load-tests/k6/jiwon/tests/t3-execution-plan.test.mjs
node --test load-tests/k6/jiwon/tests/fixture-runner.test.mjs

Get-ChildItem load-tests/k6/jiwon -Recurse -File |
  Where-Object { $_.Extension -in '.js', '.mjs' } |
  ForEach-Object { node --check $_.FullName }

node scripts/check-doc-links.mjs
```

k6가 설치된 실행 환경에서는 아래도 추가한다.

```powershell
k6 inspect load-tests/k6/jiwon/t1-cancel-promotion.js
k6 inspect load-tests/k6/jiwon/t2-concurrent-waitlist-registration.js
k6 inspect load-tests/k6/jiwon/t3-waitlist-cancel-race.js
k6 inspect load-tests/k6/jiwon/t4-last-seat-participation.js
k6 inspect load-tests/k6/jiwon/t5-room-detail-by-role.js
```
