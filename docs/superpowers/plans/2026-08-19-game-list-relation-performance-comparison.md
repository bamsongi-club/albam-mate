# Game List Relation Performance Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 현재 Slice(V0)를 기준선으로 relation query shape와 PostgreSQL theme relation index의 V1·V2·V3을 재현 가능하게 비교하고, 계약·p95 gate를 모두 통과한 하나만 적용한다.

**Architecture:** V1은 GameListSpecification의 theme·mechanism correlated subquery를 관계 테이블이 만드는 game ID 집합으로 바꾼다. V2는 PostgreSQL의 단일 theme_id index를 (theme_id, game_id)로 교체하고 V3는 두 변경을 결합한다. 비교기는 raw runner JSON 16개를 검증·집계해 후보를 선택한다.

**Tech Stack:** Java 21, Spring Data JPA Criteria API, PostgreSQL 18.4, Flyway, Testcontainers, Node.js 20+, Docker Compose

**Spec:** docs/superpowers/specs/2026-08-19-game-list-relation-performance-comparison-design.md

## Global Constraints

- 후보 구현 전 새 [PERF] 게임 목록 relation·complex query shape·theme index 비교 이슈의 최신 전체 T1…T5를 사람이 GitHub 코멘트로 승인해야 한다. #770은 relation query/index를 범위 밖으로 둔 Slice(V0) 기준선이다.
- games 170,005, BGG ID SHA-256 75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744, relation metadata 네 수가 시작·종료에 같은 fixture여야 한다.
- API data 키 content, page, size, hasNext와 기본 정렬 popularity_score DESC, name ASC, id ASC를 유지한다. exact total game count SQL을 되살리지 않는다.
- 모든 variant는 기존 filter ANY/ALL, 인증·검증·오류 envelope, Slice 중복·누락 없음을 유지한다.
- scenario별 warm-up 5회 뒤 순차 HTTP 20회이며, 동시 부하·CPU·오류율은 #863에서만 수행한다.
- 모든 scenario candidate median p95는 V0 median p95의 105% 이하여야 한다. relation-theme-mechanism과 complex median p95는 모두 V0보다 작아야 한다.
- PostgreSQL index는 새 Flyway vendor migration으로만 변경하고 이미 적용된 migration은 수정하지 않는다.
- dirty/untracked 파일을 삭제·reset·stash하지 않는다. variant마다 clean worktree와 독립 Compose project를 사용한다.

---

## File Structure

| 파일 | 책임 |
| --- | --- |
| docs/adr/game/0081-game-list-relation-filter-performance-selection.md | 후보 비교·선택, cache/projection 제외, rollback·재검토 경계 |
| docs/adr/game/README.md | ADR-0081 인덱스 |
| scripts/measurements/game-list-variant-comparison.mjs | 로컬 V0~V3 raw artifact 16개 검증과 p50/p95/max·gate·선정 결과 생성 |
| scripts/measurements/game-list-variant-comparison.test.mjs | artifact 누락·fixture 불일치·5% 회귀·tie-break 테스트 |
| src/main/java/cloud/bamsongi/albammate/game/repository/GameListSpecification.java | V1 uncorrelated theme/mechanism game-ID subquery |
| src/test/java/cloud/bamsongi/albammate/game/GameMatchModeHttpIntegrationTest.java | H2 ANY/ALL·AND·Slice 의미 회귀 |
| src/postgresTest/java/cloud/bamsongi/albammate/game/GameListFilterPostgresTest.java | PostgreSQL relation 교집합·정렬·Slice 경계 |
| src/main/resources/db/vendor-migration/postgresql/V33__replace_game_theme_relation_theme_index.sql | V2의 단일 theme index 교체 |
| src/postgresTest/java/cloud/bamsongi/albammate/game/GameThemeRelationIndexPostgresTest.java | PostgreSQL Flyway index 정의 검증 |
| docs/ERD.md | GAME_THEME_RELATIONS 역방향 index 정본 |
| docs/measurements/results/game-list-740/game-list-770-relation-variant-comparison-2026-08-19.md | 사람용 비교 표·EXPLAIN·결론 |
| docs/measurements/game-list-740-baseline.md | 비교 실행 경로와 #863 분리 링크 |

## Task 1: ADR-0081로 선택 경계 승인 기록

