# Game Metadata Filters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 170,000개 게임 카탈로그의 카테고리·테마·추천/베스트 인원 관계를 적재하고, 선택지·상세·복합 검색 API로 제공한다.

**Architecture:** GAMES의 기존 표시·규칙 필드는 유지하고 카테고리·테마·인원 선호만 별도 정규화 관계로 저장한다. 목록은 다대다 직접 조인 대신 관계별 상관 EXISTS로 판정해 중복 행과 잘못된 전체 건수를 막고, 상세는 정렬된 Repository projection을 조립한다. 원본 XML·토큰·170,000행 파일은 저장소 밖에서만 수집·정규화하며, 승인 manifest의 품질 게이트를 통과한 배치만 UPSERT를 만든다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA Specification, Flyway, PostgreSQL/Testcontainers, Node.js 20 내장 모듈, JUnit 5, MockMvc

## Global Constraints

- 작업 이슈는 [#420](https://github.com/bamsongi-club/albam-mate/issues/420)이며, 최신 전체 테스트 계약은 사람이 승인한 [T1~T10 승인 코멘트](https://github.com/bamsongi-club/albam-mate/issues/420#issuecomment-5187225098)다.
- #418이 병합된 origin/develop의 releaseYear 계약을 포함한 뒤 V18__create_game_metadata_filter_schema.sql을 사용한다.
- BGG Bearer 토큰, 원본 XML, 170,000행 JSON, 한글 사전 원본, 생성 SQL과 비밀값은 Git·로그·PR 본문에 넣지 않는다.
- games-170k.performance.json은 성능 fixture일 뿐 운영 적재 입력이 아니다. 운영 관계는 승인된 순위 CSV, BGG XML snapshot, 한글 테마 사전으로 다시 만든다.
- 카테고리는 고정 8개 코드와 CSV의 양수 subdomain rank만 사용한다. rank 누락·0·음수는 관계를 만들지 않는다.
- 테마 code는 ASCII UPPER_SNAKE_CASE이며 충돌 시 _BGG_<id>를 붙인다. 빈 한글명은 공개·적재를 막는다.
- 추천은 bestVotes + recommendedVotes > notRecommendedVotes, 베스트는 bestVotes > recommendedVotes 그리고 bestVotes > notRecommendedVotes다. N+는 검증된 maxPlayers까지 확장하고 isBest는 isRecommended의 부분집합이다.
- category, recommendedPlayerCount, bestPlayerCount, mechanism의 반복값은 OR이고 다른 필터 그룹은 AND다. themeMatch는 생략 시 ANY, ANY는 OR, ALL은 모든 고유 테마 관계를 요구한다.
- themeMatch는 테마 없이 보내도 유효하지만 중복 또는 알 수 없는 값, 알 수 없는 코드, 0 이하 인원은 전체 요청을 400 VALIDATION_ERROR로 거절한다.
- Game.tag를 카테고리·테마에 재사용하지 않고, 목록 카드에는 새 배열을 추가하지 않는다.
- 캐시, Redis, 검색 엔진, pg_trgm, 메커니즘 ALL, 사용자 지정 정렬, 패싯 결과 수와 측정 전의 역방향 검색 인덱스는 추가하지 않는다.
- 다대다 필터는 각 관계의 상관 EXISTS를 사용하고, 실제 170,000건 PostgreSQL EXPLAIN ANALYZE가 병목을 보일 때만 별도 인덱스를 후속 결정으로 제안한다.

---

## File Structure

| 경로 | 책임 |
| --- | --- |
| docs/adr/game/0050-game-metadata-catalog-and-filters.md | 170,000건 메타데이터 관계·출처·필터·성능 결정을 기록한다 |
| docs/P1-spec.md, docs/p1/search.md, docs/API.md, docs/ERD.md, docs/guides/GAME_CATALOG_IMPORT.md, docs/p1/README.md | 제품·API·ERD·적재 정본과 SEARCH-01 현재 상태를 새 계약으로 맞춘다 |
| src/main/resources/db/migration/V18__create_game_metadata_filter_schema.sql | 다섯 테이블, 제약, 8개 카테고리 기준 행을 만든다 |
| src/main/java/cloud/bamsongi/albammate/game/entity/GameCategory*.java, GameTheme*.java, GamePlayerPreference*.java | 정규화 관계의 JPA 모델 |
| src/main/java/cloud/bamsongi/albammate/game/repository/GameCategory*.java, GameTheme*.java, GamePlayerPreferenceRepository.java | 코드 검증, 선택지·상세 projection 조회 |
| src/main/java/cloud/bamsongi/albammate/game/dto/GameCategoryOption.java, GameThemeOption.java, GameCategorySummary.java, GameThemeSummary.java, ThemeMatch.java | 공개 선택지·상세 메타데이터와 query enum |
| src/main/java/cloud/bamsongi/albammate/game/service/GameCategoryQueryService.java, GameThemeQueryService.java | 공개 선택지 조회 |
| src/main/java/cloud/bamsongi/albammate/game/controller/GameCategoryController.java, GameThemeController.java | 새 선택지 API 경계 |
| GameListRequest.java, GameListSearchCriteria.java, GameQueryService.java, GameDetail.java | 목록 바인딩·조건·코드 검증·상세 조립 |
| scripts/game-catalog/bgg-metadata-acquisition.mjs | Keychain 토큰만 읽는 재개 가능한 20 ID XML snapshot 수집 |
| scripts/game-catalog/game-metadata-catalog.mjs | XML·CSV·한글 사전 정규화, 품질 게이트, UPSERT 렌더링 |
| scripts/game-catalog/prepare-game-metadata-catalog.mjs | 저장소 밖 입력을 quality report·service JSON·SQL로 변환하는 CLI |
| scripts/game-catalog/game-metadata-catalog.test.mjs | 카테고리·테마·poll 정규화와 산출물 차단 회귀 |
| src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java | 선택지·HTTP 필터·상세 공개 계약 |
| src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataCatalogImportPostgresTest.java | UPSERT 수렴·롤백·Flyway/JPA 제약 |
| src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataFilterPostgresTest.java | EXISTS 기반 OR·AND·ANY·ALL·전체 건수 계약 |
| src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataSearchPerformancePostgresTest.java | 외부 170,000행 fixture의 실행 계획·시간 기록 |
| docs/game-catalog/2026-08-05-game-metadata-filter-performance.md | 실제 fixture checksum·명령·대표 조합의 측정 증거 |

### Task 1: 정본 계약과 ADR을 먼저 갱신한다

**Files:**
- Create: docs/adr/game/0050-game-metadata-catalog-and-filters.md
- Modify: docs/adr/game/README.md
- Modify: docs/P1-spec.md
- Modify: docs/p1/search.md
- Modify: docs/API.md
- Modify: docs/ERD.md
- Modify: docs/guides/GAME_CATALOG_IMPORT.md
- Modify: docs/p1/README.md
- Test: scripts/check-doc-links.mjs

**Interfaces:**
- Consumes: docs/superpowers/specs/2026-08-05-game-metadata-filters-design.md 1~11절과 #420의 T1~T10.
- Produces: SEARCH-01의 카테고리·테마·추천/베스트 API·스키마·적재·성능 계약, ADR-0050 링크와 V18 이름.

- [ ] **Step 1: 정본 충돌을 재현한다**

docs/p1/search.md에서 테마·추천 인원·최적 인원·17만건이 제외 범위라는 문장과 docs/API.md의 태그·테마 필터 미지원 문장을 확인한다. 둘 중 하나가 남아 있으면 새 API를 구현해도 정본이 충돌하므로 이 단계는 실패로 본다.

- [ ] **Step 2: ADR-0050과 연결 문서를 최소 변경으로 작성한다**

ADR은 ADR-0019의 2,000건·테마 보류 범위를 이 기능에서 대체하고 ADR-0048의 메커니즘 공개 계약은 유지한다고 명시한다. API와 검색 정본에 다음 조합 의미를 넣는다.

~~~
category=A&category=B              -> category A OR B
theme=A&theme=B                     -> A OR B (themeMatch 생략 = ANY)
theme=A&theme=B&themeMatch=ALL     -> A AND B
recommendedPlayerCount=3&...=4    -> 추천 3 OR 4
bestPlayerCount=3&...=4           -> 베스트 3 OR 4
서로 다른 줄의 필터 그룹          -> AND
~~~

ERD에는 GAME_CATEGORIES, GAME_CATEGORY_RELATIONS, GAME_THEMES, GAME_THEME_RELATIONS, GAME_PLAYER_PREFERENCES의 키·FK·CHECK와 is_best -> is_recommended를 넣는다. import guide에는 upsert-games.sql 뒤 upsert-game-metadata.sql을 실행하는 순서, manifest와 한글 사전 검수, 실패 시 전체 롤백을 넣는다. docs/p1/README.md의 SEARCH-01 행은 새 계약 부분을 구현·검증하기 전까지 부분 구현·부분 검증으로 바꾼다.

- [ ] **Step 3: 문서 링크와 이전 계약을 검사한다**

Run: node scripts/check-doc-links.mjs

Expected: exit code 0. SEARCH-01 범위에는 이전 제외 계약이 남지 않는다.

- [ ] **Step 4: 문서 계약을 커밋한다**

~~~
git add docs/adr/game/0050-game-metadata-catalog-and-filters.md docs/adr/game/README.md docs/P1-spec.md docs/p1/search.md docs/API.md docs/ERD.md docs/guides/GAME_CATALOG_IMPORT.md docs/p1/README.md
git commit -m "docs: 게임 메타데이터 필터 계약 추가"
~~~

### Task 2: V18 스키마와 관계 모델·선택지 조회를 만든다

**Files:**
- Create: src/main/resources/db/migration/V18__create_game_metadata_filter_schema.sql
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GameCategory.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GameCategoryRelation.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GameCategoryRelationId.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GameTheme.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GameThemeRelation.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GameThemeRelationId.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GamePlayerPreference.java
- Create: src/main/java/cloud/bamsongi/albammate/game/entity/GamePlayerPreferenceId.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameCategoryRepository.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameCategoryRelationRepository.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameThemeRepository.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameThemeRelationRepository.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GamePlayerPreferenceRepository.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameCategoryOptionRow.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameThemeOptionRow.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameCategorySummaryRow.java
- Create: src/main/java/cloud/bamsongi/albammate/game/repository/GameThemeSummaryRow.java
- Create: src/main/java/cloud/bamsongi/albammate/game/dto/GameCategoryOption.java
- Create: src/main/java/cloud/bamsongi/albammate/game/dto/GameThemeOption.java
- Create: src/main/java/cloud/bamsongi/albammate/game/dto/GameCategorySummary.java
- Create: src/main/java/cloud/bamsongi/albammate/game/dto/GameThemeSummary.java
- Create: src/main/java/cloud/bamsongi/albammate/game/service/GameCategoryQueryService.java
- Create: src/main/java/cloud/bamsongi/albammate/game/service/GameThemeQueryService.java
- Create: src/main/java/cloud/bamsongi/albammate/game/controller/GameCategoryController.java
- Create: src/main/java/cloud/bamsongi/albammate/game/controller/GameThemeController.java
- Test: src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java
- Test: src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataCatalogImportPostgresTest.java

**Interfaces:**
- Consumes: Task 1의 V18·ERD 계약과 기존 GameMechanism/복합 ID/projection 패턴.
- Produces: GameCategoryOption(code, nameKo, nameEn, displayOrder), GameThemeOption(code, nameKo, nameEn), GameCategorySummary(code, nameKo, nameEn), GameThemeSummary(code, nameKo, nameEn).

- [ ] **Step 1: V18과 JPA 제약의 실패 테스트를 작성한다**

~~~
@Test
void PostgreSQL_Flyway와_JPA는_카테고리_테마_인원선호_제약을_일치시킨다() {
    // 8개 고정 category, 복합 PK 중복, player_count > 0,
    // is_best=true && is_recommended=false의 CHECK 위반을 PostgreSQL에서 검증한다.
}
~~~

Run: ./gradlew postgresTest --tests "cloud.bamsongi.albammate.game.GameMetadataCatalogImportPostgresTest.PostgreSQL_Flyway와_JPA는_카테고리_테마_인원선호_제약을_일치시킨다" --rerun

Expected: V18 또는 엔티티가 없어 실패.

- [ ] **Step 2: 다섯 테이블과 8개 기준 행을 최소 구현한다**

~~~
CREATE TABLE game_player_preferences (
    game_id BIGINT NOT NULL,
    player_count INTEGER NOT NULL,
    is_recommended BOOLEAN NOT NULL,
    is_best BOOLEAN NOT NULL,
    PRIMARY KEY (game_id, player_count),
    CONSTRAINT fk_game_player_preferences_game
        FOREIGN KEY (game_id) REFERENCES games (id) ON DELETE CASCADE,
    CONSTRAINT ck_game_player_preferences_count CHECK (player_count > 0),
    CONSTRAINT ck_game_player_preferences_best_implies_recommended
        CHECK (NOT is_best OR is_recommended)
);
~~~

카테고리·테마 relation은 game_id와 dictionary_id 복합 PK를 쓰고, dictionary FK 삭제 비용을 막는 category_id/game_id와 theme_id/game_id 인덱스만 둔다. 관계 엔티티는 EmbeddedId, MapsId, LAZY ManyToOne으로 기존 메커니즘 패턴을 따른다.

- [ ] **Step 3: 선택지·상세 projection Repository를 구현한다**

~~~
@Query("""
    select new cloud.bamsongi.albammate.game.repository.GameCategoryOptionRow(
        c.code, c.nameKo, c.nameEn, c.displayOrder)
    from GameCategory c
    order by c.displayOrder asc
    """)
List<GameCategoryOptionRow> findPublicOptions();

@Query("""
    select new cloud.bamsongi.albammate.game.repository.GameThemeSummaryRow(
        t.code, t.nameKo, t.nameEn)
    from GameThemeRelation r join r.theme t
    where r.game.id = :gameId
    order by t.nameKo asc, t.code asc
    """)
List<GameThemeSummaryRow> findSummaryRowsByGameId(long gameId);
~~~

GameCategoryRepository와 GameThemeRepository는 countByCodeIn을 제공하고, GamePlayerPreferenceRepository는 recommended/best count를 playerCount ASC로 projection한다.

- [ ] **Step 4: 선택지 Controller의 HTTP 계약을 Green으로 만든다**

~~~
@RestController
@RequestMapping("/api/game-categories")
@RequiredArgsConstructor
class GameCategoryController {
    @GetMapping
    ApiResponse<List<GameCategoryOption>> listGameCategories() {
        return ApiResponse.success(HttpStatus.OK, gameCategoryQueryService.findOptions());
    }
}
~~~

GameMetadataHttpIntegrationTest의 카테고리와_테마_선택지는_내부식별자없이_결정적_정렬로_반환한다에서 category displayOrder ASC, theme nameKo/code ASC, 내부 ID/BGG ID 비노출을 assert한다.

- [ ] **Step 5: 대상 테스트를 Green으로 재실행하고 커밋한다**

Run: ./gradlew test --tests "cloud.bamsongi.albammate.game.GameMetadataHttpIntegrationTest.카테고리와_테마_선택지는_내부식별자없이_결정적_정렬로_반환한다" --rerun --fail-fast

Run: ./gradlew postgresTest --tests "cloud.bamsongi.albammate.game.GameMetadataCatalogImportPostgresTest.PostgreSQL_Flyway와_JPA는_카테고리_테마_인원선호_제약을_일치시킨다" --rerun --fail-fast

~~~
git add src/main/resources/db/migration/V18__create_game_metadata_filter_schema.sql src/main/java/cloud/bamsongi/albammate/game src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataCatalogImportPostgresTest.java
git commit -m "feat: 게임 메타데이터 관계와 선택지 추가"
~~~

### Task 3: 저장소 밖 BGG snapshot 정규화와 metadata UPSERT를 만든다

**Files:**
- Create: scripts/game-catalog/bgg-metadata-acquisition.mjs
- Create: scripts/game-catalog/game-metadata-catalog.mjs
- Create: scripts/game-catalog/prepare-game-metadata-catalog.mjs
- Create: scripts/game-catalog/game-metadata-catalog.test.mjs
- Modify: src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataCatalogImportPostgresTest.java
- Test: scripts/game-catalog/game-metadata-catalog.test.mjs
- Test: src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataCatalogImportPostgresTest.java

**Interfaces:**
- Consumes: --ids, --out, macOS Keychain service albam-mate-bgg-api, CSV rank rows, raw XML manifest, approved theme dictionary, approved metadata manifest.
- Produces: metadata-quality-report.json, service-game-metadata.json, upsert-game-metadata.sql; 모두 저장소 밖 --out에서만 생성한다.

~~~
export function normalizeGameMetadata({ rankRows, snapshots, themeDictionary, manifest }) {
    // returns categories, themes, categoryRelations, themeRelations, playerPreferences, errors
}

export function renderGameMetadataUpsertSql(metadata) {
    // returns one BEGIN...COMMIT transaction; unresolved relation aborts it
}
~~~

- [ ] **Step 1: 정규화 실패 경로를 Node 테스트로 작성한다**

~~~
test("양수 subdomain rank만 8개 카테고리 관계로 만든다", () => {});
test("한글명 없는 테마와 코드 충돌과 중복 관계는 산출물을 차단한다", () => {});
test("4+와 동률과 poll 누락과 잘못된 label을 추정 없이 정규화한다", () => {});
test("승인 manifest가 없거나 170000 ID/checksum이 다르면 SQL을 만들지 않는다", () => {});
~~~

Run: node --test scripts/game-catalog/game-metadata-catalog.test.mjs

Expected: module 또는 export가 없어 실패.

- [ ] **Step 2: 수집기는 Keychain 토큰과 재개 manifest만 사용하게 구현한다**

~~~
security find-generic-password -a <current-user> -s albam-mate-bgg-api -w
GET /xmlapi2/thing?id=<20개 이하>&type=boardgame&stats=1
202, 429, 500, 503 -> 기록 후 재시도
~~~

수집기는 raw XML 파일명, 요청 ID, 응답 ID, HTTP status, bytes, SHA-256, 취득 시각만 manifest에 기록한다. 토큰 문자열은 표준 출력·오류·manifest에 넣지 않는다. 완료 checksum이 있는 batch는 건너뛰고 응답 ID 집합이 요청 ID 집합과 다르면 완료 처리하지 않는다.

- [ ] **Step 3: BGG의 필요한 구조만 결정적으로 파싱하고 품질 게이트를 구현한다**

~~~
const recommended = bestVotes + recommendedVotes > notRecommendedVotes;
const best = bestVotes > recommendedVotes && bestVotes > notRecommendedVotes;
const counts = label.endsWith("+")
    ? range(Number(label.slice(0, -1)), maxPlayers)
    : [Number(label)];
~~~

각 boardgamecategory는 bgg_theme_id, 원문 영문명, 승인 한글명, 안정 code를 가져야 한다. 중복 relation은 gameId:themeId key로 한 번만 허용한다. N+의 N이 maxPlayers보다 크거나 maxPlayers가 없으면 오류를 넣고 적재 산출물을 막는다.

- [ ] **Step 4: 반복 실행에 수렴하고 해석 실패 시 롤백하는 SQL을 만든다**

~~~
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM game_theme_relation_source source
        LEFT JOIN games game ON game.bgg_id = source.bgg_id
        LEFT JOIN game_themes theme ON theme.bgg_theme_id = source.bgg_theme_id
        WHERE game.id IS NULL OR theme.id IS NULL
    ) THEN
        RAISE EXCEPTION '승인 메타데이터 관계의 게임 또는 테마를 해석할 수 없습니다.';
    END IF;
