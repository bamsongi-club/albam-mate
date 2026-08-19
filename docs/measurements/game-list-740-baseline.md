# GAME 목록 17만 건 baseline 측정 — #740

## 목적

17만 건 게임 데이터셋에서 `GET /api/games`의 지연을 재현 가능하게 측정하고, HTTP 지연을 DB 조회와 애플리케이션 조립 구간으로 분해해 다음 성능 개선의 최소 범위를 결정한다.

이 문서는 **측정/진단만** 다룬다. 인덱스 추가, 쿼리 변경, `Page` 계약 변경, 캐시, 프론트 로딩 전략은 #740 범위가 아니다.

2026-08-19의 역사적 v4 `Page` baseline은 [최신 develop 결과](results/game-list-740/game-list-740-2026-08-19.md)에 보존했다. 같은 fixture에서 #770 전후를 맞춘 비교와 raw count 제거 근거는 [#770 Slice 실측](results/game-list-740/game-list-770-2026-08-19.md)에 분리해 보존한다.

현재 evidence는 2026-08-19의 별도 runner 실행 3회 JSON/CSV 집합이다. 단일 batch의 빠른 p95를 canonical 값으로 고르지 않으며, batch별 편차와 실행 조건을 결과 문서에 함께 기록한다. `game-list-740-2026-08-18T14-36-38.069Z.json/csv`는 보강 전 runner의 역사 기록으로만 보존하며 #740 완료 근거에서 제외한다.

Before Page baseline의 v4 ZIP SHA-256·SQL checksum·import 순서는 역사 기록으로 보존한다. 다만 #770 Slice의 현재 `170,005`건 DB는 보관된 `01-games-full.sql`(175,234행, 다른 BGG ID 집합)과 일치하지 않는다. 따라서 비교 fixture는 [canonical 지문 manifest](results/game-list-740/game-list-770-fixture-170005-manifest.json)로 식별하며, v4 직접 적재 lineage를 주장하지 않는다.

## 고정 측정 조건

- 대상: local compose proxy `http://127.0.0.1:5173`
- 데이터: games `170,005`건, [canonical 지문 manifest](results/game-list-740/game-list-770-fixture-170005-manifest.json)
- games BGG ID 집합 SHA-256: `75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744`
- metadata row count: mechanism `428488`, theme `461973`, category `17337`, player preference `263463`
- canonical games scalar SHA-256: `a72c105d0c8affef5c6ffa402412fdd19bab40421248e69d6f1352980bde5fc0`
- canonical metadata pair/value SHA-256: mechanism `90fb78dc92e862ae56580f8e084675edec18f4c891962334fe9ad6073807bcbc`, theme `f87ae77ee80a1620bf85ccc919689882c68b809b19dc30517ce866e046591656`, category `86c0025bdc1699d938eea93a2ac479b7e67b7a353c1cd3123c82e2703c9f665d`, player preference `3af8c769e2a626013a5a301162ede612a795d8b478aacf339ff1861fc3d276ea`
- rooms row count/canonical SHA-256: `60` / `801ad410686ecd6cd5c8e1b75e0b90ee7574f6483b351d42d8b851d1f4682a16`
- 페이지: `page=0`, `size=24`
- 시나리오별 warm-up 5회 후 실측 20회 이상
- p50/p95: nearest-rank 방식 `ceil(p * N)`
- 각 실측은 순차 요청으로 실행하여 동시 부하가 baseline에 섞이지 않게 한다.
- `--response-contract slice`는 200 응답의 `data` 키 집합을 정확히 `content`·`page`·`size`·`hasNext`로 검증하고 `totalElements`·`totalPages`를 거부한다. `--response-contract page`는 같은 fixture에서 `totalElements`·`totalPages`의 내부 정합성과 `170,005`건 total을 검증한다. 두 계약 모두 요청한 `page=0`, `size=24`, content 길이, `hasNext`를 확인하고 실제 page metadata는 raw sample JSON에 보존한다.