**Files:**
- Create: docs/adr/game/0081-game-list-relation-filter-performance-selection.md
- Modify: docs/adr/game/README.md
- Test: scripts/docs/check-doc-links.mjs

**Interfaces:**
- Consumes: V0~V3 spec, #770 Slice contract, #863 분리 범위
- Produces: 후보 코드·migration·측정이 참조할 승인 ADR-0081

- [ ] **Step 1: ADR 번호 충돌을 확인한다.**

Run:

~~~
find docs/adr -type f -name '[0-9][0-9][0-9][0-9]-*.md' -print \
  | sed -E 's#^.*/([0-9]{4})-.*#\1 #' \
  | sort -n \
  | tail -n 1
gh pr list --repo bamsongi-club/albam-mate --state open --limit 100 \
  --json number,headRefName,files
~~~

Expected: 기존 전역 ADR 번호와 충돌하지 않는 ADR-0081을 파일명·제목·README 행에 함께 반영한다.

- [ ] **Step 2: ADR 초안을 작성한다.**

ADR 결정 본문은 다음 사실을 정확히 고정한다.

~~~
# ADR-0081: 게임 목록 relation filter는 query shape·theme index를 실측 비교해 선택

- 상태: 승인됨
- 작성일: 2026-08-19
- 결정일: 2026-08-19
- 관련: #770, #863, game-list-740 baseline, 성능 비교 spec

## 결정

현재 Slice를 V0으로 유지한 채 V1(query shape), V2(theme_id, game_id index),
V3(결합)를 동일 fixture의 네 round 순차 측정으로 비교한다.
여섯 p95의 5% 회귀 gate와 relation·complex 동시 개선 gate를 통과한 하나만 적용한다.
통과 후보가 없으면 V0를 유지한다.
~~~

대안 표에는 V0, V1, V2, V3, Redis/query cache, materialized projection을 같은 기준으로 비교한다. 결과에는 API 불변·Flyway forward-only·index write/size 비용·탈락 후보 제거를, 보류에는 #863 동시 부하와 새 numeric index를 적는다. 검증은 상태 미검증, 근거 없음, 잔여 검증은 후보 비교·H2/PostgreSQL·raw EXPLAIN으로 쓴다.

- [ ] **Step 3: Game ADR 인덱스를 갱신한다.**

README 표에 번호 순서로 아래 한 행을 넣는다.

~~~
| [0081](0081-game-list-relation-filter-performance-selection.md) | 게임 목록 relation filter는 query shape·theme index를 실측 비교해 선택 | 승인됨 | 2026-08-19 | 미검증 |
~~~

- [ ] **Step 4: 문서 링크 검사를 통과시킨다.**

Run:

~~~
node scripts/docs/check-doc-links.mjs
~~~

Expected: ADR 제목·상태·링크가 유효하고 미검증을 구현 완료로 표기하지 않는다.

- [ ] **Step 5: ADR만 커밋한다.**

~~~
git add docs/adr/game/0081-game-list-relation-filter-performance-selection.md docs/adr/game/README.md
git commit -m 'docs: 게임 목록 relation 성능 선택 ADR 추가'
~~~

## Task 2: #770 범위 밖 후보를 독립 performance 이슈와 T 계약으로 고정

**Files:**
- Create externally: [PERF] 게임 목록 relation·complex query shape·theme index 비교 GitHub Issue
- Modify externally after human approval: 해당 issue의 최신 전체 T1…T5 승인 코멘트

**Interfaces:**
- Consumes: ADR-0081, #770 Slice V0, 성능 비교 spec
- Produces: 후보 코드·Flyway·측정을 시작할 수 있는 독립 issue 번호와 사람이 승인한 테스트 계약

- [ ] **Step 1: template과 중복을 확인한다.**

Run:

~~~
node .agents/skills/issue-writer/scripts/validate-template-registry.mjs
gh issue list --repo bamsongi-club/albam-mate --state all --limit 1000 \
  --search 'relation complex 게임 목록 in:title' \
  --json number,title,state,url
~~~

Expected: feature Form이 enabled이고 같은 목적의 issue가 없을 때만 새 issue를 만든다.

- [ ] **Step 2: feature Form에 아래 범위를 게시한다.**