END $$;
~~~

카테고리·테마·인원 관계는 source temp table과 ON CONFLICT DO NOTHING을 사용하고 같은 transaction 안에서 source에 없는 관계를 삭제한다. GAMES 행은 새 snapshot에 없다는 이유로 삭제하지 않는다.

- [ ] **Step 5: Node·PostgreSQL의 T1~T4를 Green으로 확인하고 커밋한다**

Run: node --test scripts/game-catalog/game-metadata-catalog.test.mjs

Run: ./gradlew postgresTest --tests "cloud.bamsongi.albammate.game.GameMetadataCatalogImportPostgresTest.메타데이터_재적재는_관계를_승인_스냅샷에_수렴시킨다" --tests "cloud.bamsongi.albammate.game.GameMetadataCatalogImportPostgresTest.메타데이터_참조해석_실패는_전체_적재를_롤백한다" --rerun --fail-fast

~~~
git add scripts/game-catalog/bgg-metadata-acquisition.mjs scripts/game-catalog/game-metadata-catalog.mjs scripts/game-catalog/prepare-game-metadata-catalog.mjs scripts/game-catalog/game-metadata-catalog.test.mjs src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataCatalogImportPostgresTest.java
git commit -m "feat: 게임 메타데이터 적재 도구 추가"
~~~

