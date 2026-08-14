# 게임 카탈로그 검수·적재

이 절차는 [ADR-0015](../adr/game/0015-bgg-baseline-team-collected-game-list.md), [ADR-0050](../adr/game/0050-game-metadata-catalog-and-filters.md)와 [ADR-0060](../adr/game/0060-approved-catalog-ai-embedding-scope.md)의 BGG 기준 snapshot, 출처 기록, 선검증과 transaction 단위 `UPSERT` 규칙을 실행한다. 원본과 생성 산출물은 저장소에 커밋하지 않고, 출처·승인 manifest와 각 배치의 `quality-report.json`을 보관한다.

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
- AI·embedding을 사용하는 배치라면 `releaseId`·`datasetId`, `approvedFields`, `approvedProcessingScopes`, `approval.references`, model/provider·index version과 `sources`·`outputs`의 checksum·행 수

여기서 판본은 같은 게임의 개정·재판 등 출시 형태를 뜻하며 확장판과 구분한다.

`TODO`, 체크섬 불일치, 미승인 상태 또는 미수용 경고가 하나라도 있으면 적재 산출물을 만들지 않는다. AI·embedding 산출을 함께 만드는 배치는 승인 manifest의 release·필드·가공 allowlist가 없거나 실제 입력과 다를 때 색인과 적재를 모두 차단해야 한다. `prepare-game-catalog.mjs`는 이 gate를 연결해 생성 전에 실제 입력과 `service-catalog.json`·`upsert-games.sql`의 선언 checksum·행 수를 대조한다.

`selection`은 다음 정합성을 만족해야 한다.

- `candidateRows = includedRows + excludedRows`
- `excludedRows`와 `exclusions.length`가 같고, 각 항목에 `identifier`와 `reason`이 있어야 한다.
- `includedRows`가 실제 `service-catalog.json` 행 수와 같아야 한다.

이 정보는 `quality-report.json`에도 그대로 기록되어 원본 후보에서 최종 적재 행까지의 차이를 추적한다.

### 2-1. AI·embedding 승인 범위

[BGG 승인 데이터셋의 AI·embedding 사용 범위](../game-catalog/2026-08-14-bgg-ai-embedding-approval.md)에 따라 정책 승인된 하나의 catalog release만 AI 입력·embedding에 사용할 수 있다. 구체 승인 manifest를 등록하고 검증하기 전에는 이 절차로 BGG 기반 AI·embedding 산출을 실행하지 않는다.

> 구현 상태: `prepare-game-catalog.mjs`는 `validateApprovedReleaseManifest`를 호출해 manifest 필수값과 입력·service catalog·UPSERT 산출물의 실제 checksum·행 수를 검증한다. 이 runner는 embedding 파일을 생성하지 않으므로 외부 embedding/index runner의 실제 embedding 산출물 대조는 별도 연결이 필요하다. 현재 저장소에 구체 승인 manifest가 없으므로 정책 승인만으로 실행 가능한 release로 간주하지 않는다.

- `approved: true`, `testOnly: false`인 manifest와 정확히 일치하는 입력·결과 checksum·행 수를 확인한다.
- manifest의 `approvedFields`에 있는 필드만 읽고, `approvedProcessingScopes`에 없는 번역·요약·재작성·파생 가공은 실행하지 않는다.
- 검색용 embedding은 결정적 `search_text` 조립 규칙과 model/provider·index version을 quality report에 남긴다. raw XML·allowlist 밖 원문은 별도 승인 없이는 모델·외부 provider·검색 index로 보내지 않는다.
- 사용자 query·ID·세션·ROOM·채팅·prompt와 provider 응답 원문은 catalog 산출물·검색 index·중앙 로그에 저장하지 않는다.
- release·필드·가공 규칙·model/provider·보존 또는 공개 범위가 바뀌면 새 manifest와 재승인을 요구한다.

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

### RANK-02 인기 점수 배치

[ADR-0058](../adr/game/0058-external-ranking-and-popularity-sort.md)의 승인 manifest를 사용해 BoardLife·BGG 순위와 1행 1 `bggId` score input을 검증하고, `games.popularity_score`를 갱신하는 SQL을 생성한다. raw source와 manifest는 저장소에 커밋하지 않는다.

