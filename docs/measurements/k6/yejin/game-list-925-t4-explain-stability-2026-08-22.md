# Issue #925 T4 — 실행계획 안정성 EXPLAIN(ANALYZE, BUFFERS) 측정 (2026-08-22)

## 배경

V3(theme·mechanism ANY 필터를 독립 IN 서브쿼리 2개로 분리)가 관계 매칭 건수 큰 조합에서 planner가 나쁜 계획을 골라 느려짐이 확인돼 반려됐고, EXISTS 기반 상관 semi-join 구조로 재작성한 V4(커밋 `f94a7beb`)가 구현·TDD Green 확인됐다. 사용자가 승인한 T4 계약(GitHub issue #925 comment):

> T4 — 실행계획 안정성(신규): 관계 매칭 건수가 큰 조합(BOOK_BGG_1117/HAND_MANAGEMENT)과 작은 조합(희귀 테마 하나) 양쪽에서 EXPLAIN (ANALYZE, BUFFERS)를 뜨고, 두 경우 모두 V4의 buffers hit·실행시간이 control(현재 발행된 uncorrelated 단일 서브쿼리 구조) 이하임을 확인합니다. 어느 한쪽이라도 control보다 나쁘면 V4를 적용하지 않고 결과만 보고합니다.

본 문서는 그 측정 결과를 기록한다.

## 대상

- **control**: `origin/develop` HEAD 기준 워크트리 `/private/tmp/albam-925-control-build` (branch `tmp/issue-925-control-only`, HEAD `fbed058b0b91a610fd7bed0d47026fc564990cc8`), 이미지 `albam925-control:fbed058b` 재사용.
- **V4**: `/private/tmp/albam-mate-925-v2-v3` (branch `perf/issue-925-v2-v3`, HEAD `f94a7beb`, 커밋: "refactor: 게임 목록 relation ANY 필터를 EXISTS semi-join으로 재작성"), 새로 빌드한 이미지 `albam925-v4:f94a7beb` 사용.

## 조합 선정

fixture(`canonical-data-only.dump`, 74MB data-only dump) 복원 후 postgres에 직접 질의해서 관계 매칭 건수를 확인했다.

### (a) 큰 조합

- `theme=BOOK_BGG_1117` → `game_theme_relations` 매칭 4,258행
- `mechanism=HAND_MANAGEMENT` → `game_mechanism_relations` 매칭 24,419행

(진단에서 이미 쓴 조합, 이번 fixture에서도 동일 건수로 재확인됨)

### (b) 작은 조합

각 theme/mechanism code별 relation 매칭 건수를 집계해 하위권을 확인:

| theme code | 매칭 건수 | mechanism code | 매칭 건수 |
| --- | --- | --- | --- |
| THIRD_PARTY_EXPANSION_BGG_3129 | 31 | LANE_BATTLER | 1 |
| KOREAN_WAR_BGG_1091 | **156** | AUCTION_TURN_ORDER_UNTIL_PASS | **96** |
| ARABIAN_BGG_1052 | 249 | VISUAL_RESTRICTION | 2 |

최하위 후보(THIRD_PARTY_EXPANSION_BGG_3129 × LANE_BATTLER 등)는 두 필터를 모두 만족하는 게임 교집합이 0건이라 실질적인 쿼리 실행 형태를 보기 어려워, **교집합이 존재하는 조합 중 두 코드 모두 매칭 건수가 낮은** `theme=KOREAN_WAR_BGG_1091`(156행) / `mechanism=AUCTION_TURN_ORDER_UNTIL_PASS`(96행)를 작은 조합으로 선택했다(교집합 1건 확인).

## 절차

1. control·V4 각각 postgres+redis만 기동 후 spring-1 단일 인스턴스(App2/proxy 미사용)를 `SPRING_FLYWAY_LOCATIONS`에서 `classpath:db/local` 제외한 override로 기동해 Flyway가 빈 스키마 생성.
2. `pg_restore --data-only --disable-triggers --no-owner`로 `canonical-data-only.dump` 적재 (`room_status_correction_progress` PK 중복 경고 1건은 기존에도 알려진 무해한 경고).
3. 각 조합으로 `GET /api/games?theme=<code>&mechanism=<code>&page=0&size=24` 요청 1회를 보내고, postgres `log_statement=all` 로그에서 Hibernate가 실제로 생성한 SQL을 그대로 캡처(추측 없이).
4. 캡처한 SQL에 파라미터를 대입하고 `EXPLAIN (ANALYZE, BUFFERS)`를 붙여 `psql -f`로 실행, Execution Time과 Buffers(shared hit/read/written)를 기록.

## 실제 캡처된 SQL

### control (양쪽 조합 동일 형태 — uncorrelated 단일 서브쿼리)

```sql
select g1_0.id, ... from games g1_0
where g1_0.id in (
  (select distinct gtr1_0.game_id
   from game_theme_relations gtr1_0
   join game_themes t1_0 on t1_0.id = gtr1_0.theme_id,
   game_mechanism_relations gmr1_0
   join game_mechanisms m1_0 on m1_0.id = gmr1_0.mechanism_id
   where gtr1_0.game_id = gmr1_0.game_id
     and t1_0.code in ($1) and m1_0.code in ($2) and m1_0.is_public)
)
order by g1_0.popularity_score desc, g1_0.name, g1_0.id
offset $3 rows fetch first $4 rows only
```

### V4 (양쪽 조합 동일 형태 — EXISTS 상관 semi-join)

```sql
select g1_0.id, ... from games g1_0
where exists (
  select 1 from game_theme_relations gtr1_0
  join game_themes t1_0 on t1_0.id = gtr1_0.theme_id
  where gtr1_0.game_id = g1_0.id and t1_0.code in ($1)
)
and exists (
  select 1 from game_mechanism_relations gmr1_0
  join game_mechanisms m1_0 on m1_0.id = gmr1_0.mechanism_id
  where gmr1_0.game_id = g1_0.id and m1_0.code in ($2) and m1_0.is_public
)
order by g1_0.popularity_score desc, g1_0.name, g1_0.id
offset $3 rows fetch first $4 rows only
```

각 파라미터는 실제 캡처된 값(theme/mechanism code, offset=0, limit=25)으로 대입해 EXPLAIN을 실행했다.

## EXPLAIN 결과

| 조합 | 대상 | Execution Time | Buffers (shared) | 비고 |
| --- | --- | --- | --- | --- |
| 큰 조합 (BOOK_BGG_1117 / HAND_MANAGEMENT) | control | **53.023 ms** | hit=73748 (read/written 없음, 총 73,748) | Nested Loop + Unique(Sort) — mechanism 24,419행을 game_theme_relations_pkey로 1건씩 룩업 |
| 큰 조합 | V4 | **238.842 ms** | hit=89346 read=12339 written=917 (총 101,685+) | Hash Semi Join — theme 4,258행·mechanism 24,419행 각각 HashAggregate로 전체 스캔 후 조인 |
| 작은 조합 (KOREAN_WAR_BGG_1091 / AUCTION_TURN_ORDER_UNTIL_PASS) | control | **2.967 ms** | hit=310 (총 310) | 동일한 Nested Loop 구조, 매칭 건수가 적어 매우 빠름 |
| 작은 조합 | V4 | **1.819 ms** | hit=464 read=178 (총 642) | 동일 Hash Semi Join 구조 |

재현성 확인을 위해 V4 양쪽 조합을 캐시가 데워진 상태로 재실행했으나 결과는 일관됐다:

| 조합 | 대상 | Execution Time (재실행) | Buffers (총) |
| --- | --- | --- | --- |
| 큰 조합 | V4 재실행 | 235.894 ms | hit=87187 read=12968 written=8330 (총 100,155+) |
| 작은 조합 | V4 재실행 | 2.717 ms | hit=382 read=23 (총 405) |

재실행에서도 큰 조합은 여전히 control보다 약 4.4배 느리고 buffers가 더 많으며, 작은 조합도 buffers 총량은 control(310)보다 많다(405~642). 즉 캐시 워밍업 차이로 설명되는 결과가 아니라, planner가 선택한 계획 형태(Hash Semi Join + 양쪽 관계 테이블 전체 스캔) 자체가 control의 계획(선택적 nested-loop 룩업)보다 구조적으로 더 많은 블록을 건드린다.

## 판정표

| 조합 | Buffers hit(V4) ≤ control | Execution Time(V4) ≤ control | 판정 |
| --- | --- | --- | --- |
| 큰 조합 | **불충족** (101,685+ > 73,748) | **불충족** (238.8ms > 53.0ms) | FAIL |
| 작은 조합 | **불충족** (642 > 310) | 충족 (1.8ms < 2.97ms, 단 재실행 시 2.7ms로 근접) | FAIL (buffers 기준 불충족) |

## T4 최종 판정: **FAIL (미통과)**

두 조합 모두 통과해야 하는데, 큰 조합은 buffers·실행시간 두 지표 모두 control보다 나쁘고, 작은 조합도 buffers 지표가 control보다 나쁘다(실행시간만 근소하게 낫거나 비슷).

### 원인 분석 (참고)

V4의 EXISTS 서브쿼리 2개를 PostgreSQL planner가 상관 서브쿼리로 그대로 실행하지 않고, 각각을 `HashAggregate`로 미리 집계한 뒤 `Hash Semi Join`으로 붙이는 계획을 선택했다. 이는 사실상 V3가 겪었던 "관계 매칭 건수가 크면 각 필터를 독립적으로 전체 스캔한 뒤 합치는" 문제와 동일한 성격이다 — EXISTS로 쿼리를 작성해도 planner가 이를 상관 nested-loop semi-join이 아니라 uncorrelated-style hash 집계 조인으로 재작성할 수 있다는 뜻이다. 반면 control의 uncorrelated IN 서브쿼리는 이 fixture·통계에서는 mechanism 쪽(더 selective하지 않은 count)을 outer로 두고 game_theme_relations_pkey(복합 PK, game_id 선두)를 이용한 nested-loop 룩업을 선택해 오히려 더 효율적으로 동작했다.

## 권고: T5(HTTP 부하 gate) 진행 여부

**T4가 미통과이므로 T5로 넘어가지 않는 것을 권고한다.** 계약상 "어느 한쪽이라도 control보다 나쁘면 V4를 적용하지 않고 결과만 보고"하기로 했으므로, 코드는 되돌리거나 추가 수정하지 않고 현재 EXISTS 구현 상태 그대로 두었다. 다음 방향(다른 쿼리 shape 재설계, planner 힌트, 통계 조정 등)은 사용자가 결정해야 한다.

## 산출물

- 이 문서: `/private/tmp/albam-mate-925-v2-v3/docs/measurements/k6/yejin/game-list-925-t4-explain-stability-2026-08-22.md` (커밋하지 않음)
- raw 데이터: `/private/tmp/albam-925-v2v3-measurement.uqio4e/t4-explain-2026-08-22/{control-large,control-small,v4-large,v4-small}/` (query.sql, explain-result.txt, pg-capture.log, 재실행 결과 포함)