## 실행

```bash
app1_container="$(docker compose ps -q spring-1)"
app2_container="$(docker compose ps -q spring-2)"
proxy_container="$(docker compose ps -q proxy)"
fixture_manifest="docs/measurements/results/game-list-740/game-list-770-fixture-170005-manifest.json"

node scripts/measurements/game-list-baseline.mjs \
  --dataset-manifest "$fixture_manifest" \
  --response-contract slice \
  --server-commit <측정 대상 서버의 40자리 commit SHA> \
  --server-container "app1=$app1_container" \
  --server-container "app2=$app2_container" \
  --proxy-container "$proxy_container"
```

필요하면 다음처럼 변경한다.

```bash
node scripts/measurements/game-list-baseline.mjs \
  --base-url http://127.0.0.1:5173 \
  --warm-up 5 \
  --runs 20 \
  --dataset-size 170005 \
  --dataset-manifest "$fixture_manifest" \
  --response-contract slice \
  --server-commit <측정 대상 서버의 40자리 commit SHA> \
  --server-container "app1=$app1_container" \
  --server-container "app2=$app2_container" \
  --proxy-container "$proxy_container"
```

`--dataset-manifest`는 측정 DB의 고정 canonical 지문을 담은 버전 관리 파일이다. runner는 시작·종료에 같은 Compose PostgreSQL의 games scalar 행, 정렬 BGG ID 집합, mechanism/theme/category의 정확한 relation pair, player preference 값, rooms의 query-relevant 행을 manifest와 대조한다. 따라서 다른 `170,005`행 fixture를 올려 두고 count만 맞춘 실행은 성공 artifact를 만들 수 없다. 결과에는 manifest SHA-256과 각 canonical 지문의 기대값·관측값을 함께 기록한다. 이 manifest는 현재 측정 DB snapshot의 식별자이며, 보관된 v4 ZIP 또는 `01-games-full.sql`이 직접 적재 원본이라는 주장을 대신하지 않는다. `--server-commit`은 40자리 SHA여야 하며, 정확히 두 `--server-container`(`app1`, `app2`)의 OCI revision label·동일 image ID·Compose project/network와 `--proxy-container`의 proxy service/network가 시작/종료 시점 모두 일치해야 한다. runner는 각 discovery/실측 응답의 `X-Albam-Mate-Upstream` 역할과 `X-Albam-Mate-Upstream-Address`를 inspect한 해당 Spring container network 주소에 대조한다. 러너를 실행한 작업 디렉터리의 commit, 러너 파일 SHA-256, 측정 전후 source clean 여부는 결과에 `runnerCommit`, `runnerFileSha256`, `runnerSourceClean`으로 별도 기록하며 `serverCommit`을 대신하지 않는다. 요청별 timeout은 기본 30초이며 `--request-timeout-ms`로 조정할 수 있고, 사전 discovery의 games/theme/mechanism 요청에도 동일하게 적용된다. 이 직접 fixture 확인은 `PGAPPNAME=game-list-baseline-fixture-check`로 구분되어 앱 요청의 SQL capture와 섞이지 않는다.

canonical games 지문은 목록 결과에 영향을 주는 name·alias·image·인원·시간·complexity·release year·age·popularity 등의 scalar 값을 포함하고, relation 지문은 정확한 pair/value를 포함한다. rooms 지문은 `game_id`, `room_type`, `start_at`, `status`를 포함한다. 원본 SQL/ZIP lineage는 별도 raw source 또는 capture 없이는 주장하지 않는다.

러너는 현재 데이터에서 유효한 값을 자동으로 선택한다.

- `keyword`: 기본 목록 첫 게임의 `name` 또는 `englishName`
- `theme`: `/api/game-themes`의 첫 유효 code
- `mechanism`: `/api/game-mechanisms`의 첫 유효 code