```sh
node scripts/game-ranking/prepare-game-popularity-ranking.mjs \
  --manifest /path/to/approved-ranking-manifest.json \
  --out build/game-ranking/approved
```

승인 manifest는 `schemaVersion: 1`, `status: approved`, `batchId`, BoardLife·BGG source의 `path`·`rows`·`sha256`, score input의 `path`·`rows`·`sha256`·`grain: 1 row per bggId`·`reviewRequiredRows: 0`을 포함해야 한다. 생성기는 rank 중복·결측·미매칭을 점수 규칙에 따라 처리하고 `quality-report.json`과 `upsert-game-popularity.sql`을 만든다. 승인되지 않은 manifest나 checksum·행 수가 맞지 않는 입력은 산출물을 차단한다.

```sh
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file build/game-ranking/approved/upsert-game-popularity.sql
```

이 SQL은 전체 `GAME_FOCUSED` 방을 집계하면서 `CANCELED`만 제외하고, 외부 점수와 함께 `popularity_score`를 한 트랜잭션에서 갱신한다. 애플리케이션 요청 중 BoardLife·BGG를 직접 조회하지 않는다.

### RANK-02 보존·검증·복구

카탈로그 운영 담당자는 승인 배치마다 적재 전에 이전 점수와 산출물의 증적을 저장한다. 기본 보존 기간은 최근 3개 배치와 90일 중 긴 기간이며, 갱신 주기는 BoardLife·BGG 승인 snapshot이 바뀔 때마다로 한다. 증적 디렉터리는 접근이 제한된 운영 저장소의 `rank-02/<batchId>/`를 사용하고, 아래 파일을 함께 보관한다.

- 승인 manifest와 raw source 참조
- `quality-report.json`과 `upsert-game-popularity.sql`
- 위 파일의 SHA-256 목록
- 적재 직전 `games.id,popularity_score` snapshot CSV
- 실행 후 검증 쿼리 결과와 복구 이력

실행 전 snapshot과 생성 산출물을 보존하고 checksum을 기록한다.

```sh
BATCH_ID=2026-08-14-ranking-v1
EVIDENCE_DIR=/secure/catalog-evidence/rank-02/$BATCH_ID
mkdir -p "$EVIDENCE_DIR"

cp /path/to/approved-ranking-manifest.json "$EVIDENCE_DIR/manifest.json"
cp build/game-ranking/approved/quality-report.json "$EVIDENCE_DIR/quality-report.json"
cp build/game-ranking/approved/upsert-game-popularity.sql "$EVIDENCE_DIR/upsert-game-popularity.sql"

psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --command "COPY (SELECT id, popularity_score FROM games ORDER BY id) TO STDOUT WITH CSV HEADER" \
  > "$EVIDENCE_DIR/popularity-score-before.csv"

sha256sum "$EVIDENCE_DIR"/* > "$EVIDENCE_DIR/SHA256SUMS"
```

`quality-report.json`의 `status`가 `approved`이고 `SHA256SUMS`가 manifest·report·SQL과 일치할 때만 SQL을 실행한다. 실행 후에는 전체 게임 수와 점수 범위를 기록하고, 범위를 벗어난 행이 0인지 확인한다.

```sh
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --command "SELECT count(*) AS total_games, min(popularity_score) AS min_score, max(popularity_score) AS max_score, count(*) FILTER (WHERE popularity_score < 0 OR popularity_score > 1) AS invalid_scores FROM games" \
  > "$EVIDENCE_DIR/post-check.txt"
```

검증 실패나 승인 오류가 발견되면 다음 명령으로 이전 snapshot을 복원한다. 복원 후 같은 post-check를 다시 실행하고, 배치 담당자가 복구 이력을 증적 디렉터리에 남긴다.

```sh
psql "$DATABASE_URL" --set ON_ERROR_STOP=on <<SQL
BEGIN;
CREATE TEMP TABLE popularity_score_restore (
    game_id BIGINT PRIMARY KEY,
    popularity_score DECIMAL(8, 6) NOT NULL
);
\\copy popularity_score_restore (game_id, popularity_score) FROM '$EVIDENCE_DIR/popularity-score-before.csv' WITH (FORMAT csv, HEADER true)
UPDATE games AS game
SET popularity_score = restore.popularity_score
FROM popularity_score_restore AS restore
WHERE game.id = restore.game_id;
COMMIT;
SQL
```

