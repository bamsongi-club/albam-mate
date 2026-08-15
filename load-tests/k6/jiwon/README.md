# ROOM k6 부하테스트

이 디렉터리는 Jiwon이 소유하며, [#649](https://github.com/bamsongi-club/albam-mate/issues/649)의 ROOM 핵심 HTTP k6 시나리오 5종, fixture 생성기와 사전·사후 DB 검증을 관리한다. [#709](https://github.com/bamsongi-club/albam-mate/issues/709)은 이 자산을 전용 Terraform 스택의 원격 발생기로 전달하는 portable bundle 계약을 추가한다. 소스와 결과의 공통 배치 규칙은 [Load Tests](../../README.md)를 따른다.

실제 fixture, 비밀번호·세션·CSRF, 원시 summary와 실행 bundle은 Git에 추적하지 않는 `build/k6/room/<run-id>/` 아래에 둔다.

## 무엇에 답하려는 측정인가

테스트의 우선 목적은 동시성 오류·불변식 위반·공통 병목을 찾고, 개선 전후를 같은 조건으로 비교하는 것이다. 시나리오별 HTTP 응답, DB 불변식과 실행 조건을 함께 기록한다.

### 이 측정이 답하지 않는 것

이 측정은 production 락 전략을 결정하지 않는다. `ROOM_CONCURRENT_MODIFICATION` 비율은 재시도 소진 결과를 관찰하는 지표이며, 락 전략 변경은 별도 측정·승인으로 판단한다.

## 제공 시나리오

| 시나리오 | 스크립트 | 초기 입력 | 핵심 판정 |
| --- | --- | --- | --- |
| T1 취소→자동 승격 | `t1-cancel-promotion.js` | hot/spread × 동시 2·4·8 | 성공 취소 수와 FIFO `PROMOTED` 수 일치, 정원·중복 승격 0 |
| T2 동시 대기 등록 | `t2-concurrent-waitlist-registration.js` | hot/spread × 동시 2·4·8, 동일 사용자 중복 | WAITING 사용자·순번 중복 0, 201과 새 WAITING 수 일치, 5xx 0 |
| T3 등록↔취소 경합 | `t3-waitlist-cancel-race.js` | race 반복, `wait-first`·`cancel-first` | 허용 종단만 남고 `RECRUITING + WAITING` 0 |
| T4 마지막 자리 참가 | `t4-last-seat-participation.js` | 마지막 자리 × 동시 2·4·8 | ACTIVE 정확히 한 명, 정원 초과·자동 WAITING 0 |
| T5 역할별 상세 조회 | `t5-room-detail-by-role.js` | public/host/participant × ACTIVE 1·10 | 역할별 shape·헤더, 조회 전후 DB snapshot 동일 |

`public`은 익명이 아니라 로그인한 비관계 사용자다. T5는 `ACTIVE 1/정원 1`, `ACTIVE 10/정원 10`의 future-start `CLOSED` 만석 ROOM을 사용한다.

## fixture와 격리

`tools/fixture.mjs`가 각 실행의 ROOM·사용자·참가·대기 행을 만들고, 같은 fixture의 사전·사후 DB 불변식을 판정한다. run ID·scenario·입력 조합마다 새 fixture를 만들며, 같은 조건의 bundle은 덮어쓰지 않는다.

| 산출물 | 위치 | 용도 |
| --- | --- | --- |
| `fixture.json` | `build/k6/room/<run-id>/<fixture-id>/` | 실제 ID와 k6 실행 입력 |
| `prepare.sql` | 동일 경로 | fixture 생성 SQL. bcrypt hash가 포함될 수 있어 Git 비추적 |
| `prepare-recovery.json` | 동일 경로 | `prepare` commit 뒤 artifact 생성이 실패했을 때 실행별 ownership marker를 대조해 안전한 cleanup에 쓰는 비밀 없는 복구 입력 |
| `before-verification.json` | 동일 경로 | 실행 전 DB 불변식 |
| `after-verification.json` | 동일 경로 | 실행 뒤 HTTP·DB 불변식 판정. T5 비교는 같은 fixture의 `PASS` artifact만 허용 |
| `run-manifest.json` | 동일 경로 | 대상 배포 SHA·환경·fixture SHA-256·k6 버전·시작/종료 UTC와 `runState`·`completed`를 묶은 실행 기록 |
| `k6-summary.json` | 동일 경로 | `run`이 같은 manifest와 함께 생성한 k6 summary |
| `t5-comparison-verification.json` | `build/k6/room/<run-id>/` | T5 role×scale 6개 실행의 공통 read profile 검증 결과 |
| `cleanup.sql` | 동일 경로 | 정확한 생성 ID만 정리하는 SQL |
| `manifest.json` | 동일 경로 | portable bundle의 clean source revision, immutable artifact hash와 실행 경계 |
| `fixture-plan.json`, `private/prepare-provenance.json` | 동일 경로 | 결정적 fixture 계획과 실행별 ownership·password hash provenance |
| `resource-query.sql`, `resource-output.json` | 동일 경로 | 원격 DB가 반환한 fixture identity 원시 결과 |
| `execution-options.json` | 동일 경로 | 정규화한 k6 환경 값과 T5 read profile |
| `before/after-snapshot.json`, `before/after-diagnosis.json`, `infra-execution.json`, `final-result.json` | 동일 경로 | 원격 실행의 raw DB/k6 결과, 앱 진단과 최종 판정 |

cleanup은 broad prefix 삭제나 `TRUNCATE`를 쓰지 않는다. SQL 실행 전 fixture 경로·결정적 plan·실행별 ownership marker·사용자/ROOM 식별자를 다시 대조하고, fixture ROOM에 비-fixture 사용자의 파생 행이나 아직 남아 있는 다른 ROOM outbox event를 source로 한 notification이 섞였으면 삭제하지 않고 중단한다.

## 전제와 환경 변수

- k6와 PostgreSQL `psql`이 실행 환경 PATH에 있어야 한다.
- 공식 실행은 고객 데이터가 없는 Terraform 부하 환경 또는 동등한 전용 환경에서만 한다.
- fixture의 `start_at`은 미래로 고정한다. ROOM 상세 조회의 상태 보정이 측정 중 데이터를 바꾸지 않게 하기 위해서다.

직접 실행(`prepare → run → verify`)은 위의 로컬 `psql`·k6 조건을 사용한다. portable bundle 원격 실행은 controller에서 Node만 사용하며, `psql`과 k6는 Terraform 스택의 PostgreSQL host·load generator에서만 실행한다.

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
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | runner 설정 | fixture 생성·실행 전 identity 검증·사후 검증·정리용 psql 연결 |

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

`run`은 k6 시작 전에 fixture의 결정적 plan과 현재 DB resource identity를 다시 대조한 뒤, fixture의 SHA-256을 같은 `run-manifest.json`에 기록하고 fixture가 가리키는 scenario 스크립트만 실행한다. `after` 검증은 현재 fixture SHA-256과 manifest를 다시 대조한 뒤 같은 경로의 `k6-summary.json`만 사용하므로, 실행 뒤 손상된 fixture나 수동으로 섞은 다른 실행 summary를 성능 근거로 쓰지 않는다. T5 manifest에는 실제 적용한 `t5ReadOptions`(VU·duration·think time)를 남기고, 같은 정규화 값으로 k6 child process를 실행한다. `ALBAM_MATE_SOURCE_SHA`에는 로컬 스크립트 checkout이 아니라 **대상 환경에 배포된** SHA를 넣는다. T1~T5는 `room_start_skew_ms`의 최댓값이 `1,000ms` 미만이어야 한다. 이는 응답 성능 SLO가 아니라 같은 barrier에 둔 VU가 실제로 함께 시작했는지 판정하는 실행 유효성 gate다.

## Terraform 원격 실행 bundle

각 팀원은 서로 다른 `stack_id`의 전용 Terraform 스택만 사용한다. 앱은 fixture 의미·SQL·diagnosis를, infra는 bundle의 정적 검증·원격 PostgreSQL/k6 실행·원시 artifact 회수만 소유한다. 기존 generic `loadtest` 명령은 ROOM bundle 실행에 사용하지 않는다.

먼저 **변경 없는 앱 checkout**에서, 배포할 commit과 같은 SHA를 `ALBAM_MATE_SOURCE_SHA`에 넣어 bundle을 만든다. `render-bundle`은 local `psql`·k6를 실행하지 않으며, source SHA가 현재 clean Git HEAD와 다르면 생성하지 않는다.

```powershell
$runId = 'room-t1-hot-8-01'
$env:ALBAM_MATE_SOURCE_SHA = '<40-character-deployed-git-sha>'
$env:ROOM_K6_FIXTURE_PASSWORD_HASH = '<private-bcrypt-hash>'

$bundle = node load-tests/k6/jiwon/tools/fixture.mjs render-bundle `
  --scenario t1 --run-id $runId --profile stress --mode hot --concurrency 8 |
  ConvertFrom-Json
```

`manifest.json`에는 source revision과 source/runtime/SQL/execution options의 SHA-256을 기록한다. `validate --for-execution`은 누락·변조·symbolic link·이미 생성된 실행 artifact를 원격 DB 작업 전에 거절한다. `execution-options.json`은 T1~T4의 warm-up·round interval과 T5의 VU·duration·think time을 고정한다.

기본 실행은 PATH의 `k6`와 `psql`을 `shell: false`로 호출한다. Windows 등에서 wrapper가 아닌 Node 기반 도구를 명시해야 할 때만 `ROOM_K6_EXECUTABLE`·`ROOM_K6_ARGUMENT_PREFIX`(string 배열 JSON), `ROOM_K6_PSQL_EXECUTABLE`·`ROOM_K6_PSQL_ARGUMENT_PREFIX`를 사용해 executable과 선행 인수를 분리한다. 이 설정은 shell을 켜거나 manifest/payload의 외부 신뢰 기준을 제공하지 않는다.

그 다음 `albam-mate-infra`의 같은 stack 설정에서 배포와 실행을 수행한다. `room-k6`은 이미 생성된 bundle을 받으며, bundle을 새로 만들거나 앱 SQL 의미를 해석하지 않는다.

`perf.env`의 `APP_REPO`는 bundle을 만든 **동일한 clean checkout**을 가리켜야 한다. infra의 첫 번째 정적 gate는 `APP_REPO/build/k6/room/...` 밖의 bundle과 `RELEASE_SHA`가 다른 source revision을 모두 거절한다.

```bash
./run.sh up
./run.sh deploy
ROOM_K6_FIXTURE_PASSWORD='<private-password>' ./run.sh room-k6 \
  ../albam-mate/build/k6/room/<run-id>/<fixture-id>
./run.sh down
```

원격 단계는 `validate → execution options → prepare SQL → resource query → hydrate → before snapshot/diagnosis → k6 → after snapshot/diagnosis → aggregate` 순서다. prepare/resource query/snapshot은 PostgreSQL host에서, k6는 load generator에서 실행한다. hydrate·diagnosis·aggregate는 bundle 안의 Node 도구가 controller에서 raw artifact만 읽어 수행한다.

정상 흐름은 테스트 직후 `down`으로 전용 DB와 stack을 함께 폐기하므로 fixture cleanup transport를 자동 실행하지 않는다. 실행이 중단돼 stack을 유지해야 한다면 이 최초 원격 흐름을 재사용하지 말고, 후속 명시 cleanup 계약을 먼저 추가한다.

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

`stress`의 기본값은 독립 ROOM 5개를 같은 동시성으로 연속 wave 실행하는 것이다. 단, T3 `race`는 각 독립 ROOM의 wait/cancel 한 쌍을 같은 barrier에 병렬 배치한다. `spike`의 기본값은 독립 ROOM 1개에 즉시 한 wave를 보낸다. T5는 VU마다 측정 창 전체를 한 번 실행한다. T5 role×scale 여섯 실행은 같은 run ID 아래에 만들고, 각 fixture의 `after` 검증 뒤에 아래 비교 검증을 실행한다.

```powershell
node load-tests/k6/jiwon/tools/fixture.mjs compare-t5 --run-id $runId
```

`compare-t5`는 public/host/participant × ACTIVE 1/10 fixture가 모두 있고, 각 완료 manifest의 `t5ReadOptions`가 같으며 각 fixture의 `after-verification.json`이 같은 fixture의 `stage: "after"`, `status: "PASS"`인지 확인한다. 각 `after` 검증은 `room_start_skew_ms` 관측 수가 해당 실행의 VU 수와 같은지도 확인한다. 비교 결과로 사용할 T5 묶음은 이 명령이 `PASS`일 때만 유효하다.

## 결과 확인

사후 검증은 HTTP 응답 분류와 DB snapshot을 함께 판정한다.

직접 `run` 경로의 실행 결과를 비교할 때는 `run-manifest.json`의 source SHA, 대상 환경, fixture 식별자, 시작·종료 UTC, k6 버전, `runState`, `completed`를 함께 대조한다. T5는 `t5ReadOptions`와 `t5-comparison-verification.json`도 함께 대조한다.

ROOM portable bundle → `run.sh room-k6` 결과를 여러 Run 단위로 정본 승격할 때는 [k6 결과 문서 공통 규칙](../../../docs/measurements/k6/README.md)을 따른 campaign manifest를 남긴다. manifest는 clean source와 deployed release 정렬, 허용된 실행 옵션, 포함·제외 Run ledger, local-only bundle의 source·artifact 무결성 식별값, phase·diagnosis·summary 판정을 보존한다. 비밀번호·credential-derived hash·토큰·세션·CSRF·URL·실제 fixture/resource ID·원시 SQL·로그는 manifest에도 넣지 않는다.

portable bundle의 `manifest.json`은 입력 계약이고, `infra-execution.json`·before/after diagnosis·`final-result.json`·`k6-summary.json`은 실행 결과다. 따라서 campaign manifest에는 두 종류의 artifact를 모두 식별하되 원문은 계속 `build/k6/room/`에만 local-only로 둔다.

원시 bundle 자체에는 campaign ID나 `reportDisposition`이 없으므로, campaign을 시작할 때 어떤 논리 Run을 포함·제외할지 별도 ledger로 확정한다. local `build/`에 남은 `PASS` artifact 전체를 자동으로 기준선에 포함하거나, 다른 campaign의 실패 이력을 final campaign에 합치지 않는다.

`room_success`, `room_created`, `room_business_failures`, `room_concurrent_failures`, `room_unexpected_4xx`, `room_server_failures`, `room_contract_failures`, `room_request_duration`, `room_start_skew_ms`를 k6 summary에서 확인한다.

## 결과 판정

| 상태 | 의미 |
| --- | --- |
| `PASS` | HTTP 응답 분류, 시나리오별 DB 불변식, hard correctness·시작 편차 gate를 통과 |
| `FAIL` | 예상 밖 응답·5xx·payload 불일치·FIFO/정원/중복/무변경·동시 시작 편차 gate 위반 |
| `INVALID` | fixture 사전 조건 또는 필수 artifact가 부족해 결과를 성능 근거로 쓸 수 없음 |

첫 기준선의 p50/p95/p99/RPS/409 비율은 관찰값이다. DB CPU·connection·lock wait·query call/time과 application retry log는 같은 측정 창의 승인된 관측 source에서 별도로 수집한다. production 락 전략은 이 스크립트가 바꾸지 않는다.

분석이 끝난 뒤에만 같은 fixture를 정리한다.

```powershell
node load-tests/k6/jiwon/tools/fixture.mjs cleanup --fixture $prepared.fixturePath
```

`prepare`가 DB commit 뒤 resource 조회·artifact 기록 단계에서 중단되면 출력된 `prepare-recovery.json` 경로를 사용한다. 이 명령은 run ID·fixture ID와 실행별 ownership marker를 다시 대조하고, 현재 DB의 정확한 사용자·ROOM identity를 조회한 뒤 일반 cleanup과 같은 비-fixture 관계 검사를 통과할 때만 삭제한다. 같은 결정적 fixture라도 다른 실행이 commit한 ROOM이면 marker 불일치로 삭제하지 않는다.

```powershell
node load-tests/k6/jiwon/tools/fixture.mjs recover-cleanup `
  --recovery build/k6/room/<run-id>/<fixture-id>/prepare-recovery.json
```

`run` 도중 `SIGINT` 또는 `SIGTERM`을 받으면 `run-manifest.json`은 `runState: "INTERRUPTED"`, `completed: false`, 종료 시각·수신 signal을 남긴다. 이 artifact와 일부 summary는 성능 근거로 쓰지 않으므로 `verify --stage after`는 `INVALID`로 끝난다. 중단 기록은 덮어쓰지 않는다. `cleanup --fixture <fixture.json>`으로 DB fixture를 안전하게 정리한 뒤 새 run ID로 `prepare`하여 다시 실행한다.

## 측정 결과 위치

승인해 보존한 ROOM k6 측정 문서는 [Jiwon k6 측정 문서](../../../docs/measurements/k6/jiwon/README.md)에서 관리한다. 현재 correctness 기준선은 그 목록의 final-05이며, 앞선 invalid·recovery campaign은 결론 계산에서 분리한다. 새 실행 결과를 정본으로 승격할 때도 같은 경로에 추가한다. 탐색·반복 실행의 원시 fixture, summary와 bundle은 `build/k6/room/<run-id>/`에만 둔다.

## 검증

```powershell
node --test load-tests/k6/jiwon/tests/fixture-model.test.mjs
node --test load-tests/k6/jiwon/tests/t3-execution-plan.test.mjs
node --test load-tests/k6/jiwon/tests/fixture-runner.test.mjs
node --test load-tests/k6/jiwon/tests/write-response-contract.test.mjs

Get-ChildItem load-tests/k6/jiwon -Recurse -File |
  Where-Object { $_.Extension -in '.js', '.mjs' } |
  ForEach-Object { node --check $_.FullName }

node scripts/docs/check-doc-links.mjs
```

k6가 설치된 실행 환경에서는 아래도 추가한다.

```powershell
k6 inspect load-tests/k6/jiwon/t1-cancel-promotion.js
k6 inspect load-tests/k6/jiwon/t2-concurrent-waitlist-registration.js
k6 inspect load-tests/k6/jiwon/t3-waitlist-cancel-race.js
k6 inspect load-tests/k6/jiwon/t4-last-seat-participation.js
k6 inspect load-tests/k6/jiwon/t5-room-detail-by-role.js
```