제목은 [PERF] 게임 목록 relation·complex query shape·theme index 비교로 한다. 본문은 #770을 V0 기준선으로 링크하고 소유 경로를 GameListSpecification, game H2/PostgreSQL tests, comparison script, PostgreSQL vendor migration, ERD, measurement results로 선언한다. #863와 cache/cursor/materialized view는 제외한다.

사람 승인 전 T 후보는 정확히 다음 다섯 개다.

~~~
- T1: V1/V2/V3은 GET /api/games의 Slice data 키·기본 정렬·필터 ANY/ALL·인증/오류 계약과 페이지 중복·누락 없음이 V0와 같다.
- T2: V1은 theme·mechanism game ID 집합의 uncorrelated subquery로 relation 의미를 보존하고, 대량 game ID를 JVM IN 목록으로 만들지 않는다.
- T3: V2/V3은 PostgreSQL Flyway forward migration으로 theme_id 단일 index를 (theme_id, game_id)로 교체하고, H2 계약을 변경하지 않는다.
- T4: V0~V3은 같은 170,005 fixture fingerprint, 동일 runner SHA, 네 round의 warm-up 5 + 순차 20회 × 여섯 scenario를 모두 VALID로 보존한다.
- T5: 선정 후보는 각 scenario median p95가 V0의 105% 이내이고 relation-theme-mechanism·complex median p95가 모두 V0보다 낮으며, 여섯 scenario의 slowest SQL EXPLAIN과 base count 부재 capture를 보존한다.
~~~

- [ ] **Step 3: 사람의 최신 전체 T1…T5 승인 코멘트를 기다린다.**

Expected: 승인 전에는 Task 3 이후의 코드·migration·측정을 시작하지 않는다. 승인 뒤 issue URL과 comment URL을 ADR-0081 검증·결과 문서에 링크한다.

## Task 3: variant artifact 비교기를 TDD로 추가

**Files:**
- Create: scripts/measurements/game-list-variant-comparison.mjs
- Create: scripts/measurements/game-list-variant-comparison.test.mjs
- Modify: docs/measurements/game-list-740-baseline.md

**Interfaces:**
- Consumes: game-list-baseline.mjs가 만든 status, runnerFileSha256, dataset, warmUpRuns, measuredRuns, results JSON
- Produces: compareVariants(artifactSpecs) -> status, fixture, variants, gates, selectedVariant JSON과 Markdown report

- [ ] **Step 1: 가짜 artifact로 comparator 실패 테스트를 작성한다.**

각 fixture helper는 6개 scenario와 summary.p50Ms, summary.p95Ms, summary.maxMs를 가진 success artifact를 만든다. 다음 test를 먼저 추가한다.

~~~
test('각 variant의 네 artifact와 같은 fixture fingerprint가 없으면 비교를 거절한다', () => {
  assert.throws(() => compareVariants(missingRoundArtifacts), /V2.*4/);
  assert.throws(() => compareVariants(mismatchedFixtureArtifacts), /fixture/);
});

test('한 scenario라도 V0 median p95의 105%를 넘으면 후보를 탈락시킨다', () => {
  const result = compareVariants(regressionArtifacts({ variant: 'V1', scenario: 'keyword' }));
  assert.equal(result.variants.V1.gates.noRegression, false);
  assert.equal(result.selectedVariant, null);
});

test('relation과 complex가 모두 낮아진 후보만 선정한다', () => {
  assert.equal(compareVariants(passingArtifacts()).selectedVariant, 'V3');
});

test('같은 relation·complex 합계면 V1, V2, V3 순으로 선택한다', () => {
  assert.equal(compareVariants(tiedArtifacts()).selectedVariant, 'V1');
});
~~~

- [ ] **Step 2: 실패를 확인한다.**

Run:

~~~
node --test scripts/measurements/game-list-variant-comparison.test.mjs
~~~

Expected: module과 compareVariants가 아직 없으므로 FAIL.

- [ ] **Step 3: artifact validator와 gate calculator를 구현한다.**

CLI input은 --artifact V0:1:path/to/artifact.json 형식을 반복해 정확히 16개 받는다. parser는 variant V0~V3와 round 1~4만 허용한다.

~~~
const REQUIRED_SCENARIOS = [
  'base', 'keyword', 'player-count', 'relation-theme-mechanism',
  'complex', 'flags-upcoming-exact',
];

function medianOfFour(values) {
  const sorted = [...values].sort((left, right) => left - right);
  return (sorted[1] + sorted[2]) / 2;
}