### Task 4: 목록 요청·상관 EXISTS 필터·입력 검증을 구현한다

**Files:**
- Create: src/main/java/cloud/bamsongi/albammate/game/dto/ThemeMatch.java
- Modify: src/main/java/cloud/bamsongi/albammate/game/dto/GameListRequest.java
- Modify: src/main/java/cloud/bamsongi/albammate/game/service/GameListSearchCriteria.java
- Modify: src/main/java/cloud/bamsongi/albammate/game/service/GameQueryService.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/controller/GameControllerTest.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/dto/GameListRequestTest.java
- Create: src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataFilterPostgresTest.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java

**Interfaces:**
- Consumes: Task 2의 relation entity·Repository code count, 기존 GameListSearchCriteria.toSpecification().
- Produces: ThemeMatch enum과 중복 없는 Specification<Game>.

~~~
public enum ThemeMatch { ANY, ALL }

private List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> category;
private List<@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]*") String> theme;
private List<ThemeMatch> themeMatch;
private List<@Min(1) Integer> recommendedPlayerCount;
private List<@Min(1) Integer> bestPlayerCount;
~~~

- [ ] **Step 1: 요청 바인딩의 실패 사례를 작성한다**

GameControllerTest의 게임_메타데이터_조건_파라미터를_서비스에_전달한다는 반복 category/theme/count와 themeMatch=ALL을 capture한다. 게임_메타데이터_조건이_형식오류면_VALIDATION_ERROR다는 category=invalid, theme=, recommendedPlayerCount=0, bestPlayerCount=-1, themeMatch=UNKNOWN, themeMatch=ANY&themeMatch=ALL을 모두 400으로 assert한다.

