# 게임 카탈로그 검수·적재

이 절차는 [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md)와 [ADR-0050](../adr/game/0050-game-metadata-catalog-and-filters.md)의 BGG 기준 snapshot, 출처 기록, 선검증과 transaction 단위 `UPSERT` 규칙을 실행한다. 원본과 생성 산출물은 저장소에 커밋하지 않고, 출처 manifest와 검수 기록만 보관한다.

입력 JSON의 `supported_player_count`는 게임 규칙상 플레이 가능한 인원 범위다. 이용자 평가 기반 추천 인원·최적 인원과는 구분한다.

## 1. 초안 검수

manifest 없이 실행해 파일 체크섬, BGG 매핑과 품질 경고를 먼저 확인한다. 검수가 끝나기 전에는 `quality-report.json`만 생성되고 종료 코드는 실패다.

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --out build/game-catalog/2026-07-24-draft
```

## 2. 출처·검수 기록

[manifest 초안](../game-catalog/2026-07-24-source-manifest.draft.json)에 다음 근거를 기록한다.

- 각 파일의 실제 출처, 취득 방식·시각과 이용 조건
- 필드별 입력 출처와 갱신 규칙
- `selectionRules`의 선택·제외 규칙과 `versionRules`의 본판·확장·변형 구분 규칙
- `selection`의 원본 후보 행 수·포함 행 수·제외 건수와 각 제외 항목의 식별자·사유
- 변환 도구가 포함된 전체 Git commit SHA
- 검수일·검수자
- 사람이 확인하고 수용한 품질 경고 코드

여기서 판본은 같은 게임의 개정·재판 등 출시 형태를 뜻하며 확장판과 구분한다.

`TODO`, 체크섬 불일치, 미승인 상태 또는 미수용 경고가 하나라도 있으면 적재 산출물을 만들지 않는다.

`selection`은 다음 정합성을 만족해야 한다.

- `candidateRows = includedRows + excludedRows`
- `excludedRows`와 `exclusions.length`가 같고, 각 항목에 `identifier`와 `reason`이 있어야 한다.
- `includedRows`가 실제 `service-catalog.json` 행 수와 같아야 한다.

이 정보는 `quality-report.json`에도 그대로 기록되어 원본 후보에서 최종 적재 행까지의 차이를 추적한다.

## 3. 적재 산출물 생성

```sh
node scripts/game-catalog/prepare-game-catalog.mjs \
  --games /path/to/games.json \
  --ranks /path/to/boardgames_ranks07-24.csv \
  --manifest /path/to/approved-manifest.json \
  --out build/game-catalog/approved
```

성공하면 아래 파일을 만든다.

- `quality-report.json`: 입력·결과 행 수, 체크섬, 오류와 승인된 경고
- `service-catalog.json`: 내부 `id`를 제외하고 `bgg_id`로 정규화한 서비스 데이터
- `upsert-games.sql`: 한 트랜잭션에서 실행하는 `bgg_id` 기준 `UPSERT`
- `mechanismCatalog`이 있는 manifest라면 `service-mechanism-catalog.json`: 검수된 메커니즘 목록
- `mechanismCatalog`이 있는 manifest라면 `upsert-game-mechanisms.sql`: 공개 메커니즘과 게임 관계를 승인 스냅샷으로 수렴시키는 SQL
- `metadataCatalog`이 있는 manifest라면 `service-game-metadata.json`: 카테고리·테마·추천/베스트 인원 관계의 정규화 결과
- `metadataCatalog`이 있는 manifest라면 `upsert-game-metadata.sql`: 고정 카테고리, 검수된 테마, 게임 관계와 인원 선호를 승인 snapshot으로 수렴시키는 SQL

`upsert-games.sql`은 기존 내부 `id`와 `created_at`을 유지하며, 새 입력에서 빠진 기존 게임을 삭제하지 않는다.
`upsert-game-mechanisms.sql`은 게임 내부 ID를 해석해야 하므로 반드시 `upsert-games.sql` 다음에 실행한다. 승인 관계의 게임이나 메커니즘을 해석하지 못하면 전체 트랜잭션을 롤백한다.
`upsert-game-metadata.sql`도 반드시 `upsert-games.sql` 다음에 실행한다. 승인 category/theme 관계의 게임이나 테마를 해석하지 못하면 category·theme·인원 선호·최소 연령 적재 전체를 롤백한다. BGG XML의 `minage`는 양의 PostgreSQL `INTEGER`만 저장하며 누락·`0`은 `NULL`로 재적재한다. 새 snapshot에 없다는 이유로 GAMES 행을 삭제하지 않는다. `quality-report.json`의 `testOnly`가 `true`인 산출물은 이 운영 경로로 실행하지 않는다.

## 4. PostgreSQL 적재

검수 보고서의 상태가 `ready`일 때만 대상 데이터베이스를 명시해 실행한다.

```sh
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file build/game-catalog/approved/upsert-games.sql

# mechanismCatalog이 있는 승인 배치에서만 이어서 실행한다.
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file build/game-catalog/approved/upsert-game-mechanisms.sql

