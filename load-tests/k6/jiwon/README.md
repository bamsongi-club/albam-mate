# ROOM k6 부하테스트

이 디렉터리는 Jiwon이 소유하며, [#649](https://github.com/bamsongi-club/albam-mate/issues/649)의 ROOM 핵심 HTTP k6 시나리오 5종을 관리한다. 소스와 결과의 공통 배치 규칙은 [Load Tests](../../README.md)를 따른다.

테스트의 우선 목적은 동시성 오류·불변식 위반·공통 병목을 찾고, 개선 전후를 같은 조건으로 비교하는 것이다. `ROOM_CONCURRENT_MODIFICATION`은 계약된 재시도 소진 결과로 별도 기록하며, 그 비율 하나만으로 락 전략을 바꾸지 않는다.

## 소유 범위와 보존 위치

- 이 디렉터리는 ROOM 참가·취소·대기 등록·상세 조회 k6 시나리오, fixture 생성기, 사전·사후 DB 검증을 소유한다.
- 실제 fixture, 비밀번호·세션·CSRF, 원시 summary와 실행 bundle은 Git에 추적하지 않는 `build/k6/room/<run-id>/<fixture-id>/` 아래에 둔다.
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

## 실행 bundle 계약

`tools/fixture.mjs prepare`는 DB에 연결하거나 `psql`을 실행하지 않는다. 각 실행의 ROOM·사용자·참가·대기 계획, 선택한 k6 entry와 그 전체 `lib/` import closure, bundle 전용 CLI·모델 소스, 그리고 SQL만 버전 있는 실행 bundle로 렌더한다. run ID·scenario·입력 조합마다 새 bundle을 만들며 같은 bundle은 덮어쓰지 않는다.

bundle 정본은 Git에 추적하지 않는 `build/k6/room/<run-id>/<fixture-id>/`이다. infra는 이 경로를 영구 결과 저장소로 사용하지 않고, 원격에서 만든 원시 파일을 이 bundle의 정해진 이름으로 전달만 한다.

| 산출물 | 생성자 | 용도 |
| --- | --- | --- |
| `manifest.json` | 앱 `prepare` | `schemaVersion=1`과 고정 artifact 경로를 선언하는 실행 계약 |
| `scenario.js`, `lib/room-k6.js`, `lib/read-execution-options.mjs`, `lib/write-options.mjs`, `lib/t3-execution-plan.mjs` | 앱 `prepare` | 선택한 T1~T5의 실행 가능한 k6 소스와 전체 import closure |
| `tools/fixture.mjs`, `tools/fixture-model.mjs` | 앱 `prepare` | checkout과 독립적으로 `validate`·`hydrate`·`diagnose`·`aggregate`·앱 소유 cleanup을 실행하는 정확한 CLI·모델 소스 |
| `fixture-plan.json` | 앱 `prepare` | DB ID가 없는 fixture 계획 |
| `prepare.sql`, `resource-query.sql` | 앱 `prepare` | infra가 대상 DB에 적용·조회할 SQL. `prepare.sql`에는 bcrypt hash가 포함될 수 있다 |
| `resource-output.json` | infra | `resource-query.sql`의 원시 JSON stdout 결과 |
| `fixture.json`, `snapshot.sql`, `cleanup.sql` | 앱 `hydrate` | 실제 DB ID가 채워진 k6 입력, snapshot·정리 SQL |
| `before-snapshot.json`, `after-snapshot.json` | infra | `snapshot.sql`의 원시 JSON stdout 결과 |
| `k6-summary.json` | infra | 원격 k6의 `--summary-export` 원시 결과 |
| `k6-console.log` | infra | k6 stdout·stderr를 보존한 원시 console 로그 |
| `infra-execution.json` | infra | schemaVersion=1인 실행 전달 metadata. PASS/FAIL·metric·diagnosis를 담지 않는다 |
| `cloudwatch/` | infra | CloudWatch collector가 회수한 원시 결과 디렉터리 |
| `before-diagnosis.json`, `after-diagnosis.json` | 앱 `diagnose` | snapshot과 summary를 해석한 판정 결과 |
| `final-result.json` | 앱 `aggregate` | 진단 결과와 원시 infra 전달 metadata를 묶는 최종 앱 산출물 |

`manifest.json`의 파일명·경로는 계약의 일부다. clean Git 작업 트리에서 만든 bundle만 `sourceRevision`에 40자리 Git `HEAD`와 `sourceDirty: false`를 기록한다. dirty이거나 Git 상태를 검증할 수 없는 작업 트리에서 만든 개발용 bundle은 `sourceRevision: null`, `sourceDirty: true`로 기록해 uncommitted 소스를 `HEAD`라고 주장하지 않는다. 두 경우 모두 `sourceHashes`에는 k6 entry·모든 `lib/` import closure·bundle CLI·모델 소스의 SHA-256을, `artifactHashes`에는 `fixture-plan.json`, `private/prepare-provenance.json`, `prepare.sql`, `resource-query.sql`의 SHA-256을 기록한다. bundle CLI는 실행 전에 이 hash를 검증하므로 infra는 mutable app checkout을 실행하거나 checkout의 소스를 추측해 바꾸지 않는다. 앱 `validate`는 dirty provenance bundle도 검증할 수 있지만, infra의 `RELEASE_SHA` gate는 immutable 40자리 `sourceRevision`이 없는 bundle을 배포 실행 대상으로 받아들이지 않는다. `validate`·`hydrate`·`diagnose`·`aggregate`는 반드시 bundle 안의 `node tools/fixture.mjs`로 실행한다.

infra는 시나리오 의미나 SQL을 해석하지 않고 이 artifact만 실행·전달한다. `infra-execution.json`은 `{ schemaVersion: 1, runId, fixtureId, stackId, targetHttpsUrl, applicationRevision, startedAt, finishedAt, phases: { prepare, resourceQuery, beforeSnapshot, k6, afterSnapshot }, k6Version?, t5ReadOptions? }`의 원시 전달 metadata다. `applicationRevision`은 실제 대상 애플리케이션의 40자리 Git SHA다. T5에서는 infra가 bundle CLI `execution-options --bundle`의 `t5ReadOptions`와 `k6Environment`를 해석·기본값 부여 없이 그대로 k6와 metadata로 전달한다. 각 phase에는 `exitCode`만 기록하며, PASS/FAIL·metric·diagnosis 같은 도메인 판정을 넣지 않는다. CloudWatch collector는 이 파일의 `stackId`와 `startedAt`·`finishedAt` 창만 사용해 원시 결과를 `cloudwatch/`에 둔다.

## SQL 원시 결과 전달

`resource-query.sql`과 `snapshot.sql`의 JSON 결과는 아래 정확한 명령으로만 생성한다.

```text
psql -X --no-psqlrc -v ON_ERROR_STOP=1 -q -A -t -f <sql>
```

infra는 이 명령의 **stdout만** 수정·trim·재직렬화하지 않은 단일 JSON 값으로 보존한다. `resource-query.sql` stdout은 `resource-output.json`, `snapshot.sql` stdout은 각각 `before-snapshot.json`과 `after-snapshot.json`이 된다. SQL stderr는 bcrypt hash·SQL literal을 포함할 수 있으므로 bundle이나 앱 `build/`로 회수하지 않는다. infra는 원격 임시 위치에서 `no_log`로만 처리하고 실행 뒤 삭제한다. `prepare.sql`은 fixture 적재 SQL이므로 JSON stdout 전달 대상이 아니다.

`k6-summary.json`, `k6-console.log`, `infra-execution.json`, `cloudwatch/`도 도메인 판정이 없는 원시 전달 artifact다. 앱의 `diagnose`만 summary·snapshot을 해석해 PASS/FAIL/INVALID를 만든다. 그 뒤 앱 `aggregate`는 두 diagnosis JSON과 `infra-execution.json`을 그대로 묶고 `cloudwatch/`의 존재·파일 목록·크기·수정 시각만 기록한다. `aggregate`는 metric·threshold·PASS/FAIL을 새로 판정하지 않는다.

`final-result.json`의 `aggregationStatus`는 테스트 결과가 아니라 입력 완결성이다. 필요한 diagnosis·infra metadata·CloudWatch raw 결과가 모두 구조적으로 유효할 때만 `COMPLETE`이고, 파일이 없으면 `INCOMPLETE`, JSON·bundle identity·infra phase 형식이 다르면 `INVALID_INPUT`이다. 실제 테스트 판정은 복사된 `diagnoses.before.status`와 `diagnoses.after.status`를 확인한다.

`hydrate`, `diagnose`, `aggregate`, `cleanup`은 `--bundle`만 받고 run ID나 fixture ID를 별도로 재정의하지 않는다. cleanup은 앱이 소유한다. `cleanup.sql`은 검토·전달용 artifact이며 CLI가 파일 경로를 그대로 실행하지 않는다. `cleanup --bundle`은 **psql 전에** recovery와 같은 preflight(bundle 경로·manifest identity·source/plan/SQL hash·canonical fixture plan·`resource-query.sql`)와 `fixture.json`·원시 `resource-output.json`의 일치를 검증하고, `buildCleanupSql(fixture)`를 메모리에서 다시 생성해 stdin으로 실행한다. 명시적으로 승인된 원격 cleanup 전송에는 `cleanup-sql --bundle`을 사용한다. 이 명령도 같은 검증 뒤 재생성한 SQL만 stdout으로 내보내며, `psql`을 실행하거나 `cleanup.sql` artifact를 읽지 않는다. infra는 이 stdout을 전송만 할 수 있고 자동 cleanup이나 cleanup 판정 로직을 만들지 않는다. cleanup SQL은 broad prefix 삭제나 `TRUNCATE`를 쓰지 않으며, fixture ROOM에 비-fixture 사용자의 파생 행이 섞였으면 삭제를 중단한다.

`prepare.sql`은 transaction으로 fixture를 적재한다. 커밋 뒤 `resource-query.sql`의 전달만 실패해 `fixture.json`과 일반 cleanup을 만들지 못했어도 infra는 cleanup하지 않는다. 운영자가 명시적으로 `recover-cleanup --bundle <bundle>`을 호출할 때만 앱 CLI가 manifest·plan·SQL 무결성을 검증하고, 계획에 고정된 사용자 이메일·ROOM 제목으로 정확한 ID를 다시 조회한 뒤 메모리에서 만든 cleanup SQL을 실행한다. 필요한 fixture가 하나라도 조회되지 않으면 cleanup을 실행하지 않는다.

## 전제와 환경 변수

- k6는 load generator에, PostgreSQL `psql`은 DB SQL을 실행하는 환경에 있어야 한다.
- 공식 실행은 고객 데이터가 없는 Terraform 부하 환경 또는 동등한 전용 환경에서만 한다.
- fixture의 `start_at`은 미래로 고정한다. ROOM 상세 조회의 상태 보정이 측정 중 데이터를 바꾸지 않게 하기 위해서다.

| 변수 | 기본값 | 용도 |
| --- | --- | --- |
| `ALBAM_MATE_TARGET_URL` | 없음 | 대상 서버 URL |
| `ALBAM_MATE_RUN_ID` | 없음 | fixture `--run-id`와 같은 실행 식별자 |
| `ROOM_K6_FIXTURE_PASSWORD` | 없음 | fixture 계정 로그인 비밀번호 |
| `ROOM_K6_FIXTURE_PASSWORD_HASH` | 없음 | 같은 비밀번호의 `{bcrypt}$` hash. bundle `prepare`에서만 사용 |
| `ROOM_K6_FIXTURE` | 없음 | `hydrate` 뒤 bundle 안에 생긴 `fixture.json` 경로 |
| `ROOM_K6_SESSION_WARMUP_SECONDS` | `15` | 쓰기 시나리오 로그인 후 공통 시작 전 대기 시간 |
| `ROOM_K6_ROUND_INTERVAL_SECONDS` | `20` | 쓰기 시나리오 wave 간격 |
| `ROOM_K6_READ_VUS` | `10` | T5 동시 VU 수 |
| `ROOM_K6_READ_DURATION_SECONDS` | `60` | T5 측정 창 |
| `ROOM_K6_READ_THINK_TIME_MS` | `0` | T5 요청 사이 think time |
| `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` | cleanup 실행 환경 | 앱 소유 `cleanup` 또는 명시적 `recover-cleanup`을 직접 실행할 때만 필요 |

`ROOM_K6_FIXTURE_PASSWORD`와 `ROOM_K6_FIXTURE_PASSWORD_HASH`는 같은 private test password여야 하며 Git에 저장하지 않는다.

## 실행

저장소 루트에서 실행한다. 아래는 T1 hot·동시 8명의 bundle을 만드는 예시다. 이 단계는 DB를 변경하지 않는다.

```powershell
$runId = 'room-t1-hot-8-01'
$env:ALBAM_MATE_RUN_ID = $runId
$env:ALBAM_MATE_TARGET_URL = 'https://<private-target>'
$env:ROOM_K6_FIXTURE_PASSWORD = '<private-password>'
$env:ROOM_K6_FIXTURE_PASSWORD_HASH = '<private-bcrypt-hash>'

$bundle = node load-tests/k6/jiwon/tools/fixture.mjs prepare `
  --scenario t1 --run-id $runId --profile stress --mode hot --concurrency 8 |
  ConvertFrom-Json
```

그 다음 순서는 manifest의 `executionProtocol`과 같이 고정된다. infra는 실행 결정을 내리지 않고 앱 CLI의 종료 코드와 artifact 경로만 따른다.

1. infra가 대상 DB를 변경하기 전에 bundle 안의 앱 CLI를 실행한다. `validate --for-execution`은 DB 연결·`psql`·cleanup 없이 manifest source hash, plan·SQL artifact hash, canonical fixture plan, `resource-query.sql`, 그리고 새 bundle 실행 상태를 검증하고 `bundlePath`·`runId`·`fixtureId`만 JSON stdout으로 돌려준다. **exit 0이 아니면 infra는 `prepare.sql`을 실행하지 않는다.**

   ```powershell
   node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') validate --for-execution --bundle $bundle.bundlePath
   ```

2. infra가 `$bundle.bundlePath/prepare.sql`을 전용 대상 DB에 적용하고 `$bundle.bundlePath/resource-query.sql`의 JSON 결과를 같은 bundle의 `resource-output.json`으로 전달한다.
3. 앱이 DB에 연결하지 않고 fixture를 완성한다.

   ```powershell
   $hydrated = node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') hydrate --bundle $bundle.bundlePath |
     ConvertFrom-Json
   $env:ROOM_K6_FIXTURE = $hydrated.fixturePath
   ```

4. infra가 `snapshot.sql`의 원시 결과를 `before-snapshot.json`으로 전달하고, 앱이 사전 조건을 판정한다.

   ```powershell
   node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') diagnose --bundle $bundle.bundlePath --stage before
   ```

5. `diagnose --stage before`가 **exit 0**일 때만 infra가 다음 k6 단계를 시작한다. nonzero이면 infra는 k6를 시작하지 않으며, 왜 중단할지 판정하거나 결과를 바꾸지 않는다.
6. infra는 T5면 bundle CLI의 옵션 정규화 결과만 조회하고 그대로 전달한다. 그 뒤 같은 bundle의 `scenario.js`, `lib/`와 `fixture.json`을 load generator에 전달하고, `k6-summary.json`, `k6-console.log`, `infra-execution.json`, `cloudwatch/` 원시 artifact를 bundle에 돌려놓는다. 실행 명령은 `k6 run --summary-export k6-summary.json scenario.js`다. infra가 정한 `ALBAM_MATE_RUN_ID`는 manifest와 같은 run ID여야 한다.

   ```powershell
   node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') execution-options --bundle $bundle.bundlePath
   ```
7. k6가 nonzero로 끝나도 infra는 가능한 `k6-summary.json`·`k6-console.log`·`infra-execution.json`·`cloudwatch/`를 그대로 보존하고, 같은 `snapshot.sql`의 원시 결과를 `after-snapshot.json`으로 전달한다. 이 단계는 k6 exit code로 생략하지 않는다.
8. 앱이 summary·snapshot을 함께 판정한다.

   ```powershell
   node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') diagnose --bundle $bundle.bundlePath --stage after
   ```

9. infra 반환과 사후 진단이 끝난 뒤 앱이 최종 artifact를 만든다. 사후 진단이 `FAIL` 또는 `INVALID`여도 원시 결과를 보존하려면 이 단계를 명시적으로 계속 실행한다. `aggregate`는 입력이 완결될 때만 exit 0이고, 누락·형식 오류는 `final-result.json`에 남긴 뒤 exit 2다.

   ```powershell
   node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') aggregate --bundle $bundle.bundlePath
   ```

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

`stress`의 기본값은 독립 ROOM 5개를 같은 동시성으로 연속 wave 실행하는 것이다. `spike`의 기본값은 독립 ROOM 1개에 즉시 한 wave를 보낸다. T5는 VU마다 측정 창 전체를 한 번 실행한다.

T5 role×scale 여섯 bundle을 같은 run ID로 완료한 뒤, 앱은 원시 `infra-execution.json`의 전달값과 각 `final-result.json`의 완결성만 비교한다. 이 단계도 k6를 실행하지 않는다.

```powershell
node load-tests/k6/jiwon/tools/fixture.mjs compare-t5 --run-id $runId
```

## 결과 확인

사후 진단은 HTTP 응답 분류와 원시 DB snapshot을 함께 판정한다. `diagnose`는 DB 연결이나 `psql` 실행을 하지 않는다.

| 상태 | 의미 |
| --- | --- |
| `PASS` | HTTP 응답 분류, 시나리오별 DB 불변식, hard correctness gate를 통과 |
| `FAIL` | 예상 밖 응답·5xx·payload 불일치·FIFO/정원/중복/무변경 gate 위반 |
| `INVALID` | fixture 사전 조건 또는 필수 artifact가 부족해 결과를 성능 근거로 쓸 수 없음 |

`room_success`, `room_created`, `room_business_failures`, `room_concurrent_failures`, `room_unexpected_4xx`, `room_server_failures`, `room_contract_failures`, `room_request_duration`, `room_start_skew_ms`를 k6 summary에서 확인한다.

첫 기준선의 p50/p95/p99/RPS/409 비율은 관찰값이다. DB CPU·connection·lock wait·query call/time과 application retry log는 같은 측정 창의 승인된 관측 source에서 별도로 수집한다. production 락 전략은 이 스크립트가 바꾸지 않는다.

분석과 최종 artifact 생성이 끝난 뒤에만 앱 소유 CLI로 같은 bundle을 정리한다. 이 명령은 대상 DB에 직접 연결할 수 있는 실행 환경에서만 사용한다. infra는 자동으로 호출하지 않는다.

```powershell
node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') cleanup --bundle $bundle.bundlePath
```

명시적으로 승인된 원격 cleanup 전송에는 아래처럼 SQL만 생성한다. 이 명령 자체는 DB에 연결하지 않는다.

```powershell
node (Join-Path $bundle.bundlePath 'tools/fixture.mjs') cleanup-sql --bundle $bundle.bundlePath
```

## 검증

```powershell
node --test load-tests/k6/jiwon/tests/fixture-model.test.mjs load-tests/k6/jiwon/tests/fixture-bundle.test.mjs load-tests/k6/jiwon/tests/fixture-runner.test.mjs load-tests/k6/jiwon/tests/t3-execution-plan.test.mjs

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