Run: ./gradlew test --tests "cloud.bamsongi.albammate.game.controller.GameControllerTest.게임_메타데이터_조건이_형식오류면_VALIDATION_ERROR다" --rerun

Expected: 새 요청 필드·validator가 없어 실패.

- [ ] **Step 2: themeMatch 단일값/default와 코드 검증을 구현한다**

~~~
public ThemeMatch getThemeMatch() {
    if (themeMatch == null || themeMatch.isEmpty()) return ThemeMatch.ANY;
    if (themeMatch.size() != 1 || themeMatch.getFirst() == null) return null;
    return themeMatch.getFirst();
}

@AssertTrue(message = "themeMatch는 한 번만 전달할 수 있습니다.")
public boolean isThemeMatchSingleValue() {
    return themeMatch == null || themeMatch.isEmpty()
        || (themeMatch.size() == 1 && themeMatch.getFirst() != null);
}
~~~

GameQueryService는 중복을 제거한 category/theme 코드 수가 각 countByCodeIn 결과와 다르면 BusinessException(VALIDATION_ERROR)을 던진다. 일부 코드만 유효해도 전체 요청을 통과시키지 않는다.

- [ ] **Step 3: 각 관계를 상관 EXISTS로 추가한다**

~~~
private void addThemePredicate(Root<Game> root, CriteriaQuery<?> query,
        CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
    if (themes.isEmpty()) return;
    if (themeMatch == ThemeMatch.ANY) {
        predicates.add(criteriaBuilder.exists(themeExists(root, query, criteriaBuilder, themes)));
        return;
    }
    Subquery<Long> matchedThemeCount = query.subquery(Long.class);
    Root<GameThemeRelation> relation = matchedThemeCount.from(GameThemeRelation.class);
    Join<GameThemeRelation, GameTheme> theme = relation.join("theme");
    matchedThemeCount.select(criteriaBuilder.countDistinct(theme.get("code")));
    matchedThemeCount.where(criteriaBuilder.equal(relation.get("game"), root), theme.get("code").in(themes));
    predicates.add(criteriaBuilder.equal(matchedThemeCount, (long) themes.size()));
}
~~~

