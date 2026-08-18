# GAME 목록 17만 건 baseline 측정 — #740

## 목적

17만 건 게임 데이터셋에서 `GET /api/games`의 지연을 재현 가능하게 측정하고, HTTP 지연을 DB 조회와 애플리케이션 조립 구간으로 분해해 다음 성능 개선의 최소 범위를 결정한다.

이 문서는 **측정/진단만** 다룬다. 인덱스 추가, 쿼리 변경, `Page` 계약 변경, 캐시, 프론트 로딩 전략은 #740 범위가 아니다.

2026-08-18 실제 측정 결과는 [game-list-740-2026-08-18.md](results/game-list-740/game-list-740-2026-08-18.md)에 기록했다. 해당 결과는 PR #771 head의 170,005건 전용 DB에서 수집한 HTTP baseline, 실제 SQL statement capture, `EXPLAIN (ANALYZE, BUFFERS)`를 포함한다.

## 고정 측정 조건

- 대상: local compose proxy `http://127.0.0.1:5173`
- 데이터: games `170,005`건
- 입력 ZIP SHA-256: `09da6ecbc6f3be18b4233a26a4715b7af0011929b3e5c2b549b1b021dc5fa079`
- 적재 순서: `01 → 01b → 02 → 03 → 04 → 05 → 06 → 07`
- 페이지: `page=0`, `size=24`
- 시나리오별 warm-up 5회 후 실측 20회 이상
- p50/p95: nearest-rank 방식 `ceil(p * N)`
- 각 실측은 순차 요청으로 실행하여 동시 부하가 baseline에 섞이지 않게 한다.

## 실행

```bash
node scripts/measurements/game-list-baseline.mjs \
  --server-commit <측정 대상 서버 commit SHA>
```

필요하면 다음처럼 변경한다.

```bash
node scripts/measurements/game-list-baseline.mjs \
  --base-url http://127.0.0.1:5173 \
  --warm-up 5 \
  --runs 20 \
  --dataset-size 170005 \
  --server-commit <측정 대상 서버 commit SHA>
```

`--server-commit`은 측정 대상 서버의 image/runtime provenance에서 확인한 값이어야 한다. 러너를 실행한 작업 디렉터리의 commit은 결과에 `runnerCommit`으로 별도 기록하며 `serverCommit`을 대신하지 않는다.

러너는 현재 데이터에서 유효한 값을 자동으로 선택한다.

- `keyword`: 기본 목록 첫 게임의 `name` 또는 `englishName`
- `theme`: `/api/game-themes`의 첫 유효 code
- `mechanism`: `/api/game-mechanisms`의 첫 유효 code

따라서 데이터셋이 바뀌어도 존재하지 않는 relation code 때문에 400 응답을 성능 표본으로 기록하지 않는다. 실제 사용한 값과 URL은 결과 JSON에 보존한다.

결과는 기본적으로 `docs/measurements/results/game-list-740/` 아래 JSON/CSV로 생성한다. 결과 파일을 커밋할 때에는 실행 당시 `runnerCommit`, `serverCommit`, 데이터 건수, SHA-256이 맞는지 먼저 확인한다. 측정 중 non-200 또는 네트워크 오류가 발생하면 `status=failed`와 이미 수집한 raw sample을 함께 저장하며, 실패 report는 정상 baseline으로 사용하지 않는다.

## 측정 매트릭스

| 시나리오 | 목적 |
| --- | --- |
| `base` | 필터 없는 기본 목록의 content/count/sort 비용 |
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

1. `gameRepository.findAll(specification, pageable)`
   - 페이지 content query
   - `Page`의 total element 계산을 위한 count query
2. 현재 페이지의 game id 최대 24개를 대상으로 `findUpcomingRoomCounts(...)`
3. `GameListItem.from(...)`으로 24개 DTO 조립
4. `ApiResponse<PageResponse<...>>` JSON 직렬화

기본 요청에는 mechanism/category/theme 코드 검증 쿼리가 없다. 해당 쿼리는 각 필터가 실제로 전달될 때만 수행한다. 익명 사용자이므로 `userPlayedGameRepository` 조회도 없다.

또한 `GameListItem.from`은 `Game`의 scalar 필드와 `upcomingRoomCount`, `playedByMe`만 읽으므로 기본 목록 DTO 조립에서 category/theme/mechanism lazy collection N+1을 전제로 진단하면 안 된다.

## HTTP 시간과 애플리케이션 시간 분리

`GameController.listGames`에는 이미 다음 로그가 있다.

```text
event=game_search_completed outcome=success resultCount=... durationMs=...
```

이 `durationMs`는 `gameQueryService.findPage(...)`와 `PageResponse.from(...)`까지 측정하고, HTTP JSON 직렬화와 프록시 왕복은 포함하지 않는다.

따라서 같은 단일 요청을 기준으로 다음을 비교한다.

