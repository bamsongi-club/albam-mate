# issue #925 V3 relation-theme-mechanism 모순 진단 (2026-08-22)

커밋하지 않는다. 순수 진단 문서.

## 배경

- runs=20 (2026-08-21): V3 relation-theme-mechanism p95가 control보다 25% 빠름.
- runs=50 (2026-08-22): 같은 파라미터(`theme=BOOK_BGG_1117&mechanism=HAND_MANAGEMENT`)에서 V3가 control 대비 73% 느려짐. 4라운드 내내 V2보다도 꾸준히 느림 (원시 p95: 86.4/83.0/52.3/40.6ms vs V2 58.2/28.3/27.1/31.1ms).
- 과거 EXPLAIN (ANALYZE, BUFFERS) 단발 실행: control 189ms vs V3 18ms — "DB 자체는 V3가 빠르다"는 증거.
- 두 결과가 모순되어, 인스턴스 교대(App1/App2 프록시)·워밍업 부족(5회) 변수를 제거한 좁은 진단을 수행했다.

## 진단 방법

- App2/proxy 없이 spring-1 단일 인스턴스만 기동, 호스트 포트로 직접 curl (인스턴스 교대 변수 제거).
- fixture: `canonical-data-only.dump`를 Flyway로 빈 스키마 생성한 DB에 `pg_restore --data-only --disable-triggers --no-owner`로 적재 (기존 세션이 검증한 절차 그대로).
  - 매 라운드 `room_status_correction_progress` 테이블 1행 중복키 경고만 발생(job 진행 상태 시드값, 게임/방 데이터에는 영향 없음) — 기존 rerun 절차에서도 동일하게 관찰된 무해한 경고.
- 워밍업 40회 사전 요청으로 JIT을 데운 뒤, 동일 요청을 50회 순차 실행해 응답시간 기록.
- 측정 직후 postgres 로그(`log_statement=all`)에서 그 라운드에 Hibernate가 실제로 만든 SQL(`S_12`, games 조회 쿼리)을 그대로 추출해 `EXPLAIN (ANALYZE, BUFFERS)`로 재실행.
- control과 V3 각각 1회 수행 (정밀 반복측정이 아니라 인과관계를 좁히는 목적).

## (a) 단일 인스턴스 HTTP p50/p95

| 변형 | n | p50 | p95 | min | max |
| --- | --- | --- | --- | --- | --- |
| control | 50 | 20.36ms | 33.56ms | 17.78ms | 43.14ms |
| V3 | 50 | 26.61ms | 32.54ms | 23.53ms | 45.72ms |

raw: `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/{control,v3}/http-times.txt`

단일 인스턴스·충분한 워밍업(40회) 조건에서는 p95만 보면 V3(32.5ms)가 control(33.6ms)보다 미세하게 빠르지만, p50은 V3(26.6ms)가 control(20.4ms)보다 약 30% 높다. runs=50 부하측정에서 본 "73% 느려짐"급 격차는 재현되지 않았지만, V3가 더 빠르다고 할 근거도 약하다 — p50 기준으로는 오히려 V3가 느리다.

## (b) 실제 캡처한 SQL과 EXPLAIN 실행시간

두 변형 모두 `theme=BOOK_BGG_1117&mechanism=HAND_MANAGEMENT` 요청 직후 postgres 로그에서 games 조회 쿼리(`S_12`)를 그대로 추출했다 (원문: `pg-capture.log`, EXPLAIN 스크립트: `explain.sql`, 결과: `explain-result.txt`).

### control (JOIN 기반 relation 필터, 기존 구조)

```sql
select g1_0.id, ... from games g1_0
where g1_0.id in (
  select distinct gtr1_0.game_id
  from game_theme_relations gtr1_0
  join game_themes t1_0 on t1_0.id = gtr1_0.theme_id,
       game_mechanism_relations gmr1_0
  join game_mechanisms m1_0 on m1_0.id = gmr1_0.mechanism_id
  where gtr1_0.game_id = gmr1_0.game_id
    and t1_0.code in ('BOOK_BGG_1117')
    and m1_0.code in ('HAND_MANAGEMENT') and m1_0.is_public
)
order by g1_0.popularity_score desc, g1_0.name, g1_0.id
offset 0 rows fetch first 25 rows only;
```

- 실행계획: Merge Join으로 theme relation(4258행)과 mechanism relation(24419행)을 game_id 기준 정렬 후 병합, Unique로 교집합을 70행으로 좁힌 뒤에야 games를 Nested Loop로 조회.
- **Buffers: shared hit=4270**
- **Execution Time: 33.507 ms**

### V3 (독립 IN 서브쿼리 분리 구조)

```sql
select g1_0.id, ... from games g1_0
where g1_0.id in (
  select distinct gtr1_0.game_id from game_theme_relations gtr1_0
  where gtr1_0.theme_id in (select gt1_0.id from game_themes gt1_0 where gt1_0.code in ('BOOK_BGG_1117'))
)
and g1_0.id in (
  select distinct gmr1_0.game_id from game_mechanism_relations gmr1_0
  where gmr1_0.mechanism_id in (select gm1_0.id from game_mechanisms gm1_0 where gm1_0.code in ('HAND_MANAGEMENT') and gm1_0.is_public)
)
order by g1_0.popularity_score desc, g1_0.name, g1_0.id
offset 0 rows fetch first 25 rows only;
```