# metadataCatalog이 있는 승인 배치에서만 이어서 실행한다.
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file build/game-catalog/approved/upsert-game-metadata.sql
```

어느 행에서든 실패하면 해당 SQL의 `COMMIT`에 도달하지 않아 그 트랜잭션 전체가 롤백된다. 적재 전후 행 수, 기존 `bgg_id`의 내부 `id` 유지와 반복 실행 결과를 확인한다.

현재 Issue #351 승인 배치는 공개 메커니즘 189개와 관계 13,263개여야 한다. 두 SQL 실행 후 아래 결과가 각각 `189`, `13263`인지 확인한다. 이후 배치는 승인된 `quality-report.json`의 `mechanismCatalog` 건수와 대조한다.

```sh
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --tuples-only \
  --command "SELECT COUNT(*) FROM game_mechanisms WHERE is_public = true;" \
  --command "SELECT COUNT(*) FROM game_mechanism_relations;"
```

## 5. 17만 게임 메타데이터 batch

운영 metadata는 성능 fixture에서 만들지 않는다. 승인된 170,000개 BGG ID, 순위 CSV, BGG XML snapshot, 한글 테마 사전과 metadata manifest를 같은 batch로 보관한다.

수집기는 최대 20개 ID씩 호출하고, 완료된 batch checksum은 다시 요청하지 않는다. manifest에는 요청 ID·응답 ID 집합, HTTP status, bytes, raw XML SHA-256, 취득 시각만 기록한다. Bearer token은 macOS Keychain에서 실행 시에만 읽고 터미널 출력·오류·manifest·Git에 남기지 않는다.

### 외부 입력 계약

메타데이터 변환기는 저장소 밖 `metadata-input-manifest.json` 하나만 입력으로 받는다. 경로는 팀 공유 저장소 또는 로컬 절대 경로여도 되지만, 그 파일과 원본·산출물은 Git에 넣지 않는다.

```json
{
  "schemaVersion": 1,
  "approved": true,
  "testOnly": false,
  "games": { "path": "/path/to/games.json", "sha256": "<64-hex>", "rows": 170000 },
  "ranks": { "path": "/path/to/boardgames_ranks07-24.csv", "sha256": "<64-hex>", "rows": 179329 },
  "xmlSnapshot": {
    "rawDirectory": "/path/to/raw",
    "manifestPath": "/path/to/manifest.json",
    "manifestSha256": "<64-hex>"
  },
  "themeDictionary": { "path": "/path/to/bgg-theme-ko-map.json", "sha256": "<64-hex>" },
  "reviewedBy": "<reviewer>",
  "reviewedAt": "2026-08-05T00:00:00Z"
}
```

`bgg-theme-ko-map.json`은 아래처럼 BGG 원본 ID·영문명·검수 한글명을 함께 둔다. ID만 맞고 영문명이 달라진 행, 빈 한글명, 중복 ID·영문명·한글명은 오류다. 변환기는 이 사전에 없는 XML 테마를 자동 번역하거나 누락한 채 통과시키지 않는다.

```json
{
  "schemaVersion": 1,
  "entries": [
    { "bggThemeId": 1016, "nameEn": "Science Fiction", "nameKo": "SF 공상 과학" }
  ]
}
```

실행은 다음과 같다. `--out`은 저장소 밖 빈 배치 디렉터리여야 하며, 실패한 품질 게이트에서는 `quality-report.json`만 남긴다.

```sh
node scripts/game-catalog/prepare-game-metadata-catalog.mjs \
  --input-manifest /path/to/metadata-input-manifest.json \
  --out /path/to/approved-game-metadata-batch
```

metadata 품질 게이트는 아래를 모두 만족해야 한다.

- 대상 BGG ID 170,000개와 응답 ID 집합이 같고 중복이 없다.
- CSV의 실제 `<subdomain>_rank` 열(예: `strategygames_rank`)의 양수 값만 고정 8개 category relation으로 만들며, 8개 source 열의 누락·0·음수 rank를 추정하지 않는다.
- 모든 공개 theme에 BGG ID·영문명·안정 code·검수 한글명이 있고, theme와 relation 중복이 없다. theme code는 영문명을 ASCII UPPER_SNAKE_CASE로 정규화한 `<BASE>_BGG_<bggThemeId>`라서 snapshot 순서와 증분 적재에 따라 바뀌지 않는다.
- suggested_numplayers의 recommended/best 판정, N+ 확장, 동률·poll 누락·잘못된 label 처리가 승인된 규칙과 일치한다.
- BGG XML `minage`의 양의 `INTEGER`만 사용하고, 누락·`0`은 `NULL`이며 음수·비정수·PostgreSQL `INTEGER` 범위 밖 값은 품질 게이트에서 차단한다.
- quality report는 최소 연령의 채움·누락 건수와 입력·snapshot·산출물 checksum을 함께 기록한다.
- manifest의 입력·snapshot·한글 사전·산출물 checksum과 행 수가 quality report에 다시 기록된다.

어느 게이트라도 실패하거나 manifest가 승인되지 않으면 quality report만 생성하고 service JSON·UPSERT SQL을 만들지 않는다.

`testOnly: true`는 PostgreSQL 검증 fixture에만 허용한다. 생성된 service JSON과 quality report에도 `testOnly: true`가 보존되고, 생성 SQL은 기본 세션에서 `albam_mate.allow_test_only_metadata_import`가 설정되지 않으면 즉시 실패한다. 따라서 운영 배치는 반드시 `testOnly: false`(또는 생략)인 승인 산출물만 위 운영 명령으로 실행한다.

테스트 전용 SQL을 PostgreSQL 검증에서 실행할 때만 아래처럼 명시적으로 허가한다.

```sh
PGOPTIONS='-c albam_mate.allow_test_only_metadata_import=true' \
  psql "$DATABASE_URL" --set ON_ERROR_STOP=on \
  --file /path/to/test-only/upsert-game-metadata.sql