복구 책임자는 해당 승인 배치를 실행한 카탈로그 운영 담당자이며, 실행 전 snapshot 없이 전역 점수 SQL을 실행하지 않는다.

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

## 5. 17만 게임 메커니즘 batch

17만 게임의 BGG XML에서 메커니즘 관계를 생성할 때는 기존 `prepare-game-catalog.mjs`의 파일 기반 흐름과 별도의 입력·출력 경로를 사용한다. 기존 게임 산출 디렉터리 `build/game-catalog/approved`를 메커니즘 CLI의 `--out`으로 재사용하지 않는다. CLI는 부분 산출물 혼입을 막기 위해 비어 있지 않은 출력 디렉터리를 거부한다.

입력 manifest는 저장소 밖에 두고 `approved: true`, `testOnly: false`, `games`, `mechanismDictionary`, `xmlSnapshot`, `mechanismCatalog`를 포함해야 한다. `games`는 170,000행의 BGG ID, `mechanismDictionary`는 검수된 `description_ko`, `xmlSnapshot`은 batch별 요청·응답 ID와 raw XML manifest를 가리킨다. `mechanismCatalog`에는 `sourceReference`, `reviewedBy`, `reviewedAt`, `approvalScope`를 기록한다.

```sh
node scripts/game-catalog/prepare-game-mechanism-catalog.mjs \
  --input-manifest /path/to/mechanism-input-manifest.json \
  --out /path/to/approved-game-mechanism-batch
```

`/path/to/approved-game-mechanism-batch`는 기존 게임 산출 디렉터리와 분리된 빈 디렉터리여야 한다. 성공하면 `service-mechanism-catalog.json`, `upsert-game-mechanisms.sql`, `mechanism-quality-report.json`을 만든다. 품질 보고서는 아래 입력 식별자·검증 checksum·행 수와 승인 provenance를 보존하므로, SQL과 같은 배치 증적 보관 위치에 둔다.

- `inputs.manifest`: 승인 입력 manifest 경로와 SHA-256
- `inputs.games`: 게임 입력 경로·SHA-256·행 수
- `inputs.mechanismDictionary`: 메커니즘 사전 경로·SHA-256·행 수
- `inputs.xmlSnapshotManifest`: XML snapshot manifest 경로·SHA-256·게임 수·batch 수
- `provenance`: `mechanismInput`, `sourceReference`, `reviewedBy`, `reviewedAt`, `approvalScope`, `approvalReferences`
- `checks`와 `outputs`: 검증된 대상·snapshot 수와 생성 artifact/SQL SHA-256

실제 적재는 파일을 합쳐 덮어쓰지 않고, 기존 게임 SQL과 별도 메커니즘 SQL을 순서대로 실행한다.

```sh
psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file /path/to/approved/upsert-games.sql

psql "$DATABASE_URL" \
  --set ON_ERROR_STOP=on \
  --file /path/to/approved-game-mechanism-batch/upsert-game-mechanisms.sql
```

`upsert-game-mechanisms.sql`은 자체 transaction 안에서 게임과 메커니즘 관계를 해석하지 못하면 롤백한다. 따라서 `upsert-games.sql`을 먼저 성공시킨 뒤 메커니즘 SQL을 실행하며, 이미 승인 게임이 적재된 경우에는 두 번째 명령만 실행한다. 원본 XML·사전·manifest와 `mechanism-quality-report.json`은 생성 SQL의 정확한 입력을 재현할 수 있을 때까지 함께 보관하고 저장소에는 커밋하지 않는다.

## 6. 17만 게임 메타데이터 batch

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
| [2026-08-14 BGG 승인 데이터셋의 AI·embedding 사용 범위](../game-catalog/2026-08-14-bgg-ai-embedding-approval.md) | 승인 release·필드·가공 allowlist와 재승인 조건 |

## 7. 검증 명령

```sh
node --test scripts/game-catalog/prepare-game-catalog.test.mjs
node --test scripts/game-catalog/game-metadata-catalog.test.mjs
./gradlew postgresTest --no-daemon --stacktrace
```

`postgresTest`는 Docker가 필요하다. Docker 데몬이 없어서 Testcontainers가 시작되지 못한 경우에는 테스트 실패가 아니라 실행 환경 제약으로 별도 기록한다.