카테고리, 추천, 베스트도 각각 별도 EXISTS를 더한다. direct join은 쓰지 않는다. copy constructor와 getter에 새 lists/themeMatch를 모두 보존해 upcomingOnly 또는 playedFilter 뒤에도 조건이 사라지지 않게 한다.

- [ ] **Step 4: PostgreSQL OR·AND·ANY·ALL·total count를 Green으로 만든다**

GameMetadataFilterPostgresTest에서 category 두 개 OR, theme ANY와 ALL의 차이, 추천/베스트 각 OR, mechanism·keyword·가능 인원과의 AND, 반복값의 무중복, 첫·둘째 page total count를 한 fixture로 assert한다.

Run: ./gradlew postgresTest --tests "cloud.bamsongi.albammate.game.GameMetadataFilterPostgresTest.PostgreSQL에서_카테고리_테마_인원선호와_기존조건은_중복없이_결합한다" --rerun --fail-fast

- [ ] **Step 5: HTTP 검증과 PostgreSQL 조회를 재실행하고 커밋한다**

Run: ./gradlew test --tests "cloud.bamsongi.albammate.game.controller.GameControllerTest.게임_메타데이터_조건이_형식오류면_VALIDATION_ERROR다" --tests "cloud.bamsongi.albammate.game.GameMetadataHttpIntegrationTest.알수없는_카테고리_테마와_중복_themeMatch는_VALIDATION_ERROR다" --rerun --fail-fast