따라서 데이터셋이 바뀌어도 존재하지 않는 relation code 때문에 400 응답을 성능 표본으로 기록하지 않는다. 실제 사용한 값, URL, 응답 page metadata는 결과 JSON에 보존한다.

결과는 기본적으로 `docs/measurements/results/game-list-740/` 아래 JSON/CSV로 생성한다. 결과 파일을 커밋할 때에는 실행 당시 `runnerCommit`, `serverCommit`, 데이터 건수, SHA-256이 맞는지 먼저 확인한다. 측정 중 non-200 또는 네트워크 오류가 발생하면 `status=failed`와 이미 수집한 raw sample을 함께 저장하며, 실패 report는 정상 baseline으로 사용하지 않는다.

## #867 V0~V3 relation·complex 후보 비교

[#867](https://github.com/bamsongi-club/albam-mate/issues/867)은 #770 Slice를 V0 control로 두고 V1(query shape), V2(PostgreSQL theme relation index), V3(결합)를 비교한다. 선택·rollback 경계는 [ADR-0080](../adr/game/0080-game-list-relation-filter-performance-selection.md)에서 정한다. cache·cursor·projection과 동시 HTTP·CPU·오류율은 이 비교에 넣지 않으며 후자는 [#863](https://github.com/bamsongi-club/albam-mate/issues/863)에서만 다룬다.

각 variant는 독립 fixture DB에서 여섯 scenario를 warm-up 5회 뒤 순차 20회로 실행한다. V0~V3은 다음 순서를 정확히 한 번씩 써서 총 16개 성공 artifact를 만든다.

| round | 실행 순서 |
| --- | --- |
| 1 | V0 → V1 → V2 → V3 |
| 2 | V1 → V2 → V3 → V0 |
| 3 | V2 → V3 → V0 → V1 |
| 4 | V3 → V0 → V1 → V2 |

각 artifact는 runner file SHA-256, games `170,005`, BGG ID set SHA-256, relation metadata와 시작·종료 fixture provenance가 같아야 한다. 실패 artifact는 삭제하지 않지만 아래 비교기의 입력으로 쓰지 않는다. `serverCommit`·컨테이너 ID는 candidate마다 달라도 되며 runner source SHA와 fixture fingerprint는 달라지면 안 된다.

16개가 모두 성공한 뒤 다음을 실행한다.

```bash
node scripts/measurements/game-list-variant-comparison.mjs \
  --artifact V0:1:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r1.json \
  --artifact V0:2:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r2.json \
  --artifact V0:3:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r3.json \
  --artifact V0:4:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v0-r4.json \
  --artifact V1:1:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r1.json \
  --artifact V1:2:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r2.json \
  --artifact V1:3:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r3.json \
  --artifact V1:4:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v1-r4.json \
  --artifact V2:1:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r1.json \
  --artifact V2:2:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r2.json \
  --artifact V2:3:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r3.json \
  --artifact V2:4:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v2-r4.json \
  --artifact V3:1:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r1.json \
  --artifact V3:2:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r2.json \
  --artifact V3:3:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r3.json \
  --artifact V3:4:docs/measurements/results/game-list-740/game-list-867-2026-08-19/http/v3-r4.json \
  --evidence-root docs/measurements/results/game-list-740/game-list-867-2026-08-19/sql-captures \
  --output docs/measurements/results/game-list-740/game-list-770-relation-variant-comparison-2026-08-19.json \
  --markdown-output docs/measurements/results/game-list-740/game-list-770-relation-variant-comparison-2026-08-19.md
```

비교기는 scenario별 네 batch p50/p95/max와 p95 median을 보존한다. V1~V3은 여섯 scenario 모두 V0 p95의 105% 이내이고, `relation-theme-mechanism`과 `complex` p95가 모두 V0보다 낮아야 한다. 통과 후보가 여럿이면 두 scenario p95 합이 더 작은 것을 고르고 동률이면 V1, V2, V3 순으로 고른다. 하나도 통과하지 않으면 `selectedVariant=null`이며 V0를 유지한다.

각 variant의 여섯 scenario는 실제 SQL capture와 가장 느린 읽기 SQL의 `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)`를 별도로 보존한다. 이 one-shot EXPLAIN execution time은 HTTP p95와 같은 통계가 아니므로 합산하지 않는다. base capture는 `size + 1` 조회와 game exact count SQL 부재를 보여야 한다.

2026-08-19 실측 결과는 [#867 relation·complex 후보 비교 결과](results/game-list-740/game-list-867-2026-08-19.md)에 보존했다. 16개 artifact가 모두 `VALID`였고, 여섯 scenario의 5% p95 회귀 제한과 relation·complex 개선을 모두 통과한 V1만 선택했다. V2/V3은 relation plan을 더 좁혔지만 다른 scenario의 p95 회귀로 탈락했다.

## 측정 매트릭스

| 시나리오 | 목적 |
| --- | --- |
| `base` | 필터 없는 기본 목록의 content/Slice/sort 비용 |
| `keyword` | `lower(name) like '%...%'` 경로 비용 |
| `player-count` | 일반 컬럼 범위 조건 비용 |
| `relation-theme-mechanism` | theme/mechanism correlated subquery 비용 |
| `complex` | 여러 조건 AND 결합 비용 |
| `flags-upcoming-exact` | `upcomingOnly` + exact player 범위 경로 비용 |

## 현재 코드 기준 기본 요청의 논리적 DB 경로

기본 익명 요청

```text
GET /api/games?upcomingOnly=false&playerCountExact=false&page=0&size=24
```

은 현재 코드상 다음 경로를 지난다.

1. `gameRepository.findBy(specification, query -> query.slice(pageable))`
   - `size + 1` 페이지 content query로 다음 페이지 존재 여부 판정
   - 전체 건수 count query 없음
2. 현재 페이지의 game id 최대 24개를 대상으로 `findUpcomingRoomCounts(...)`
3. `GameListItem.from(...)`으로 24개 DTO 조립
4. `ApiResponse<GameListSliceResponse<...>>` JSON 직렬화

기본 요청에는 mechanism/category/theme 코드 검증 쿼리가 없다. 해당 쿼리는 각 필터가 실제로 전달될 때만 수행한다. 익명 사용자이므로 `userPlayedGameRepository` 조회도 없다.

또한 `GameListItem.from`은 `Game`의 scalar 필드와 `upcomingRoomCount`, `playedByMe`만 읽으므로 기본 목록 DTO 조립에서 category/theme/mechanism lazy collection N+1을 전제로 진단하면 안 된다.

## HTTP 시간과 애플리케이션 시간 분리

`GameController.listGames`에는 이미 다음 로그가 있다.

```text
event=game_search_completed outcome=success resultCount=... durationMs=...
```

이 `durationMs`는 `gameQueryService.findPage(...)`와 `GameListSliceResponse.from(...)`까지 측정하고, HTTP JSON 직렬화와 프록시 왕복은 포함하지 않는다.

따라서 같은 단일 요청을 기준으로 다음을 비교한다.

```text
HTTP total time
- controller durationMs
≈ serialization + Spring MVC response write + local proxy overhead
```

이 차이는 직렬화 시간의 완전한 단독 측정값은 아니므로 **serialization/network/proxy 잔여 구간**으로 기록한다. 잔여 구간이 병목 후보로 보일 때만 별도 JFR 또는 MVC instrumentation을 후속 측정한다.

## #770 전 Page 기반 SQL 개수와 N+1 역사 기록

아래 2026-08-19 capture는 #770 전 `Page` 계약에서 얻은 역사 진단 기록이다. 당시 기본 익명 요청의 코드상 기대치는 일반적으로 다음 3개 논리 쿼리였다. 이 count evidence는 현재 Slice 완료 근거로 사용하지 않는다.

1. game content
2. game count
3. page game ids의 upcoming room count

필터 시나리오에서는 mechanism/category/theme 유효성 검증용 count가 선행될 수 있었다.

확인할 것:

- 동일 형태 SQL이 page content 개수에 비례해 반복되는가
- 동일 relation 조회가 중복 실행되는가
- count query가 content query와 비슷하거나 더 길었는가
- relation filter의 correlated `exists`/`count(distinct ...)`가 count query에도 그대로 들어갔는가

**SQL 개수를 추정값으로 완료 보고서에 쓰지 않는다. 실제 캡처 로그의 statement 수를 기록한다.**

## #770 전 Page 기반 content / count / related query 분해

`JpaSpecificationExecutor.findAll(specification, pageable)` 하나의 호출 안에서 content와 count가 실행됐으므로 당시 Java 메서드 벽시계만으로 둘을 분리하면 안 됐다.

분해 기준은 DB statement 로그다.

- `select ... from games ... order by ... fetch first/limit ...` → content
- `select count(...) from games ...` → count
- room group/count + `game_id in (...)` → upcoming related query
- 필터 코드 존재 검증 query → validation query

당시 각 SQL의 DB 실행 시간을 요청 단위로 합산했다. 기본 요청은 [동일 요청의 controller/SQL/HTTP capture](results/game-list-740/game-list-740-2026-08-19-base-request-capture.log)로 다음처럼 대응했다.

| evidence | HTTP total | controller | content | count | validation | related | SQL execute sum | controller - SQL | HTTP - controller |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base request, 1 capture | 41.503ms | duration 미기록 | 0.646ms | 19.123ms | 0ms | 0.115ms | 19.884ms | 산출 안 함 | 산출 안 함 |

따라서 이 Page capture에서 기본 요청의 DB statement 1순위는 `count`였다. raw capture는 HTTP 시작/종료 시각, 고유 measurement ID의 단일 proxy `/api/games` access log, `X-Albam-Mate-Upstream: app2`와 inspect IP 대조, 해당 controller log, 하나의 PostgreSQL `app2` PID `56`의 content/count/related statement를 함께 보존한다. Spring은 host port를 publish하지 않고 health check도 game-list URL을 호출하지 않는다. 당시 console profile은 controller duration key-value를 내보내지 않았으므로 `controller - SQL`과 `HTTP - controller` residual은 산출하지 않았다. HTTP p95는 20회 분포이고 위 분해는 단일 요청이므로 서로 같은 통계량처럼 비교하지 않는다.

현재 #770 Slice raw capture는 앱 요청에서 `size + 1` content query와 upcoming related query만 분류하며 app count statement가 없어야 한다. 필터 시나리오의 코드 검증 query는 별도로 분류한다. Compose PostgreSQL fixture의 games row count·BGG ID 집합·metadata relation count 직접 확인은 `PGAPPNAME=game-list-baseline-fixture-check`를 사용하므로 앱 요청 SQL 개수에 포함하지 않는다.

relation filter의 `296.095ms` EXPLAIN은 기본 요청의 수치가 아니라 relation 시나리오 후보이며, 이 값을 근거로 #740의 기본 요청 후속 범위를 결정하지 않는다.

## EXPLAIN 기준

각 시나리오에서 실제 캡처된 **가장 느린 SQL**을 바인드 값까지 재현해 다음을 실행한다.

```sql
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)
<captured SQL>;
```

반드시 기록할 항목:

- Planning Time / Execution Time
- Seq Scan / Index Scan / Bitmap Scan 여부
- 실제/추정 rows 차이
- Sort Method, Memory, disk spill 여부
- shared hit/read/dirtied/written blocks
- loops가 큰 correlated subplan 존재 여부
- count query에서 relation subquery가 전체 games 집합에 반복되는지

`EXPLAIN ANALYZE`는 쿼리를 실제 실행하므로 이 문서의 대상처럼 읽기 전용 SELECT에만 사용한다.

## 정적 코드에서 이미 확인되는 후보

측정 전 코드만 보고 확정할 수 있는 것은 구조뿐이다.

- 기본 정렬은 `popularityScore DESC, name ASC, id ASC`이다.
- keyword는 `lower(name) LIKE '%keyword%'` 형태다.
- category/theme/mechanism/player preference/played filter는 correlated subquery를 사용한다.
- `Slice`는 `size + 1` content query로 다음 페이지 존재 여부를 판정하며 total count query를 수행하지 않는다.
- 기본 익명 목록 DTO 생성 자체는 24건이고 relation collection을 순회하지 않는다.

따라서 **17만 건 전체를 JSON으로 내려서 느리다**라는 가설은 현재 API 구조와 맞지 않는다. 한 페이지는 최대 24건을 반환한다. 병목 후보는 우선 `170,005`건 모집합에서의 정렬/필터/Slice content 실행계획과 relation filter의 subquery 비용으로 검증해야 한다.

## #740 완료 판정

다음이 모두 채워져야 #740을 닫는다.

- [x] 측정 당시 runner/server commit SHA 기록
- [x] Before Page의 역사적 v4 provenance와 #770 Slice fixture의 관측 지문을 분리해 보존
- [x] 각 시나리오 warm-up 후 20회 이상의 raw sample 보존
- [x] 각 시나리오 p50/p95/max/status 기록
- [x] 요청 1회 SQL 개수와 유형 기록
- [x] 최신 `develop` 반영 server/runner로 Before Page baseline 재실행, `runnerFileSha256`/`runnerSourceClean` 기록
- [x] 서버 OCI revision label·동일 image ID·proxy Compose network·upstream 역할/address를 runner artifact에 기록하고 전후 대조
- [x] 동일 조건의 별도 runner 실행 3회와 batch별 p50/p95 편차 기록
- [x] #770 Slice 계약에서 discovery timeout과 Compose PostgreSQL fixture 지문 전후 대조 검증 ([#770 Slice 실측](results/game-list-740/game-list-770-2026-08-19.md))
- [x] N+1/중복 query 여부 판정
- [ ] #770 Slice content/validation/related 구간의 대표 실행계획 시간 기록
- [x] 가장 느린 SQL의 `EXPLAIN (ANALYZE, BUFFERS)` 보존
- [x] 기본 요청의 HTTP/upstream/controller/SQL 시간 창 대응 기록
- [x] #770 Slice 기본 요청 raw capture에 HTTP window·고유 proxy measurement ID·upstream 역할/address·PostgreSQL timestamp/PID/application name·content/related statement 보존 ([capture](results/game-list-740/game-list-770-2026-08-19-base-request-capture.log))
- [x] #770 Slice 기본 요청이 app count query 없이 `size + 1` content query를 사용하는지 숫자로 확인 ([capture](results/game-list-740/game-list-770-2026-08-19-base-request-capture.log))
- [ ] 기본 요청 전체 잔여 구간의 세부 계측 및 후속 #770 단일 범위 승인
- [ ] 개선은 별도 후속 이슈로 최소 범위만 생성

## 후속 이슈 분리 원칙

측정 결과에 따라 하나만 우선 생성한다.

- count가 1순위 → count 비용을 줄이는 최소 쿼리/계약 개선 이슈
- content sort/filter가 1순위 → 해당 predicate/order를 위한 실행계획/인덱스 이슈
- relation correlated subquery가 1순위 → relation query shape 또는 인덱스 이슈
- upcoming room count가 1순위 → room aggregate 조회 개선 이슈
- DB가 충분히 빠르고 residual이 1순위 → DTO/materialization/serialization 계측 이슈

한 이슈에 인덱스 + pagination + cache + frontend progressive loading을 함께 넣지 않는다. #740은 병목을 숫자로 고른 뒤 다음 한 단계만 여는 진단 이슈다.