function passesVariant(candidate, control) {
  return REQUIRED_SCENARIOS.every((scenario) =>
    candidate[scenario].p95Ms <= control[scenario].p95Ms * 1.05,
  )
    && candidate['relation-theme-mechanism'].p95Ms < control['relation-theme-mechanism'].p95Ms
    && candidate.complex.p95Ms < control.complex.p95Ms;
}
~~~

성공 artifact는 status success, warmUpRuns 5, measuredRuns 20, scenario success, 20개의 200 sample을 가져야 한다. 16개 artifact의 runnerFileSha256, dataset.fixtureId, dataset.fixtureManifestSha256, dataset.bggIdSetSha256, dataset.metadata는 같아야 한다. serverCommit과 container ID는 variant별로 달라도 된다.

- [ ] **Step 4: JSON과 Markdown 결과를 작성한다.**

--output JSON에는 variant·scenario의 네 batch p50/p95/max, median p95, V0 대비 비율, gate, selectedVariant를 쓴다. JSON과 raw artifact는 측정자의 로컬 evidence 디렉터리에 남기고, --markdown-output에는 V0~V3 표·batch/server commit 요약·탈락 사유·선정 결론을 커밋한다.

- [ ] **Step 5: Green과 기존 runner 회귀를 확인한다.**

Run:

~~~
node --test scripts/measurements/game-list-variant-comparison.test.mjs
node --test scripts/measurements/game-list-baseline.test.mjs
~~~

Expected: valid/reject/tie 및 기존 fixture/provenance runner 계약이 모두 PASS.

- [ ] **Step 6: baseline runbook과 함께 커밋한다.**

baseline 문서에는 16 artifact 입력 명령과 4-round order를 추가한다. 대표 실행은 아래 형식을 쓴다.

~~~
measurement_root="${ALBAM_MATE_GAME_LIST_EVIDENCE_ROOT:?set local game-list-867 evidence root}"

node scripts/measurements/game-list-variant-comparison.mjs \
  --artifact V0:1:"$measurement_root/http/v0-r1.json" \
  --artifact V0:2:"$measurement_root/http/v0-r2.json" \
  --artifact V0:3:"$measurement_root/http/v0-r3.json" \
  --artifact V0:4:"$measurement_root/http/v0-r4.json" \
  --artifact V1:1:"$measurement_root/http/v1-r1.json" \
  --artifact V1:2:"$measurement_root/http/v1-r2.json" \
  --artifact V1:3:"$measurement_root/http/v1-r3.json" \
  --artifact V1:4:"$measurement_root/http/v1-r4.json" \
  --artifact V2:1:"$measurement_root/http/v2-r1.json" \
  --artifact V2:2:"$measurement_root/http/v2-r2.json" \
  --artifact V2:3:"$measurement_root/http/v2-r3.json" \
  --artifact V2:4:"$measurement_root/http/v2-r4.json" \
  --artifact V3:1:"$measurement_root/http/v3-r1.json" \
  --artifact V3:2:"$measurement_root/http/v3-r2.json" \
  --artifact V3:3:"$measurement_root/http/v3-r3.json" \
  --artifact V3:4:"$measurement_root/http/v3-r4.json" \
  --evidence-root "$measurement_root/sql-captures" \
  --output "$measurement_root/comparison.json" \
  --markdown-output docs/measurements/results/game-list-740/game-list-770-relation-variant-comparison-2026-08-19.md

git add scripts/measurements/game-list-variant-comparison.mjs \
  scripts/measurements/game-list-variant-comparison.test.mjs \
  docs/measurements/game-list-740-baseline.md
git commit -m 'feat: 게임 목록 성능 후보 비교기 추가'
~~~

## Task 4: V1 uncorrelated relation query shape를 별도 branch에서 구현

**Files:**
- Modify: src/main/java/cloud/bamsongi/albammate/game/repository/GameListSpecification.java
- Modify: src/test/java/cloud/bamsongi/albammate/game/GameMatchModeHttpIntegrationTest.java
- Modify: src/postgresTest/java/cloud/bamsongi/albammate/game/GameListFilterPostgresTest.java

**Interfaces:**
- Consumes: GameListSearchCriteria themes/themeMatch/mechanisms/mechanismMatch
- Produces: GameListSpecification.from(criteria)가 relation table game-ID subquery를 root id IN predicate로 결합