~~~
git add src/main/java/cloud/bamsongi/albammate/game/dto/ThemeMatch.java src/main/java/cloud/bamsongi/albammate/game/dto/GameListRequest.java src/main/java/cloud/bamsongi/albammate/game/service/GameListSearchCriteria.java src/main/java/cloud/bamsongi/albammate/game/service/GameQueryService.java src/test/java/cloud/bamsongi/albammate/game/controller/GameControllerTest.java src/test/java/cloud/bamsongi/albammate/game/dto/GameListRequestTest.java src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataFilterPostgresTest.java
git commit -m "feat: 게임 메타데이터 복합 필터 추가"
~~~

### Task 5: 게임 상세 메타데이터 배열을 조립한다

**Files:**
- Modify: src/main/java/cloud/bamsongi/albammate/game/dto/GameDetail.java
- Modify: src/main/java/cloud/bamsongi/albammate/game/service/GameQueryService.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/service/GameQueryServiceDetailTest.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/service/GameQueryServiceDetailIntegrationTest.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/controller/GameControllerTest.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java

**Interfaces:**
- Consumes: Task 2의 findSummaryRowsByGameId와 findRecommendedPlayerCountsByGameId/findBestPlayerCountsByGameId.
- Produces: 기존 GameDetail 뒤에 categories, themes, recommendedPlayerCounts, bestPlayerCounts 배열을 더한 응답.

~~~
public record GameDetail(
    Long id, Long bggId, String name, String englishName, String imageUrl,
    String supportedPlayerCount, String tag, String estimatedPlayTime,
    BigDecimal complexity, Integer releaseYear, long upcomingRoomCount,
    String alias, String description, String detailDescription, Boolean playedByMe,
    List<GameCategorySummary> categories, List<GameThemeSummary> themes,
    List<Integer> recommendedPlayerCounts, List<Integer> bestPlayerCounts) {}
~~~

- [ ] **Step 1: 상세 배열의 정렬·빈 배열 계약 테스트를 작성한다**

GameMetadataHttpIntegrationTest의 게임상세는_정렬된_메타데이터배열과_빈배열을_반환한다는 category displayOrder, theme nameKo/code, count ASC와 관계 없는 게임의 []를 assert한다. GameQueryServiceDetailTest는 Repository 결과가 GameDetail로 매핑되는지를 mock으로 assert한다.

Run: ./gradlew test --tests "cloud.bamsongi.albammate.game.GameMetadataHttpIntegrationTest.게임상세는_정렬된_메타데이터배열과_빈배열을_반환한다" --rerun

Expected: GameDetail constructor와 metadata 조립이 없어 실패.

- [ ] **Step 2: detail projection을 한 번씩 조회해 배열을 조립한다**

~~~
return GameDetail.from(
    game, upcomingRoomCount, playedByMe,
    gameCategoryRepository.findSummaryRowsByGameId(gameId).stream().map(GameCategorySummary::from).toList(),
    gameThemeRepository.findSummaryRowsByGameId(gameId).stream().map(GameThemeSummary::from).toList(),
    gamePlayerPreferenceRepository.findRecommendedPlayerCountsByGameId(gameId),
    gamePlayerPreferenceRepository.findBestPlayerCountsByGameId(gameId));
~~~

이전 GameDetail convenience constructor와 from overload는 네 빈 배열을 넣어 유지한다. GameListItem과 GameListRow는 바꾸지 않는다.

- [ ] **Step 3: 상세 단위·통합·HTTP 테스트를 Green으로 만들고 커밋한다**

Run: ./gradlew test --tests "cloud.bamsongi.albammate.game.service.GameQueryServiceDetailTest.게임_상세는_메타데이터배열을_정해진순서로_매핑한다" --tests "cloud.bamsongi.albammate.game.service.GameQueryServiceDetailIntegrationTest.상세는_관계가없는경우_빈메타데이터배열을_반환한다" --tests "cloud.bamsongi.albammate.game.GameMetadataHttpIntegrationTest.게임상세는_정렬된_메타데이터배열과_빈배열을_반환한다" --rerun --fail-fast

