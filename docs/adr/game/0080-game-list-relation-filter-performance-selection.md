# ADR-0080: 게임 목록 relation filter는 query shape·theme index를 실측 비교해 선택

- 상태: 승인됨
- 작성일: 2026-08-19
- 결정일: 2026-08-19
- 관련: [#770 게임 목록 Slice 전환](https://github.com/bamsongi-club/albam-mate/issues/770), [#863 게임 목록 동시 부하·자원·오류율 검증](https://github.com/bamsongi-club/albam-mate/issues/863), [게임 목록 17만 건 기준선](../../measurements/game-list-740-baseline.md), [relation·complex 후보 비교 설계](../../superpowers/specs/2026-08-19-game-list-relation-performance-comparison-design.md), [ADR-0050: 게임 메타데이터 카탈로그와 상세 필터](0050-game-metadata-catalog-and-filters.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

#770은 `GET /api/games`의 exact `totalElements`·`totalPages`를 없애고 `hasNext`를 쓰는 Slice 계약으로 바꾼다. 이 변경은 전체 game count SQL을 제거하지만, relation filter가 큰 후보 집합을 만들고 각 game마다 다른 relation을 확인하는 비용까지 없애지는 않는다.

17만 건 관측 fixture의 기존 relation 실행계획에서 `theme + mechanism` 요청은 mechanism relation에서 약 `24,419`개 game ID를 만든 뒤 game primary-key lookup·theme relation 확인·theme code 판정을 반복했다. content SQL 실행 시간은 `296.095ms`였고, `complex`도 같은 mechanism 후보 폭에서 시작했다. 최종 정렬 대상은 각각 70개·38개였으므로 top-N sort만을 주 병목으로 단정할 근거는 없다.

Slice 전환 전후 HTTP tail은 batch마다 크게 흔들렸다. Page와 Slice의 역사적 결과를 한 표에 놓고 넓은 성능 개선을 주장하면 fixture lineage와 runtime 변동을 혼동한다. 따라서 현재 Slice를 같은 실험 안에서 다시 측정한 V0로 삼고, query shape와 theme relation index를 독립·결합 후보로 비교해야 한다.

판단 기준은 다음과 같다.

1. #770이 확정한 Slice API·정렬·필터·페이지 경계와 exact count 제거를 보존하는가.
2. relation·complex 경로의 median p95를 실제로 낮추면서 다른 다섯 시나리오를 5% 이상 악화하지 않는가.
3. 같은 170,005건 fixture, runner, warm-cache 순차 HTTP 규칙에서 재현 가능한가.
4. 인덱스의 write·storage·배포 비용이나 cache·projection의 무효화 경계를 성능 수치와 섞지 않는가.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| V0: 현재 Slice 유지 | API와 DB schema를 더 바꾸지 않는다. | relation/complex fan-out은 그대로 남고 구조적 병목이 해소되지 않을 수 있다. | 비교 기준선 및 통과 후보가 없을 때의 선택 |
| V1: theme·mechanism query shape만 교체 | relation 테이블에서 game ID 집합을 만들면 per-game correlated 확인을 줄일 수 있다. | `ANY`·`ALL`, 복수 relation, 다른 predicate와의 AND, 정렬·Slice 경계가 달라질 수 있다. | 비교 후보 |
| V2: PostgreSQL theme index만 교체 | `theme_id`에서 `game_id`를 찾는 역방향 relation 접근을 더 좁힐 수 있다. | index size·쓰기 비용과 forward migration 배포 비용이 들며 기존 query shape에서는 효과가 작을 수 있다. | 비교 후보 |
| V3: V1과 V2 결합 | query shape가 복합 index를 실제 경로로 쓸 때 추가 이득을 확인할 수 있다. | 원인 분리가 어려워 V1·V2 단독 근거 없이 채택하면 안 된다. | 비교 후보 |
| Redis/query cache | 반복 요청의 응답 시간을 빠르게 만들 수 있다. | DB 병목을 가리고 invalidation·freshness·운영 실패 경계를 새로 만든다. | 제외 |
| materialized projection·비정규화 read model | relation 조회를 사전 계산해 가장 큰 읽기 이득을 기대할 수 있다. | 적재·갱신 정합성, storage, 재생성·rollback을 새로 설계해야 해 이번 좁은 원인 검증보다 범위가 크다. | 제외 |

## 결정

### 1. 현재 Slice를 V0로 두고 후보를 미리 채택하지 않는다

V0는 #770 Slice 구현이다. response `data`는 `content`, `page`, `size`, `hasNext`만 유지하고, 기본 정렬 `popularity_score DESC, name ASC, id ASC`, filter 의미, 인증·입력 검증·오류 envelope, 페이지 중복·누락 없음과 exact game count 부재를 바꾸지 않는다.

코드·migration 작업은 #770의 범위가 아니다. 새 performance feature 이슈의 최신 전체 T1~T5를 사람이 승인한 뒤에만 아래 후보를 구현·측정한다.

### 2. 세 후보를 같은 경계에서 비교한다

- **V1**은 `GameListSpecification`의 theme·mechanism correlated subquery를 relation 테이블이 반환하는 game ID 집합으로 바꾼다. outer game ID는 DB 내부 subquery에 포함되는지만 확인하며, 후보 ID를 JVM 메모리에 모아 큰 `IN (...)` 파라미터 목록으로 다시 보내지 않는다. `ANY`는 하나 이상, `ALL`은 요청한 distinct code 수와 같은 game만 남기고 mechanism의 `isPublic=true` 조건도 유지한다.
- **V2**는 PostgreSQL에서 기존 `ix_game_theme_relations_theme_id` 단일 index를 `ix_game_theme_relations_theme_game (theme_id, game_id)`로 전진 Flyway migration으로 교체한다. 표준 migration·H2 계약은 바꾸지 않으며, 후보 측정은 독립 fixture DB에서만 이 migration을 적용한다.
- **V3**는 V1과 V2를 결합한다. V1과 V2 단독 결과를 같이 보존해 결합 이득을 분리한다.

category·player preference·played·upcoming·숫자 predicate 및 API·frontend는 이 비교에서 변경하지 않는다.

### 3. 비교는 16개의 유효한 순차 HTTP batch로 한다

모든 batch는 games `170,005`, BGG ID set SHA-256 `75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744`, mechanism `428,488`, theme `461,973`, category `17,337`, player preference `263,463`의 관측 fixture 지문을 시작·종료에 대조한다. 이 지문은 보관된 v4 SQL의 직접 적재 계보 주장이 아니라 비교용 동일성 확인이다.

V0~V3은 각 네 round를 다음 순서로 실행한다.

| round | 실행 순서 |
| --- | --- |
| 1 | V0 → V1 → V2 → V3 |
| 2 | V1 → V2 → V3 → V0 |
| 3 | V2 → V3 → V0 → V1 |
| 4 | V3 → V0 → V1 → V2 |

각 batch는 여섯 scenario `base`, `keyword`, `player-count`, `relation-theme-mechanism`, `complex`, `flags-upcoming-exact`에 대해 warm-up 5회 뒤 HTTP 순차 요청 20회를 실행한다. runner file SHA, fixture fingerprint, scenario 이름, 200 response 20개, Slice metadata가 하나라도 맞지 않으면 그 batch는 `INVALID`이며 후보 선택에 쓰지 않는다.

### 4. 회귀·개선 gate를 동시에 통과한 하나만 적용한다

각 scenario의 대표 p95는 해당 variant 네 batch p95를 오름차순 정렬한 뒤 가운데 두 값의 산술평균으로 계산한다. 후보는 다음을 모두 만족해야 한다.

1. 16개 batch가 모두 `VALID`여야 한다.
2. 여섯 scenario 각각의 candidate median p95가 V0 median p95의 `105%` 이하여야 한다.
3. `relation-theme-mechanism`과 `complex` candidate median p95가 모두 V0보다 엄격히 낮아야 한다.
4. H2·PostgreSQL의 filter 의미, 정렬, Slice 경계 회귀 테스트를 통과해야 한다.

여러 후보가 통과하면 relation과 complex median p95 합이 더 작은 것을 택한다. 그 합도 같으면 변경 범위가 작은 V1, V2, V3 순으로 택한다. 통과 후보가 없으면 V0를 유지하고 query rewrite·index migration을 적용하지 않는다.

### 5. HTTP 통계와 SQL 실행계획을 분리해 남긴다

각 variant의 여섯 scenario는 실제 SQL capture와 가장 느린 읽기 SQL의 `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)`를 보존한다. relation·complex는 content SQL plan도 별도로 남긴다. base capture는 `size + 1` 조회와 game exact count SQL 부재를 보여야 한다.

HTTP p50/p95/max와 한 번 실행한 `EXPLAIN ANALYZE` execution time은 같은 통계가 아니므로 합치거나 서로의 대체 근거로 쓰지 않는다. 실행계획은 후보 수·scan·sort·spill·correlated loop·index search가 왜 바뀌었는지 설명하는 증거이고, HTTP p95는 사용자 요청 tail gate의 증거다.

## 결과

- 얻는 것:
  - exact count 제거 뒤에도 남은 relation 구조적 병목을 가설이 아니라 같은 fixture의 V0~V3 비교로 판단한다.
  - 빠른 한 번의 실행이나 서로 다른 Page/Slice 결과 대신 4-round p95 gate와 raw artifact로 선택을 재현한다.
  - cache·projection·cursor처럼 API·운영 경계를 넓히는 수단을 섞지 않고 query shape와 index라는 좁은 원인을 검증한다.
- 감수할 비용·위험:
  - 후보마다 clean worktree·독립 fixture DB·4개 batch가 필요해 측정 시간과 Docker/PostgreSQL 자원을 더 쓴다.
  - V1은 Criteria API query shape가 바뀌어 의미 회귀 위험이 있고, V2는 index storage·insert/update cost와 migration lock·배포 순서 위험이 있다.
  - sequential warm-cache 결과만으로 동시성·자원 사용량·production SLO를 주장할 수 없다.
- 후속 작업:
  - 독립 performance issue의 승인 T 계약 뒤 comparator, V1/V2/V3와 H2/PostgreSQL tests를 구현한다.
  - 비교 결과와 raw SQL/EXPLAIN을 이 ADR의 검증 절에 기록하고 선택된 candidate만 final branch에 남긴다.
  - 선택된 구현 뒤 동시 HTTP·CPU·memory·connection·오류율은 [#863](https://github.com/bamsongi-club/albam-mate/issues/863)에서 검증한다.

## 적용·호환·rollback

- 적용: final branch에는 selected variant의 query shape 또는 PostgreSQL migration만 가져온다. 탈락 후보의 code·migration은 final branch에 넣지 않는다.
- 호환: 선택 여부와 무관하게 `GET /api/games`의 Slice data 키와 기존 default sort·filter semantics는 유지한다. H2는 API 의미 회귀를 검증하고 PostgreSQL은 실제 index·planner 근거를 검증한다.
- runtime rollback: V1/V3 query shape에 이상이 보이면 V0 shape로 되돌린다. V2/V3 index는 이미 적용한 Flyway migration을 수정·삭제하지 않으며, 제거가 필요하면 PostgreSQL 검증을 포함한 별도 forward migration으로 처리한다.
- 배포 경계: V2/V3의 index replacement는 후보 DB에서 먼저 검증한다. 운영 적용 전에 index build lock·실행 시간·디스크 여유를 확인하고, 그 결과가 허용되지 않으면 V2/V3을 선택하지 않는다.

## 보류 및 재검토

- 지금 하지 않는 것: Redis/query cache, cursor API, materialized projection, 비정규화 read model, 숫자 range filter용 새 index, 동시 HTTP·CPU·memory·connection·오류율 검증
- 보류 이유: cache·projection은 DB 병목 밖의 새 정합성·운영 결정이고, numeric filter와 동시 부하는 V1/V2 선택의 직접 근거가 아니다.
- 다시 검토할 조건: V0~V3 모두 relation·complex 개선 gate를 통과하지 못할 때, selected candidate의 PostgreSQL plan이 numeric predicate를 다음 병목으로 보일 때, 또는 #863 결과가 resource/error 한계를 보일 때

## 참고 자료

- [게임 목록 17만 건 기준선](../../measurements/game-list-740-baseline.md)
- [#770 Slice 실측 결과](../../measurements/results/game-list-740/game-list-770-2026-08-19.md)
- [relation·complex 후보 비교 설계](../../superpowers/specs/2026-08-19-game-list-relation-performance-comparison-design.md)
- [#770 게임 목록 Slice 전환](https://github.com/bamsongi-club/albam-mate/issues/770)
- [#863 게임 목록 동시 부하·자원·오류율 검증](https://github.com/bamsongi-club/albam-mate/issues/863)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
  - 독립 performance issue의 최신 전체 T1~T5 승인과 V1/V2/V3 구현·H2/PostgreSQL 회귀 테스트
  - 동일 fixture·runner SHA의 16개 valid raw artifact와 comparator 선택 결과
  - 여섯 scenario SQL capture, relation·complex content plan, base exact count 부재 capture
  - 선택된 candidate의 PostgreSQL migration pre-flight와 [#863](https://github.com/bamsongi-club/albam-mate/issues/863) 동시 부하·자원·오류율 결과

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
