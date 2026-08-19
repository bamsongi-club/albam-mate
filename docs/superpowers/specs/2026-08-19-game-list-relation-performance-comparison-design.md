# #770 게임 목록 relation·complex 성능 후보 비교 설계

## 상태와 결정

- 설계 승인: 2026-08-19
- 대상: `GET /api/games`의 `relation-theme-mechanism`, `complex` 조회 경로
- 기준선: exact total을 제거한 현재 Slice 구현(V0)
- 선택 기준: 여섯 시나리오 모두 V0 대비 p95가 5% 이내이고, `relation-theme-mechanism`과 `complex`의 median p95가 모두 V0보다 낮은 후보만 적용한다.
- 분리 범위: 동시 HTTP 부하, App·PostgreSQL 자원, 오류율 검증은 [#863](https://github.com/bamsongi-club/albam-mate/issues/863)에서 수행한다.

이 문서는 후보를 미리 채택하지 않는다. 같은 fixture와 실행 규칙에서 V0~V3을 비교한 뒤 통과한 하나만 후속 구현으로 선택한다. 통과 후보가 없으면 Slice(V0)를 유지한다.

## 문제와 확인된 근거

#770은 목록의 exact `totalElements`·`totalPages`를 없애고 `hasNext` Slice 계약으로 바꿨다. 이 변경은 목록의 exact game count SQL을 제거하지만, relation filter의 구조적 비용까지 없애지는 않는다.

기존 실행계획에서 `theme + mechanism` 요청은 mechanism 관계에서 `24,419`개 game ID를 만든 뒤 다음 작업을 반복했다.

1. `games_pkey`로 game을 `24,419`회 조회한다.
2. 각 game의 theme relation을 `24,419`회 확인한다.
3. theme를 `78,352`회 조회해 code를 판정한다.

그 결과 relation content SQL은 `296.095ms`였고, `complex`도 같은 mechanism 후보 폭에서 시작해 `14,228`개 game을 거친 뒤 theme를 확인했다. 정렬 대상은 각각 70개·38개뿐이므로, 최종 top-N sort 자체가 주된 비용이라는 근거는 없다.

Slice 전환 후 6개 HTTP 시나리오의 tail은 batch마다 크게 흔들렸다. 따라서 기존 Before/After 표를 넓은 성능 개선의 증거로 해석하지 않는다. 새 비교는 현재 Slice를 같은 실험 안의 V0 기준선으로 다시 측정한다.

## 데이터와 계약 불변식

모든 후보는 현재 관측 fixture manifest를 공통 기준으로 사용한다.

- games: `170,005`
- BGG ID 집합 SHA-256: `75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744`
- game-mechanism relation: `428,488`
- game-theme relation: `461,973`
- game-category relation: `17,337`
- game-player preference: `263,463`

이 fixture는 보관된 v4 SQL의 직접 적재 계보가 아니라 관측 지문으로만 식별한다. 각 batch의 시작과 종료에서 runner가 위 지문을 대조하지 못하면 그 batch는 `INVALID`다.

후보가 바꿀 수 없는 계약은 다음과 같다.

- 응답 data 키는 `content`, `page`, `size`, `hasNext`만 가진다. `totalElements`·`totalPages`는 다시 넣지 않는다.
- 기본 정렬은 `popularity_score DESC, name ASC, id ASC`다.
- 기존 필터 의미, 인증·입력 검증·오류 envelope, 게임 상세와 비게임 `PageResponse`는 바꾸지 않는다.
- 페이지 경계에 중복·누락이 없고, `hasNext`는 `size + 1` 또는 동등한 DB 경로로 계산한다.
- 후보 ID를 애플리케이션 메모리에 대량으로 모아 `IN (...)`으로 되돌려 보내지 않는다.

## 비교 후보

| variant | 변경 | 확인하려는 가설 | 주요 위험 |
| --- | --- | --- | --- |
| V0 | 현재 Slice 그대로 | count 제거 뒤의 실제 기준선 | relation/complex fan-out은 유지된다. |
| V1 | relation query shape만 변경 | 관계 테이블에서 시작하는 semi-join 또는 DB 내부 후보 교집합으로 per-game correlated 확인을 줄일 수 있다. | `ANY`·`ALL`, 복수 theme/mechanism, 다른 필터와의 AND, 정렬·페이지 경계 의미가 달라질 수 있다. |
| V2 | 기존 `theme_id` 단일 인덱스를 `game_theme_relations(theme_id, game_id)` 복합 인덱스로 교체 | theme code에서 game ID를 찾는 역방향 relation 접근을 index-only에 가깝게 만들 수 있다. | 인덱스 크기·쓰기 비용·Flyway 배포 비용이 생기며, 기존 query shape만으로는 효과가 작을 수 있다. |
| V3 | V1과 V2를 함께 적용 | 좁힌 query shape가 복합 인덱스를 실제 접근 경로로 쓸 때 추가 이득이 있는지 확인한다. | 원인 분리가 어려우므로 V1/V2 단독 결과 없이 채택하지 않는다. |

V1은 relation code를 relation ID로 해석한 뒤, DB 안에서 relation 테이블 기반 후보를 semi-join 또는 교집합으로 만들고 game 필터·정렬을 적용한다. 어느 relation을 선행할지는 고정된 `theme` 가정이 아니라 실제 plan의 후보 수와 선택도에 따라 정한다. `ALL`은 요청한 code 수와 일치하는 relation만 남기고, `ANY`는 하나 이상을 만족하는 relation만 남겨 현재 의미를 보존한다.

Redis/query cache, cursor API, materialized projection, 비정규화 read model, 숫자 범위용 새 인덱스는 이번 설계에서 제외한다. 캐시·projection은 DB 병목을 가리고 무효화·신선도·운영 경계를 새로 만들며, 숫자 predicate는 V1/V2 뒤의 실행계획이 새 병목으로 확인될 때만 별도 결정한다.

## 측정 설계

### 실행 단위

각 variant는 dirty source가 아닌 독립 commit·동일 revision의 app1/app2 image로 기동한다. V2/V3의 index는 해당 variant의 fixture 복제본에만 적용한다. 모든 variant batch는 같은 logical fixture snapshot에서 시작하므로 V2의 인덱스가 다음 V0/V1 batch에 남지 않는다.

각 batch는 다음을 시작·종료에 기록하고 대조한다.

- server commit, runner commit·file SHA-256·source clean 상태
- app1/app2 OCI revision·공통 image ID·Compose project/network·proxy upstream address
- fixture fingerprint와 PostgreSQL container 식별값
- 사용한 scenario 값과 실제 응답 Slice metadata

응답이 200이 아니거나, fixture·container provenance·Slice contract가 맞지 않거나, runner가 중간에 바뀌면 수치와 무관하게 `INVALID`다.

### 순서와 표본

하드웨어 시간대·재기동 순서가 한 variant에만 유리하지 않게 4회 round-robin으로 실행한다.

| round | 실행 순서 |
| --- | --- |
| 1 | V0 → V1 → V2 → V3 |
| 2 | V1 → V2 → V3 → V0 |
| 3 | V2 → V3 → V0 → V1 |
| 4 | V3 → V0 → V1 → V2 |

각 variant batch는 아래 여섯 시나리오를 순차 요청으로 실행한다. 동시 부하는 #863의 별도 범위다.

- `base`
- `keyword`
- `player-count`
- `relation-theme-mechanism`
- `complex`
- `flags-upcoming-exact`

각 시나리오마다 warm-up 5회 후 20회 실측한다. p50/p95는 nearest-rank `ceil(p * N)`로 계산하고, raw request·응답 상태·upstream을 JSON/CSV에 보존한다. 모든 variant는 네 batch를 가지므로 한 번 빠르게 나온 batch만 대표값으로 고르지 않는다.

### SQL·EXPLAIN 증거

각 variant에서는 여섯 시나리오 각각의 실제 요청 SQL을 capture한다. 해당 시나리오의 가장 느린 SQL을 바인드 값까지 재현해 `EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)`를 보존한다. relation·complex는 content SQL의 plan도 별도로 보존한다.

보고서는 다음을 나란히 적는다.

- planning/execution time, shared hit/read, scan 종류, sort와 spill 여부
- relation 후보 수, 실제/추정 rows, correlated subplan loops, index searches
- content·validation·related SQL의 statement 수와 실행 시간
- base raw capture에서 game exact count SQL이 없는지와 `size + 1` 조회가 유지되는지

`EXPLAIN ANALYZE`는 읽기 전용 captured SELECT에만 사용한다. single raw capture는 HTTP p95와 같은 통계량으로 비교하지 않는다.

## 판정과 선택

각 scenario의 V0 기준값은 네 batch p95를 오름차순 정렬한 가운데 두 값의 산술평균이다. 후보의 같은 scenario 값도 같은 방식으로 계산한다.

1. **유효성 gate**: 모든 batch가 `VALID`이고, 응답·fixture·provenance 계약을 통과해야 한다.
2. **회귀 gate**: 여섯 scenario 각각에서 후보 median p95는 V0 median p95의 105% 이하여야 한다.
3. **개선 gate**: `relation-theme-mechanism`과 `complex`의 후보 median p95는 모두 V0 median p95보다 작아야 한다.
4. **정합성 gate**: H2와 PostgreSQL에서 필터 의미·정렬·페이지 경계 회귀 테스트를 통과해야 한다.

둘 이상의 후보가 통과하면 relation·complex 두 median p95의 합이 더 작은 후보를 우선한다. 그 값까지 같으면 적용 범위가 작은 순서인 V1, V2, V3을 적용한다. 어느 후보도 통과하지 않으면 V0를 유지하고 인덱스·query rewrite를 적용하지 않는다.

## 정확성 검증

V1/V3에는 현재 #770 계약 테스트에 더해 다음을 둔다.

- 단일·복수 theme/mechanism의 `ANY`와 `ALL` 결과 ID가 V0 기준 결과와 일치한다.
- category, keyword, player count, complexity, upcomingOnly, played filter를 relation 조건과 조합한 결과가 V0와 일치한다.
- 첫·중간·마지막 Slice 경계에서 정렬이 유지되고 중복·누락이 없다.
- PostgreSQL 통합 테스트는 실제 relation index와 SQL planner가 있는 상태에서 수행한다. H2는 API 의미 회귀를 확인하되 PostgreSQL 실행계획 근거를 대신하지 않는다.
- V2/V3의 Flyway index migration은 새 version으로만 추가하고, 최신 기본 브랜치와 열린 migration 번호를 다시 확인한 뒤 부여한다.

후보 비교는 구현 변경을 고르는 실험이고, 최종 적용은 선택된 후보의 좁은 diff만 남긴다. 실험에서 탈락한 query/index 변경은 최종 브랜치와 PR에 남기지 않는다.

## 배포·복구 경계

선택된 query shape는 API 계약을 바꾸지 않으므로 feature flag나 이중 읽기를 추가하지 않는다. V2/V3의 index는 additive migration으로 배포한다. 문제가 확인되면 애플리케이션은 V0 또는 V1로 되돌리고, 공유 환경에 적용된 migration은 수정하지 않는다. index 제거가 필요할 때는 별도 forward migration과 PostgreSQL 검증으로 처리한다.

동시 부하·자원·오류율은 이 선택의 완료 조건이 아니다. 선택된 후보가 적용된 뒤 #863에서 별도 profile과 판정 기준을 승인받아 검증한다.

## 범위 밖

- game list 응답의 추가 계약 변경 또는 exact total 복구
- cache, cursor, materialized view, 별도 검색 read model
- 동시성 용량·운영 SLO·production scale 주장
- relation/complex 이외의 새 병목에 대한 인덱스·query 변경
- #770과 무관한 frontend 리팩터링