~~~
git add src/main/java/cloud/bamsongi/albammate/game/dto/GameDetail.java src/main/java/cloud/bamsongi/albammate/game/service/GameQueryService.java src/test/java/cloud/bamsongi/albammate/game/service/GameQueryServiceDetailTest.java src/test/java/cloud/bamsongi/albammate/game/service/GameQueryServiceDetailIntegrationTest.java src/test/java/cloud/bamsongi/albammate/game/controller/GameControllerTest.java src/test/java/cloud/bamsongi/albammate/game/GameMetadataHttpIntegrationTest.java
git commit -m "feat: 게임 상세 메타데이터 응답 추가"
~~~

### Task 6: 170,000건 PostgreSQL 대표 조합을 측정하고 기록한다

**Files:**
- Create: src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataSearchPerformancePostgresTest.java
- Create: docs/game-catalog/2026-08-05-game-metadata-filter-performance.md
- Modify: docs/p1/README.md
- Test: src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataSearchPerformancePostgresTest.java

**Interfaces:**
- Consumes: 외부 fixture 경로 -Dissue420.fixture=/absolute/path/games-170k.performance.json, Task 4의 GameListSearchCriteria, fixture SHA-256.
- Produces: -Dissue420.performanceReport=/absolute/path/report.json에 조건별 EXPLAIN ANALYZE/경과 시간을 기록하고, 문서에는 checksum·명령·실측값만 남긴다.

- [ ] **Step 1: fixture 경계와 대표 조합 검증을 작성한다**

~~~
@Test
void 십칠만건_fixture에서_대표조합의_결과_전체건수_실행계획과_시간을_기록한다() {
    // 조건 없음, category 단일/복수, theme ANY 단일/복수, theme ALL 복수,
    // recommended/best 복수, mechanism+theme+playerCount 복합을 같은 fixture로 실행한다.
}
~~~

fixture system property가 없거나 JSON 행 수가 170,000이 아니면 명시적으로 실패한다. 이 fixture에서 만든 관계는 성능 transaction에만 쓰고 운영 metadata UPSERT로 전달하지 않는다.

- [ ] **Step 2: 대표 조합의 설명 가능한 측정 결과를 출력한다**

~~~
EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
SELECT id FROM games
WHERE /* GameListSearchCriteria와 동치인 현재 predicate */
ORDER BY name ASC, id ASC
LIMIT 10;
~~~

각 조합에서 실제 query 결과 ID와 Page totalElements를 비교하고 JSON report에 fixture SHA-256, 조건, 결과 건수, total count, elapsed milliseconds, plan JSON을 기록한다. report 작성 실패는 테스트 실패다.

- [ ] **Step 3: Docker를 확인하고 T10을 실제 fixture로 실행한다**

Run: docker version

Run: ./gradlew postgresPerformanceTest --tests "cloud.bamsongi.albammate.game.GameMetadataSearchPerformancePostgresTest.십칠만건_fixture에서_대표조합의_결과_전체건수_실행계획과_시간을_기록한다" --rerun --fail-fast -Dissue420.fixture=/absolute/path/games-170k.performance.json -Dissue420.fixtureManifest=/absolute/path/source-manifest.performance.json -Dissue420.rankCsv=/absolute/path/boardgames_ranks07-24.csv -Dissue420.performanceReport=/absolute/path/game-metadata-performance-report.json

Expected: Testcontainers PostgreSQL에서 7개 대표 조합의 plan/시간이 report에 남는다. cache와 추가 검색 인덱스는 넣지 않는다.

- [ ] **Step 4: 실제 수치만 문서에 반영하고 상태를 갱신한다**

docs/game-catalog/2026-08-05-game-metadata-filter-performance.md에는 fixture SHA-256, exact Gradle selector, PostgreSQL 이미지, 각 조합의 total count·elapsed milliseconds·plan 핵심 노드만 기록한다. docs/p1/README.md의 SEARCH-01 상태는 코드, T1~T10, 문서 링크, 실제 performance report가 모두 통과한 경우에만 완료 근거로 바꾼다.

- [ ] **Step 5: 문서 링크·대상 테스트를 다시 확인하고 커밋한다**

Run: node scripts/check-doc-links.mjs

~~~
git add src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataSearchPerformancePostgresTest.java docs/game-catalog/2026-08-05-game-metadata-filter-performance.md docs/p1/README.md
git commit -m "test: 게임 메타데이터 검색 성능 측정 추가"
~~~

### Task 7: 전달 패킷·검증 manifest·PR 전 고정 head 검증을 마친다

**Files:**
- Modify: 승인 범위에서 실제 변경된 파일만
- Test: scripts/validate-packet.mjs
- Test: scripts/validate-backend-test-manifest.mjs
- Test: scripts/check-doc-links.mjs

**Interfaces:**
- Consumes: #420 승인 comment, Task 1~6의 exact test selector와 저장소 밖 v3 packet/manifest.
- Produces: T1~T10 각각 최소 하나의 exact source·selector evidence와 Draft PR 고정 head의 Green 결과.

- [ ] **Step 1: T1~T10별 manifest evidence를 실제 선언 메서드로 작성한다**

~~~
{
  "id": "T7",
  "evidence": [{
    "task": "postgresTest",
    "source": "src/postgresTest/java/cloud/bamsongi/albammate/game/GameMetadataFilterPostgresTest.java",
    "selector": "cloud.bamsongi.albammate.game.GameMetadataFilterPostgresTest.PostgreSQL에서_테마_ANY와_ALL을_구분한다"
  }]
}
~~~