- [ ] **Step 1: V0 characterization을 추가한다.**

GameMatchModeHttpIntegrationTest에는 theme 2개·mechanism 2개를 가진 game, 각 그룹 하나만 모두 가진 game, 다른 playerCount game을 만든다. theme ANY/ALL과 mechanism ANY/ALL의 4조합에서 content IDs를 고정한다.

GameListFilterPostgresTest에는 같은 조합을 size 1, page 0/1/2로 실행해 전체 ID가 한 번씩만 나오고 마지막 hasNext가 false인지 확인한다.

- [ ] **Step 2: characterization tests를 V0에서 실행한다.**

Run:

~~~
./gradlew test --tests cloud.bamsongi.albammate.game.GameMatchModeHttpIntegrationTest --rerun --fail-fast
./gradlew postgresTest --tests cloud.bamsongi.albammate.game.GameListFilterPostgresTest --rerun --fail-fast
~~~

Expected: 최적화 전 의미를 고정하는 characterization이므로 Green이 정상이다.

- [ ] **Step 3: theme·mechanism subquery를 uncorrelated game ID 집합으로 바꾼다.**

GameListSpecification의 addThemePredicate와 addMechanismPredicate에서 relation game equals root 조건을 제거한다. subquery가 game ID를 직접 반환하고 outer root id가 그 집합에 포함되는지 확인한다.

~~~
var subquery = query.subquery(Long.class);
Root<GameThemeRelation> relation = subquery.from(GameThemeRelation.class);
var theme = relation.join("theme");
subquery.select(relation.get("game").get("id"));
subquery.where(theme.get("code").in(themes));

if (themeMatch == ThemeMatch.ALL) {
    subquery.groupBy(relation.get("game").get("id"));
    subquery.having(criteriaBuilder.equal(
        criteriaBuilder.countDistinct(theme.get("code")), (long) themes.size()));
}
predicates.add(root.get("id").in(subquery));
~~~

mechanism도 같은 game-ID subquery를 쓰되 isPublic true 조건과 countDistinct(mechanism.code) ALL 조건을 넣는다. category·player preference·played·upcoming 조건은 바꾸지 않는다.

- [ ] **Step 4: V1 tests와 convention을 Green으로 만든다.**

Run:

~~~
./gradlew test --tests cloud.bamsongi.albammate.game.GameMatchModeHttpIntegrationTest --rerun --fail-fast
./gradlew postgresTest --tests cloud.bamsongi.albammate.game.GameListFilterPostgresTest --rerun --fail-fast
./gradlew conventionCheck
~~~

Expected: ANY/ALL·AND·Slice boundary tests와 Checkstyle이 PASS.

- [ ] **Step 5: V1을 독립 commit으로 보존한다.**

~~~
git add src/main/java/cloud/bamsongi/albammate/game/repository/GameListSpecification.java \
  src/test/java/cloud/bamsongi/albammate/game/GameMatchModeHttpIntegrationTest.java \
  src/postgresTest/java/cloud/bamsongi/albammate/game/GameListFilterPostgresTest.java
git commit -m 'refactor: 게임 relation 필터 후보 조회 방식 변경'
~~~

## Task 5: V2 PostgreSQL theme relation index를 별도 branch에서 구현

**Files:**
- Create: src/main/resources/db/vendor-migration/postgresql/V33__replace_game_theme_relation_theme_index.sql
- Create: src/postgresTest/java/cloud/bamsongi/albammate/game/GameThemeRelationIndexPostgresTest.java
- Modify: docs/ERD.md

**Interfaces:**
- Consumes: ix_game_theme_relations_theme_id와 game_theme_relations(game_id, theme_id) primary key
- Produces: ix_game_theme_relations_theme_game (theme_id, game_id); leading theme_id는 FK 역방향 조회에도 계속 사용 가능

- [ ] **Step 1: version을 재확인하고 failing PostgreSQL test를 작성한다.**

현재 base의 최대 migration은 V31이지만 열린 PR #862가 V32를 사용하므로 V33를 계획한다. Task 실행 직전 main/vendor migration과 열린 PR의 최대 version을 재확인하고 더 높으면 그 다음 번호로 migration filename과 test expected version/script를 같이 바꾼다.