```text
HTTP total time
- controller durationMs
≈ serialization + Spring MVC response write + local proxy overhead
```

이 차이는 직렬화 시간의 완전한 단독 측정값은 아니므로 **serialization/network/proxy 잔여 구간**으로 기록한다. 잔여 구간이 병목 후보로 보일 때만 별도 JFR 또는 MVC instrumentation을 후속 측정한다.

## SQL 개수와 N+1 확인

측정 실행에서는 Hibernate SQL 로그 또는 PostgreSQL statement 로그 중 하나를 사용해 요청 1회에 발생한 SQL을 보존한다. 기본 익명 요청의 코드상 기대치는 일반적으로 다음 3개 논리 쿼리다.

1. game content
2. game count
3. page game ids의 upcoming room count

필터 시나리오에서는 mechanism/category/theme 유효성 검증용 count가 선행될 수 있다.

확인할 것:

- 동일 형태 SQL이 page content 개수에 비례해 반복되는가
- 동일 relation 조회가 중복 실행되는가
- count query가 content query와 비슷하거나 더 긴가
- relation filter의 correlated `exists`/`count(distinct ...)`가 count query에도 그대로 들어가는가

**SQL 개수를 추정값으로 완료 보고서에 쓰지 않는다. 실제 캡처 로그의 statement 수를 기록한다.**

## content / count / related query 분해

`JpaSpecificationExecutor.findAll(specification, pageable)` 하나의 호출 안에서 content와 count가 실행되므로 Java 메서드 벽시계만으로 둘을 분리하면 안 된다.

분해 기준은 DB statement 로그다.

- `select ... from games ... order by ... fetch first/limit ...` → content
- `select count(...) from games ...` → count
- room group/count + `game_id in (...)` → upcoming related query
- 필터 코드 존재 검증 query → validation query

각 SQL의 DB 실행 시간을 요청 단위로 합산하고 다음 표를 채운다.

| scenario | HTTP p95 | controller | content | count | validation | related | residual |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| base | TBD | TBD | TBD | TBD | 0 | TBD | TBD |
| keyword | TBD | TBD | TBD | TBD | 0 | TBD | TBD |
| player-count | TBD | TBD | TBD | TBD | 0 | TBD | TBD |
| relation-theme-mechanism | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| complex | TBD | TBD | TBD | TBD | TBD | TBD | TBD |
| flags-upcoming-exact | TBD | TBD | TBD | TBD | 0 | 경로별 기록 | TBD |

`residual`은 `controller/HTTP`와 DB statement 합의 차이로 단정하지 않는다. Hibernate materialization, DTO 조립, 트랜잭션 경계, JSON serialization, proxy 비용이 섞일 수 있으므로 별도 칼럼으로 남긴다.

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
- `Page`를 유지하므로 결과 목록 외 total count 비용이 존재한다.
- 기본 익명 목록 DTO 생성 자체는 24건이고 relation collection을 순회하지 않는다.

따라서 **17만 건 전체를 JSON으로 내려서 느리다**라는 가설은 현재 API 구조와 맞지 않는다. 한 페이지는 24건만 반환한다. 병목 후보는 우선 `170,005`건 모집합에서의 정렬/필터/content/count 실행계획과 relation filter의 subquery 비용으로 검증해야 한다.

## #740 완료 판정

다음이 모두 채워져야 #740을 닫는다.

- [ ] 측정 당시 runner/server commit SHA 기록
- [ ] 데이터 170,005건 및 입력 ZIP SHA-256 확인
- [ ] 각 시나리오 warm-up 후 20회 이상의 raw sample 보존
- [ ] 각 시나리오 p50/p95/max/status 기록
- [ ] 요청 1회 SQL 개수와 유형 기록
- [ ] N+1/중복 query 여부 판정
- [ ] content/count/validation/related 구간 DB 시간 분리
- [ ] 가장 느린 SQL의 `EXPLAIN (ANALYZE, BUFFERS)` 보존
- [ ] 병목 1순위를 숫자로 확정
- [ ] 개선은 별도 후속 이슈로 최소 범위만 생성

## 후속 이슈 분리 원칙

측정 결과에 따라 하나만 우선 생성한다.

- count가 1순위 → count 비용을 줄이는 최소 쿼리/계약 개선 이슈
- content sort/filter가 1순위 → 해당 predicate/order를 위한 실행계획/인덱스 이슈
- relation correlated subquery가 1순위 → relation query shape 또는 인덱스 이슈
- upcoming room count가 1순위 → room aggregate 조회 개선 이슈
- DB가 충분히 빠르고 residual이 1순위 → DTO/materialization/serialization 계측 이슈

한 이슈에 인덱스 + pagination + cache + frontend progressive loading을 함께 넣지 않는다. #740은 병목을 숫자로 고른 뒤 다음 한 단계만 여는 진단 이슈다.