실제 메서드명·source set과 다르면 manifest validator가 실패해야 한다. node test는 T1~T3의 보조 evidence로만 넣고 T-ID마다 Java exact selector를 하나 이상 둔다.

- [ ] **Step 2: packet·manifest와 대상 H2/PostgreSQL 테스트를 고정한다**

Run: node scripts/validate-packet.mjs /absolute/temp/issue-420-packet.json

Run: node scripts/validate-backend-test-manifest.mjs --packet /absolute/temp/issue-420-packet.json --manifest /absolute/temp/issue-420-manifest.json --worktree /absolute/worktree

Run: ./gradlew test --tests "cloud.bamsongi.albammate.game.GameMetadataHttpIntegrationTest.카테고리와_테마_선택지는_내부식별자없이_결정적_정렬로_반환한다" --tests "cloud.bamsongi.albammate.game.controller.GameControllerTest.게임_메타데이터_조건이_형식오류면_VALIDATION_ERROR다" --tests "cloud.bamsongi.albammate.game.service.GameQueryServiceDetailTest.게임_상세는_메타데이터배열을_정해진순서로_매핑한다" --rerun --fail-fast

Run: ./gradlew postgresTest --tests "cloud.bamsongi.albammate.game.GameMetadataCatalogImportPostgresTest.메타데이터_재적재는_관계를_승인_스냅샷에_수렴시킨다" --tests "cloud.bamsongi.albammate.game.GameMetadataFilterPostgresTest.PostgreSQL에서_카테고리_테마_인원선호와_기존조건은_중복없이_결합한다" --rerun --fail-fast

Run: ./gradlew postgresPerformanceTest --tests "cloud.bamsongi.albammate.game.GameMetadataSearchPerformancePostgresTest.십칠만건_fixture에서_대표조합의_결과_전체건수_실행계획과_시간을_기록한다" --rerun --fail-fast -Dissue420.fixture=/absolute/path/games-170k.performance.json -Dissue420.fixtureManifest=/absolute/path/source-manifest.performance.json -Dissue420.rankCsv=/absolute/path/boardgames_ranks07-24.csv -Dissue420.performanceReport=/absolute/path/game-metadata-performance-report.json

- [ ] **Step 3: diff·문서·커버리지 규칙을 검사한다**

Run: git diff --check

Run: node scripts/check-doc-links.mjs

Run: ./gradlew jacocoTestReport verifyCoverageRuleTargets

build.gradle 변경이 필요하면 gatedBranchCoverage에 새 패키지의 실측 minimum만 추가하고 git diff HEAD -- build.gradle이 그 항목 추가 또는 최소선 상향 hunk만 포함하는지 확인한다. 필요하지 않으면 build.gradle은 변경하지 않는다.

- [ ] **Step 4: Draft PR의 최신 head에서 같은 검증·read-only 리뷰·CI를 반복한다**

branch를 push한 뒤 Draft PR의 headRefOid를 기록한다. 그 SHA에서 manifest validator, H2/PostgreSQL rerun, review-code, GitHub CI, mergeability를 확인한다. head가 바뀌면 기존 결과를 폐기하고 새 head에서 동일 단계를 다시 실행한다. 모두 성공한 경우에만 Ready for review로 전환한다.

## Plan Self-Review

### Spec coverage

| 설계 요구 | 구현 Task |
| --- | --- |
| 고정 8개 category와 rank 관계 | Task 2, Task 3 |
| theme ID·한글명·안정 code·중복 차단 | Task 2, Task 3 |
| recommended/best와 4+ 확장 | Task 2, Task 3 |
| 승인 적재의 수렴·롤백 | Task 3 |
| category/theme 선택지 API | Task 2 |
| category OR·검증 | Task 4 |
| theme ANY/ALL·themeMatch 검증 | Task 4 |
| recommendation/best OR와 전 그룹 AND | Task 4 |
| GameDetail 배열·정렬·빈 배열 | Task 5 |
| 170,000건 EXPLAIN·시간·cache 미도입 | Task 6 |
| ADR/P1/API/ERD/import/status 갱신 | Task 1, Task 6 |
| packet/manifest/Draft 고정 head 검증 | Task 7 |

### Placeholder scan

미완성 표지와 모호한 오류 처리 지시를 제거했다. 각 code step은 실제 type, query, command 또는 assertion 경계를 갖는다.

### Type consistency

ThemeMatch는 GameListRequest, GameListSearchCriteria, HTTP query에 같은 이름으로 사용한다. category/theme option과 detail summary는 displayOrder 공개 여부가 달라 별도 record로 분리한다. GamePlayerPreference는 playerCount, isRecommended, isBest를 목록 조건과 detail projection에서 같은 의미로 사용한다.

## Execution Route

이 저장소의 AGENTS.md는 확정된 백엔드 기능을 backend-delivery 절차로 backend-developer에게 위임하도록 요구한다. 따라서 Task 1의 정본 계약을 먼저 반영하고, v3 packet을 검증한 뒤 전담 구현자가 Task 2~7을 TDD 순서로 실행한다.