GameThemeRelationIndexPostgresTest에는 아래 사실을 확인하는 test를 쓴다.

~~~
assertEquals(1, jdbc.queryForObject(
    "select count(*) from flyway_schema_history where version = ? and success = true and script = ?",
    Integer.class, "33", "V33__replace_game_theme_relation_theme_index.sql"));
assertEquals(0, jdbc.queryForObject(
    "select count(*) from pg_indexes where schemaname = 'public' and indexname = 'ix_game_theme_relations_theme_id'",
    Integer.class));
String definition = jdbc.queryForObject(
    "select indexdef from pg_indexes where schemaname = 'public' and indexname = 'ix_game_theme_relations_theme_game'",
    String.class);
assertTrue(definition.contains("(theme_id, game_id)"));
~~~

- [ ] **Step 2: migration과 ERD를 구현한다.**

Migration은 PostgreSQL 전용으로 아래 두 문장만 가진다.

~~~
DROP INDEX IF EXISTS ix_game_theme_relations_theme_id;

CREATE INDEX ix_game_theme_relations_theme_game
    ON game_theme_relations (theme_id, game_id);
~~~

ERD의 GAME_THEME_RELATIONS 문장은 single theme_id index가 아니라 (theme_id, game_id) index가 uncorrelated theme game-ID 후보와 FK 역방향 조회를 함께 지원한다고 바꾼다. index-only 변경이므로 Entity는 수정하지 않는다.

- [ ] **Step 3: PostgreSQL migration과 documentation을 Green으로 만든다.**

Run:

~~~
./gradlew postgresTest --tests cloud.bamsongi.albammate.game.GameThemeRelationIndexPostgresTest --rerun --fail-fast
./gradlew postgresTest --tests cloud.bamsongi.albammate.SchemaValidationPostgresTest --rerun --fail-fast
node scripts/docs/check-doc-links.mjs
~~~

Expected: Testcontainers에 새 forward migration이 적용되고 old index가 남지 않으며 새 index와 ERD가 일치한다.

- [ ] **Step 4: V2를 독립 commit으로 보존한다.**

~~~
git add src/main/resources/db/vendor-migration/postgresql/V33__replace_game_theme_relation_theme_index.sql \
  src/postgresTest/java/cloud/bamsongi/albammate/game/GameThemeRelationIndexPostgresTest.java \
  docs/ERD.md
git commit -m 'feat: 게임 테마 관계 역방향 인덱스 교체'
~~~

## Task 6: V0~V3 clean build와 4-round sequential evidence 수집

**Files:**
- Create locally: 16개 raw JSON/CSV와 variant·scenario SQL capture·EXPLAIN text under the local evidence root
- Create: 측정 결과 요약 Markdown under docs/measurements/results/game-list-740/

**Interfaces:**
- Consumes: V0 Slice commit, V1 commit, V2 commit, V3(V1+V2) commit, fixture manifest, baseline runner
- Produces: comparator가 읽는 local valid raw artifact 16개와 V0/V1/V2/V3 SQL·EXPLAIN evidence

- [ ] **Step 1: variant worktree와 commit을 고정한다.**

V0는 Slice commit, V1은 Task 4 commit, V2는 Task 5 commit, V3는 V1+V2만 합친 clean commit이다. 각 worktree에서 runner path가 clean이고 server commit이 40자리인지 확인한다.

~~~
git status --porcelain -- scripts/measurements/game-list-baseline.mjs
git rev-parse HEAD
~~~

- [ ] **Step 2: 각 batch를 동일 logical fixture 복제본에서 시작한다.**

V2/V3 migration은 해당 candidate DB에만 적용한다. V0/V1 batch에 V2 index가 남거나 games·BGG ID set·metadata가 다르면 INVALID다.

- [ ] **Step 3: 4-round order로 baseline runner를 실행한다.**

각 기동 variant에서 app1/app2/proxy ID를 읽고 다음을 실행한다.

~~~
app1_container="$(docker compose ps -q spring-1)"
app2_container="$(docker compose ps -q spring-2)"
proxy_container="$(docker compose ps -q proxy)"
fixture_manifest="docs/measurements/results/game-list-740/game-list-770-fixture-170005-manifest.json"