- 실행계획: theme 쪽 IN을 먼저 HashAggregate로 distinct game_id(4258개)를 만든 뒤 **그 각각을 games_pkey에 Nested Loop로 개별 Index Scan(loops=4258)**하여 games 전체 컬럼(폭 2524바이트)을 미리 가져오고, 그 다음에야 mechanism 쪽 결과(24419개)와 Hash Join으로 교집합(70행)을 구한다. 즉 최종 교집합을 구하기 전에 이미 4258개 게임 행 전체를 읽어들이는 낭비 구조다.
- **Buffers: shared hit=21041** (control 대비 약 5배)
- **Execution Time: 45.975 ms**

## (c) 판단 포인트 결론

- 단일 인스턴스·워밍업 40회 조건에서도 V3의 HTTP p50이 control보다 높고(26.6ms vs 20.4ms), 실제로 그 순간 Hibernate가 만든 SQL을 그대로 재실행한 EXPLAIN도 **V3가 control보다 느리다(45.975ms vs 33.507ms)**.
- 판단 포인트 3번째 항목에 해당한다: **"이때 EXPLAIN 실행시간도 V3가 느리게 나온다면 → 예전 EXPLAIN(다른 세션, 다른 순간)이 재현 안 되는 것이니, DB 쿼리 자체가 조건에 따라 불안정하다는 근거가 강해진다."**
- 인스턴스 교대(App1/App2)·워밍업 부족(5회) 가설은 기각한다. 단일 인스턴스로도, 워밍업을 8배(5→40)로 늘려도 V3가 DB 레벨에서 더 느리게 나왔다.
- 근본 원인은 실행계획 구조 차이로 설명된다: control은 theme·mechanism relation을 Merge Join으로 먼저 교집합(70행)을 구한 뒤 games를 조회하지만, V3는 두 IN 서브쿼리를 완전히 독립적으로 평가하다 보니 플래너가 "theme 쪽 4258개 game_id를 games 테이블과 먼저 Nested Loop Join(각 game_id마다 games_pkey Index Scan, loops=4258)"하는 계획을 선택했다. 이 때문에 최종 결과가 70행임에도 buffers hit이 21041(=control의 약 5배)까지 늘어난다.
- 이 결과는 과거의 "control 189ms vs V3 18ms" EXPLAIN이 이번 fixture·이번 통계(ANALYZE 시점, 캐시 상태)에서는 재현되지 않음을 보여준다. 즉 V3의 "독립 IN 서브쿼리 분리" 접근은 planner가 어떤 조인 순서/방법을 선택하느냐에 따라 유리할 수도, 불리할 수도 있는 **불안정한 최적화**이며, 이번 조건(theme=BOOK_BGG_1117 처럼 relation 매치 건수가 수천 단위로 큰 케이스)에서는 오히려 불리하게 작동했다.

## (d) raw 데이터 위치

- `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/control/http-times.txt`
- `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/control/pg-capture.log`
- `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/control/explain.sql`, `explain-result.txt`
- `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/v3/http-times.txt`
- `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/v3/pg-capture.log`
- `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/v3/explain.sql`, `explain-result.txt`
- 진단용 compose override·env·스크립트: `/private/tmp/albam-925-v2v3-measurement.uqio4e/diagnosis-2026-08-22/{compose.diag-single.yml,.env.control,.env.v3,run-diag.sh}`

## (e) 다음에 볼 것

1. **planner 힌트/통계 재현성 확인**: `game_theme_relations`/`game_mechanism_relations`에 대해 `ANALYZE` 직후 vs 시간이 지난 뒤(autovacuum 통계 갱신 시점 차이)로 동일 EXPLAIN을 다시 떠서, "과거 18ms" 결과가 특정 통계 스냅숏에서만 나오는 우연이었는지 확인한다.
2. **다른 theme/mechanism 조합으로 반복**: 이번 진단은 `BOOK_BGG_1117`/`HAND_MANAGEMENT` 한 조합만 봤다. relation 매치 건수가 작은 조합(예: 희귀 테마)에서는 V3의 독립 IN 서브쿼리가 오히려 유리할 수 있으니, 매치 건수 분포별로 여러 조합을 EXPLAIN해서 "V3가 유리한 영역 vs 불리한 영역"의 경계를 찾는다.
3. **V3 쿼리를 semi-join(EXISTS) 형태로 재작성**해서 플래너가 games를 마지막에 조인하도록 강제하는 대안이 있는지 검토한다 — 현재 V3의 "완전히 독립된 IN 서브쿼리 2개" 구조가 이번 케이스처럼 relation 매치가 큰 조합에서 계획을 나쁘게 유도하는 근본 원인이므로, 인덱스보다 쿼리 형태 자체를 바꾸는 게 더 근본적인 해법일 수 있다.
4. **HTTP p50/p95와 EXPLAIN 실행시간의 격차(약 −10~20ms)**가 이번 라운드에서도 존재한다 — Hibernate 세션 준비(트랜잭션 시작/커밋, count 쿼리 2회 등) 오버헤드가 얼마나 되는지 별도로 격리 측정하면 "앱 레이어 vs DB" 경계를 더 좁힐 수 있다.