```

### 17만 행 게임 기본 정보·성능 fixture 계약

`games-170k.performance.json`과 그 `source-manifest.performance.json`은 게임 본문 170,000행 적재와 PostgreSQL 성능 테스트에 쓴다. 관계·메타데이터 필드는 합성값이므로 운영 적재에는 사용하지 않고, 승인된 순위 CSV·BGG XML snapshot·한글 사전으로 별도 산출한다. 테스트는 manifest의 `rows=170000`와 fixture SHA-256을 먼저 검증하고, category 관계는 같은 manifest가 가리키는 실제 순위 CSV의 양수 rank에서만 만든다. `issue420.fixture`가 없으면 JUnit 조건으로 성능 클래스를 건너뛰므로, 외부 입력이 없는 기본 `postgresTest`는 이 fixture를 요구하거나 실패하지 않는다.

170,000개 ID의 전체 XML snapshot과 한글 사전이 아직 없는 상태에서 테마·추천/베스트 조합 성능을 측정할 때는 테스트 코드가 고정 seed로 만든 **성능 전용 관계 분포**만 사용한다. 이 분포는 report에 `performanceFixtureRelations=true`로 남기며 service JSON·UPSERT SQL·운영 metadata manifest에 전달하면 실패해야 한다. 따라서 T10은 조회 경로와 실행 계획의 재현 근거이고, 운영 원본 사실을 주장하지 않는다.

```sh
JAVA_TOOL_OPTIONS="-Dissue420.fixture=/path/to/games-170k.performance.json -Dissue420.fixtureManifest=/path/to/source-manifest.performance.json -Dissue420.rankCsv=/path/to/boardgames_ranks07-24.csv -Dissue420.performanceReport=/path/to/game-metadata-performance-report.json" \
  ./gradlew postgresTest \
  --tests "cloud.bamsongi.albammate.game.GameMetadataSearchPerformancePostgresTest.십칠만건_fixture에서_대표조합의_결과_전체건수_실행계획과_시간을_기록한다" \
  --rerun --fail-fast
```

report의 `pageAndCountElapsedMs`는 page 조회와 total count 조회만 잰 시간이고, `explainAnalyzeElapsedMs`는 `EXPLAIN ANALYZE`를 별도로 실행한 시간이다. 두 값은 합산하거나 서로의 응답 시간으로 해석하지 않는다.

## 검수·측정 기록 찾기

이 가이드는 실행 절차를 소유하고, 날짜별 문서는 당시 입력·품질·측정 증거를 보존한다. 현재 규칙을 찾을 때는 이 가이드와 승인 ADR을 먼저 보고, 특정 배치의 근거가 필요할 때 아래 기록을 연다.

| 기록 | 확인할 내용 |
| --- | --- |
| [2026-07-24 게임 카탈로그 입력 검수](../game-catalog/2026-07-24-input-review.md) | 최초 입력 매핑과 검수 결과 |
| [2026-08-01 BGG 상세 데이터 취득·검증](../game-catalog/2026-08-01-bgg-detail-acquisition.md) | 상세 원본 취득 범위와 체크섬 |
| [2026-08-03 P1 검색 수치 품질](../game-catalog/2026-08-03-p1-search-numeric-fields-quality-report.md) | 최소 연령·인원·시간 수치의 품질 게이트 |
| [2026-08-04 P1 게임 메커니즘 품질](../game-catalog/2026-08-04-p1-game-mechanism-quality-report.md) | 공개 메커니즘과 게임 관계 적재 증거 |
| [2026-08-05 게임 메타데이터 필터 성능](../game-catalog/2026-08-05-game-metadata-filter-performance.md) | 17만 건 검색 fixture와 실행 계획·시간 |

## 검증 명령

```sh
node --test scripts/game-catalog/prepare-game-catalog.test.mjs
node --test scripts/game-catalog/game-metadata-catalog.test.mjs
./gradlew postgresTest --no-daemon --stacktrace
```

`postgresTest`는 Docker가 필요하다. Docker 데몬이 없어서 Testcontainers가 시작되지 못한 경우에는 테스트 실패가 아니라 실행 환경 제약으로 별도 기록한다.