node scripts/measurements/game-list-baseline.mjs \
  --dataset-manifest "$fixture_manifest" \
  --server-commit "$(git rev-parse HEAD)" \
  --server-container "app1=$app1_container" \
  --server-container "app2=$app2_container" \
  --proxy-container "$proxy_container" \
  --warm-up 5 \
  --runs 20
~~~

Run order:

~~~
round 1: V0 → V1 → V2 → V3
round 2: V1 → V2 → V3 → V0
round 3: V2 → V3 → V0 → V1
round 4: V3 → V0 → V1 → V2
~~~

각 output JSON/CSV는 `v0-r1`부터 `v3-r4`까지 variant·round를 드러내는 이름으로 보존한다. non-200, timeout, source dirty, runner/image/fixture mismatch 결과는 삭제하지 않고 failed artifact로 보존하되 comparator input으로 쓰지 않는다.

- [ ] **Step 4: 여섯 scenario SQL capture와 EXPLAIN을 수집한다.**

각 variant에서 base, keyword, player-count, relation-theme-mechanism, complex, flags-upcoming-exact의 실제 SQL과 duration을 capture한다. scenario별 slowest SQL과 relation/complex content SQL은 실제 bind 값을 넣은 별도 `.sql` 파일로 보존한다. 그 파일에는 먼저 `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)`를 쓰고, 다음 줄에 capture한 읽기 전용 `SELECT` 전문을 그대로 붙여 `psql -f`로 실행한다. 임의의 축약 SQL이나 다른 batch의 bind 값을 쓰지 않는다.

report에는 planning/execution time, shared hit/read, scan 종류, rows estimate/actual, sort/spill, correlated loops, index searches, content/validation/related statement 수를 기록한다. base capture에서는 fetch first 25와 game exact count SQL 부재를 확인한다.

- [ ] **Step 5: runner completeness를 확인한다.**

Run:

~~~
node --test scripts/measurements/game-list-baseline.test.mjs
~~~

Expected: comparator input 16개는 모두 success, 6 scenario, scenario당 20개의 200 sample, 동일 fixture fingerprint다. 하나라도 아니면 selected variant를 만들지 않고 INVALID 사유를 보고한다.

## Task 7: comparator로 하나를 선택하거나 V0 유지 결론을 낸다

**Files:**
- Create: docs/measurements/results/game-list-740/game-list-770-relation-variant-comparison-2026-08-19.md
- Modify: docs/adr/game/0081-game-list-relation-filter-performance-selection.md
- Modify: docs/measurements/game-list-740-baseline.md

**Interfaces:**
- Consumes: local valid raw artifacts와 SQL/EXPLAIN captures
- Produces: selectedVariant가 V1, V2, V3, 또는 null인 재현 가능한 결론

- [ ] **Step 1: comparator를 실행한다.**

Task 3에 기록한 16-artifact command를 그대로 실행한다.

Expected: output JSON에는 four-batch p50/p95/max, median p95, V0 ratio, gates, selectedVariant가 있다.

- [ ] **Step 2: 사람이 읽을 수 있는 report를 완성한다.**

Markdown에는 여섯 scenario의 V0/V1/V2/V3 median p50/p95/max, 5% 회귀 판정, relation·complex 개선 판정, batch/server commit 요약, six-scenario EXPLAIN summary를 넣는다. raw HTTP/SQL/EXPLAIN 원본은 로컬 evidence에만 남기며, HTTP p95와 one-shot EXPLAIN execution time을 같은 통계로 합산하지 않는다.

- [ ] **Step 3: 기계 출력과 같은 선택 규칙을 적용한다.**

~~~
all 16 batch valid
AND each candidate scenario median p95 <= V0 median p95 * 1.05
AND relation median p95 < V0 median p95
AND complex median p95 < V0 median p95
→ pass candidate 중 relation+complex median p95 합 최소
→ tie: V1, V2, V3 순
→ pass 없음: selectedVariant=null, V0 유지
~~~

- [ ] **Step 4: ADR 검증 절만 갱신한다.**

ADR-0081의 승인된 결정 본문은 수정하지 않는다. 검증 절에는 selected variant 또는 V0 유지, raw result, H2/PostgreSQL test, runner/artifact, SQL/EXPLAIN 근거를 넣는다. #863 동시 부하 검증은 미검증으로 남긴다.

- [ ] **Step 5: 결과 문서만 커밋한다.**

~~~
git add docs/measurements/results/game-list-740 \
  docs/measurements/game-list-740-baseline.md \
  docs/adr/game/0081-game-list-relation-filter-performance-selection.md
git commit -m 'docs: 게임 목록 relation 성능 비교 결과 기록'
~~~

## Task 8: 선정 후보만 final branch에 적용하고 검증

**Files:**
- Modify: selectedVariant에 필요한 Task 4/5 파일만
- Modify: 결과·ADR path만 필요한 범위

**Interfaces:**
- Consumes: Task 7 selectedVariant
- Produces: 검증된 selected candidate 하나 또는 code 변경 없는 V0 유지

- [ ] **Step 1: final diff를 selected variant로 제한한다.**

selectedVariant V1이면 V1 query shape files만, V2면 migration/index/ERD/test files만, V3면 두 set만 가져온다. null이면 code/migration을 가져오지 않는다. 탈락 variant commit과 helper는 final branch에 남기지 않는다.

- [ ] **Step 2: focused regression을 Green으로 만든다.**

Run:

~~~
./gradlew test --tests cloud.bamsongi.albammate.game.GameMatchModeHttpIntegrationTest --rerun --fail-fast
./gradlew postgresTest --tests cloud.bamsongi.albammate.game.GameListFilterPostgresTest --rerun --fail-fast
./gradlew postgresTest --tests cloud.bamsongi.albammate.game.GameThemeRelationIndexPostgresTest --rerun --fail-fast
node --test scripts/measurements/game-list-baseline.test.mjs scripts/measurements/game-list-variant-comparison.test.mjs
node scripts/docs/check-doc-links.mjs
~~~

If V1 is selected, omit the index-only test. If V2 is selected, omit only V1-specific additions that are not in final diff. Disk/Testcontainers infrastructure failures are blocked evidence, never Green.

- [ ] **Step 3: final diff와 commit 범위를 확인한다.**

Run:

~~~
git diff --check origin/develop...HEAD
git diff --stat origin/develop...HEAD
git status --short
~~~

Expected: selected candidate, its tests, ADR/result evidence, required ERD/migration docs만 포함하며 frontend/API/cache/concurrency 범위가 섞이지 않는다.

- [ ] **Step 4: 코드와 evidence를 분리해 커밋한다.**

~~~
case "$selected_variant" in
  V1)
    git add src/main/java/cloud/bamsongi/albammate/game/repository/GameListSpecification.java \
      src/test/java/cloud/bamsongi/albammate/game/GameMatchModeHttpIntegrationTest.java \
      src/postgresTest/java/cloud/bamsongi/albammate/game/GameListFilterPostgresTest.java
    git commit -m 'refactor: 게임 relation 조회 성능 개선 적용'
    ;;
  V2)
    git add src/main/resources/db/vendor-migration/postgresql/V33__replace_game_theme_relation_theme_index.sql \
      src/postgresTest/java/cloud/bamsongi/albammate/game/GameThemeRelationIndexPostgresTest.java \
      docs/ERD.md
    git commit -m 'feat: 게임 테마 관계 역방향 인덱스 교체'
    ;;
  V3)
    git add src/main/java/cloud/bamsongi/albammate/game/repository/GameListSpecification.java \
      src/test/java/cloud/bamsongi/albammate/game/GameMatchModeHttpIntegrationTest.java \
      src/postgresTest/java/cloud/bamsongi/albammate/game/GameListFilterPostgresTest.java
    git commit -m 'refactor: 게임 relation 조회 성능 개선 적용'

    git add \
      src/main/resources/db/vendor-migration/postgresql/V33__replace_game_theme_relation_theme_index.sql \
      src/postgresTest/java/cloud/bamsongi/albammate/game/GameThemeRelationIndexPostgresTest.java \
      docs/ERD.md
    git commit -m 'feat: 게임 테마 관계 역방향 인덱스 교체'
    ;;
  null)
    ;;
  *)
    echo "unexpected selected_variant: $selected_variant" >&2
    exit 1
    ;;
esac

git add docs/measurements/results/game-list-740 \
  docs/measurements/game-list-740-baseline.md \
  docs/adr/game/0081-game-list-relation-filter-performance-selection.md
git commit -m 'docs: 게임 목록 relation 성능 개선 근거 추가'
~~~

selectedVariant가 null이면 code commit을 만들지 않고 V0 유지 결과 문서만 커밋한다.
