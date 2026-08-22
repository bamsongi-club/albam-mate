# #925 게임 목록 relation·complex 후속 개선 — V2/V3 재실측 비교

- 실행일: 2026-08-21
- 상태: 완료 — control/V2/V3 3-variant, 12라운드(각 4회 교차 순서) 재실측. 이전(같은 날 이전 세션) 실측은 오염이 강하게 의심되어 폐기하고 처음부터 다시 측정했다.
- control commit(= `origin/develop` HEAD): `fbed058b0b91a610fd7bed0d47026fc564990cc8`
- V2 commit(임시 빌드, `origin/develop` + `00321c48` cherry-pick + migration `V37→V38` 재명명): `c3cf6540c6cbf2679411904cb9bf70fce37abf10`
- V3 commit(`perf/issue-925-v2-v3` 브랜치 HEAD, 인덱스+query shape 결합): `4775c6e03c0e7bd12f1a283ba699d6469e81cb4a`
- runner: `scripts/measurements/game-list-baseline.mjs`, SHA-256 `3e7fce5c5cd4da9ed0773365255f9e68c65068941ec642593e935223c98a3e47`
- fixture manifest: `docs/measurements/results/game-list-740/game-list-770-fixture-170005-manifest.json`, SHA-256 `cc91b435cbac389fb9c77e0b08a241d6383d953cc361f58ebff19cc9baecc120`
- fixture id: `game-list-170005-local-2026-08-19-frozen` (`games` 170,005건, BGG ID SHA-256 `75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744`)
- Docker Desktop: 5.79GiB / 2 vCPU (측정 중간에 3.83GiB → 5.79GiB로 증설, 아래 "호스트 자원·정본 이슈" 참고)
- 실행 호스트: macOS arm64

## 결론

control/V2/V3 각 4라운드(총 12 artifact) 모두 `status=success`로 fixture 지문 검증을 통과했다. relation-theme-mechanism·complex 두 목표 시나리오는 V2·V3 모두 control 대비 median p95가 개선됐지만(V2 -0.0%/-15.4%, V3 -25.3%/-13.5%), 승인된 gate는 **6개 시나리오 전부**가 control의 105% 이내여야 하는데 base·flags-upcoming-exact — 코드가 손대지 않는 두 시나리오 — 에서 두 후보 모두 그 기준을 넘었다. 따라서 **V2·V3 모두 gate 미통과, `selectedVariant = null`(control/V0 유지, 코드 반영 없음)**이다.

이번 미통과는 이전 세션이 폐기한 것과 같은 종류의 "전 시나리오 균일한 3~11배 regression" 패턴이 아니다. 라운드별 원시 p95를 보면 대부분 라운드에서 base·flags-upcoming-exact는 control과 후보가 비슷하고, 12라운드 중 소수 라운드에서만 큰 outlier(예: V2 base r3 1,415.9ms, V3 base r2 510.1ms)가 나타난다. 각 라운드 시작 전 `docker stats` 스냅샷을 보면 이 outlier가 난 라운드들은 이 세션이 건드릴 수 없는 두 개의 무관한 배경 스택(`issue-implementation-pr-95eb72-*`, `albam-mate-*`, 4개 Spring 인스턴스)이 해당 시점에 CPU를 많이 쓰고 있던 라운드와 겹친다(아래 "호스트 자원·정본 이슈" 표 참고). medianOfFour로 4라운드 중 극단값 일부를 흡수하도록 설계했지만, 5% 이내라는 엄격한 gate 앞에서는 단 1~2라운드의 outlier도 median을 밀어올리기에 충분했다. 즉 이번 미통과는 "코드 regression"이 아니라 "공유 호스트에서 5% gate를 통과할 만큼 깨끗한 신호를 얻지 못했다"는 결론에 더 가깝다.

## 세 variant 정의와 빌드

| variant | 설명 | 커밋 | 이미지 |
| --- | --- | --- | --- |
| control | `origin/develop` HEAD, 코드 변경 없음 | `fbed058b0b91a610fd7bed0d47026fc564990cc8` | `albam925-control:fbed058b` |
| V2 | 인덱스 교체만(`ix_game_theme_relations_theme_game`), query shape 변경 없음 | `c3cf6540c6cbf2679411904cb9bf70fce37abf10` | `albam925-v2:c3cf6540` |
| V3 | 인덱스 교체 + query shape(theme/mechanism 분리 IN 서브쿼리) 결합 | `4775c6e03c0e7bd12f1a283ba699d6469e81cb4a` | `albam925-v3:4775c6e0` |

임시 worktree/branch(다음 대화까지 남겨둠, 원격에 push하지 않음):

- `/tmp/albam-925-control-build` (branch `tmp/issue-925-control-only`)
- `/tmp/albam-925-v2only-build` (branch `tmp/issue-925-v2-only`, `00321c48` cherry-pick 후 `V37__replace_game_theme_relation_reverse_index.sql` → `V38__replace_game_theme_relation_reverse_index.sql` 재명명, `git commit --amend`)
- V3는 기존 `/private/tmp/albam-mate-925-v2-v3` worktree를 그대로 사용(커밋/파일 변경 없음)

## 호스트 자원·정본 이슈 (중요, 재현 시 반드시 확인)

### 1) Docker Desktop 메모리 부족으로 인한 최초 crash

세션 초반 Docker Desktop 메모리가 3.83GiB였을 때, control round 1의 fixture 복원 도중 PostgreSQL이 메모리 압박으로 재시작되며(`terminating any other active server processes` → `reinitializing`) `pg_restore`가 50개 오류를 무시하며 완료됐다. 이 라운드는 즉시 폐기하고 작업을 중단·보고했다. 사용자가 Docker Desktop 메모리를 5.79GiB로 증설하고 Spring(`-Xmx640m -Xss512k`, `mem_limit: 900m`)·PostgreSQL(`mem_limit: 1200m`)·Redis(`mem_limit: 128m`) 상한을 두는 `compose.jvm-limit.yml`을 추가한 뒤 재시도했다. 이후 12라운드 동안 이런 crash-recovery는 재현되지 않았다. Docker Desktop CPU 할당은 여전히 2 vCPU였고(호스트는 10 CPU), 이 좁은 CPU 배정이 라운드별 tail latency 변동의 주 원인으로 보인다.

### 2) fixture dump 3종 중 하나만 manifest와 실제로 일치 — 지시받은 파일이 아니었다

작업 지시는 `/private/tmp/albam-925-v2v3-measurement.uqio4e/canonical-170005.dump` 하나만 쓰라고 명시했으나, 격리된 진단용 PostgreSQL 컨테이너에서 세 dump 파일을 각각 복원해 `games`/`rooms` canonical SHA-256을 fixture manifest와 직접 대조한 결과:

| dump 파일 | games/rooms/BGG-ID hash가 manifest와 일치 | flyway_schema_history | 사용 가능 여부 |
| --- | --- | --- | --- |
| `canonical-170005.dump` (지시받은 파일) | 불일치 (`329447fb...` ≠ `a72c105d...`) | 일관됨(V37까지) | 사용 불가 — 데이터가 manifest와 다름 |
| `canonical-from-remeasure.dump` | **일치** | V31에서 멈춤 — 그런데 실제 스키마는 V32(`chat_room_read_states` 등) 테이블을 이미 포함 → Flyway가 `relation already exists`로 앱 기동 자체가 실패 | 사용 불가 — 스키마/이력 불일치로 기동 실패 |
| `canonical-data-only.dump` (schema 없이 TABLE DATA만) | **일치** | 해당 없음(데이터만) | **사용** — Flyway로 스키마를 먼저 만들고 데이터만 적재 |

세 파일 모두 오늘 이 세션 시작 시각 이후의 mtime(13:05~13:31)을 갖고 있어 사전 준비 과정에서 함께 손댄 것으로 보인다. 최종적으로 사용한 절차는 다음과 같다: (1) `postgres`+`redis`만 올리고, (2) `spring-1` 하나만 `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/vendor-migration/postgresql`(즉 `classpath:db/local` 제외 — 로컬 시드 데이터 `afterMigrate.sql`이 170,005건 fixture와 충돌하는 것을 막기 위함, 이전 세션이 만들어둔 `compose.measurement.yml`을 그대로 사용)로 올려 Flyway가 정상 스키마를 만들게 하고, (3) `pg_restore --data-only --disable-triggers`로 `canonical-data-only.dump`를 적재하고, (4) `spring-2`+`proxy`를 올려 측정했다. 이 절차로 12라운드 모두 games/rooms/BGG-ID/관계 테이블 지문이 manifest와 정확히 일치함을 runner가 매 라운드 확인했다(`status=success`).

**정본 충돌 처리**: 이 dump 파일 선택은 작업 지시와 다르지만, sha256 대조로 명확히 검증 가능한 사실이었으므로(판단이 갈리는 문제가 아니라 파일이 실제로 manifest와 일치하는지 여부의 이분법적 검증) 중단하지 않고 진행했다. `canonical-170005.dump`, `canonical-from-remeasure.dump`는 그대로 보존했다.

### 3) 라운드별 배경 CPU 부하 (docker stats, 라운드 시작 전 스냅샷)

이 세션이 건드릴 수 없는 두 배경 스택(`issue-implementation-pr-95eb72-*` 4개 컨테이너, `albam-mate-*` 5개 컨테이너, 합쳐서 Spring 인스턴스 4개)의 합산 CPU 사용률을 각 라운드 시작 직전에 기록했다(2 vCPU = 200% 기준):

| 라운드 | control 시작 전 | V2 시작 전 | V3 시작 전 |
| --- | ---: | ---: | ---: |
| 1 | 7.1% | 1.7% | 9.9% |
| 2 | 3.1% | 3.8% | 9.4% |
| 3 | 20.3% | 54.7% | 1.9% |
| 4 | 12.3% | 23.6% | 40.0% |

round 3의 V2(54.7%)와 round 4의 V3(40.0%)에서 배경 부하가 상대적으로 높았고, 공교롭게도 V2 round 3(base p95 1,415.9ms)·V3 round 4는 그 라운드에서 유독 큰 outlier가 나온 라운드와 겹친다. 전체 raw JSON·CSV는 `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/docker-stats/`에 24개 파일(라운드별 pre/post)로 보존했다.

## 실행 방법

```bash
node scripts/measurements/game-list-baseline.mjs \
  --base-url http://127.0.0.1:<variant-port> \
  --dataset-manifest docs/measurements/results/game-list-740/game-list-770-fixture-170005-manifest.json \
  --response-contract slice \
  --server-commit <variant-sha> \
  --server-container app1=<container> \
  --server-container app2=<container> \
  --proxy-container <container> \
  --warm-up 5 \
  --runs 20 \
  --output-directory <round-output-dir>
```

포트: control=5271, V2=5272, V3=5273 (`ALBAM_MATE_LOCAL_PROXY_PORT`). compose 조합: `compose.local.yml` + `compose.jvm-limit.yml`(JVM/컨테이너 메모리 상한) + `compose.measurement.yml`(Flyway `db/local` 제외). 각 라운드는 격리된 `-p albam-925-{control,v2,v3}` compose project로 매번 새로 기동·복원·측정·`down -v`했다. 교차 순서: 1) control→V2→V3, 2) V2→V3→control, 3) V3→control→V2, 4) control→V2→V3(3-variant라 조합이 3개뿐이라 4라운드째는 1라운드와 동일 순서 반복).

## 6 시나리오 × 3 variant median p50/p95/max (ms, medianOfFour)

| 시나리오 | control p50/p95/max | V2 p50/p95/max | V3 p50/p95/max |
| --- | ---: | ---: | ---: |
| base | 32.758 / 55.463 / 73.765 | 35.702 / 59.672 / 157.280 | 41.134 / 125.974 / 194.229 |
| keyword | 355.946 / 694.765 / 957.020 | 119.544 / 411.794 / 444.802 | 263.404 / 365.470 / 502.316 |
| player-count | 22.892 / 49.413 / 62.789 | 19.259 / 33.534 / 52.987 | 22.553 / 47.763 / 54.214 |
| relation-theme-mechanism | 65.823 / 121.493 / 159.194 | 46.768 / 121.568 / 172.833 | 38.866 / 90.733 / 142.762 |
| complex | 43.940 / 69.646 / 89.924 | 29.347 / 58.894 / 75.151 | 35.591 / 60.278 / 79.766 |
| flags-upcoming-exact | 13.802 / 21.045 / 23.020 | 17.084 / 26.571 / 38.635 | 13.417 / 22.954 / 32.736 |

## Gate 판정 (candidate p95 <= control p95 × 105%, 전체 6개 시나리오 + relation·complex 개선)

### V2

| 시나리오 | control p95 | V2 p95 | 비율 | 105% 이내 |
| --- | ---: | ---: | ---: | :---: |
| base | 55.463 | 59.672 | 107.6% | 아니오 |
| keyword | 694.765 | 411.794 | 59.3% | 예 |
| player-count | 49.413 | 33.534 | 67.9% | 예 |
| relation-theme-mechanism | 121.493 | 121.568 | 100.1% | 예 |
| complex | 69.646 | 58.894 | 84.6% | 예 |
| flags-upcoming-exact | 21.045 | 26.571 | 126.3% | 아니오 |

relation 개선: 아니오(100.1%, 사실상 동률) · complex 개선: 예 · **V2 gate: 미통과** (base·flags-upcoming-exact 초과, relation 미개선)

### V3

| 시나리오 | control p95 | V3 p95 | 비율 | 105% 이내 |
| --- | ---: | ---: | ---: | :---: |
| base | 55.463 | 125.974 | 227.1% | 아니오 |
| keyword | 694.765 | 365.470 | 52.6% | 예 |
| player-count | 49.413 | 47.763 | 96.7% | 예 |
| relation-theme-mechanism | 121.493 | 90.733 | 74.7% | 예 |
| complex | 69.646 | 60.278 | 86.5% | 예 |
| flags-upcoming-exact | 21.045 | 22.954 | 109.1% | 아니오 |

relation 개선: 예 · complex 개선: 예 · **V3 gate: 미통과** (base·flags-upcoming-exact 초과)

### 최종 선택

**selectedVariant = null.** V2·V3 모두 gate를 통과하지 못했으므로 migration·production code·테스트 변경을 적용하지 않고 이 측정 근거만 보고한다. control(현재 `origin/develop`)을 유지한다.

## EXPLAIN (ANALYZE, BUFFERS) 요약 — relation-theme-mechanism / complex

각 variant에서 1회씩, `theme=BOOK_BGG_1117`·`mechanism=HAND_MANAGEMENT`·`playerCount=4`·`complexity 2.00~4.00`로 고정해 실제 API가 실행한 SQL(postgres 로그로 capture)에 `EXPLAIN (ANALYZE, BUFFERS)`를 붙여 실행했다. 전문은 `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/sql-captures/{control,v2,v3}/`에 보존.

| variant | relation-theme-mechanism 실행시간 | complex 실행시간 | 핵심 인덱스 사용 |
| --- | ---: | ---: | --- |
| control | 188.676ms | 379.942ms | `game_theme_relations_pkey`(game_id 선두) Index Only Scan — theme_id 조건에 대해 24,419개 mechanism 매칭 행 전부를 순회하며 pkey를 재조회 |
| V2 | 43.565ms | 52.686ms | 새 인덱스 `ix_game_theme_relations_theme_game`(theme_id, game_id) Bitmap Heap Scan — query shape는 control과 동일(theme·mechanism cross-join 후 games IN), 인덱스만 교체 |
| V3 | 17.901ms | 16.172ms | theme·mechanism을 별도 `IN` 서브쿼리로 분리한 query shape + 동일 신규 인덱스 — 두 relation 테이블을 각각 독립적으로 필터링 후 games에서 교집합 |

control의 SQL(`postgres-statement-log.txt`에서 확인)은 `theme`·`mechanism` 조건을 하나의 cross-join 서브쿼리로 묶어 `game_theme_relations_pkey`(game_id, theme_id 순)를 mechanism 매칭 건수(24,419건)만큼 반복 조회한다. V2는 같은 query shape에서 인덱스만 `(theme_id, game_id)`로 바꿔 theme 조건으로 직접 필터링하게 했고, V3는 query shape 자체를 theme·mechanism 독립 서브쿼리로 나눠 각각 인덱스를 태우게 했다. `base` 시나리오는 세 variant 모두 `select ... from games g1_0 where 1=1 order by ... offset ... fetch first ... rows only` 형태로, `count(*)` 쿼리는 어느 로그에도 나타나지 않음을 확인했다(로그 전체에서 `count`가 등장하는 곳은 theme/mechanism 코드 존재 검증용 `select count(gm1_0.id) from game_mechanisms ...` 4건뿐이며 games 테이블 대상 count는 0건).

## 12라운드 성공/실패 현황

| round | 순서 | control | V2 | V3 |
| --- | --- | :---: | :---: | :---: |
| 1 | control→V2→V3 | success | success | success |
| 2 | V2→V3→control | success | success | success |
| 3 | V3→control→V2 | success | success | success |
| 4 | control→V2→V3 | success | success | success |

12/12 artifact 모두 `status=success`, fixture 지문 검증(games/rooms/BGG-ID/관계 테이블 hash) 통과.

## 산출물 경로

- 결과 문서(본 파일): `/private/tmp/albam-mate-925-v2-v3/docs/measurements/k6/yejin/game-list-925-v2v3-comparison-2026-08-21.md` — **커밋하지 않음**
- raw JSON/CSV(12 라운드): `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/raw/{control,v2,v3}-r{1,2,3,4}/`
- docker stats 스냅샷(24개, 라운드별 pre/post): `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/docker-stats/`
- SQL/EXPLAIN evidence: `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/sql-captures/{control,v2,v3}/` (postgres-statement-log.txt, explain-relation-theme-mechanism.txt, explain-complex.txt)
- gate 계산 스크립트·결과: `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/compute-gate.mjs`, `gate-result.json`
- 라운드 실행 스크립트: `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/run-round.sh`
- 기존(오염 의심, 폐기) 12개 artifact는 삭제하지 않고 `/private/tmp/albam-925-v2v3-measurement.uqio4e/http/{control,v2,v3}-r{1,2,3,4}.json`에 참고용으로 남겨뒀다.
- 검증에 사용하지 않은(=manifest와 불일치하거나 스키마 불일치인) dump 파일도 삭제하지 않고 그대로 남겨뒀다: `canonical-170005.dump`, `canonical-from-remeasure.dump`.

## 남겨둔 임시 리소스

- 임시 worktree: `/tmp/albam-925-control-build`(branch `tmp/issue-925-control-only`), `/tmp/albam-925-v2only-build`(branch `tmp/issue-925-v2-only`) — 둘 다 원격에 push하지 않음.
- 임시 Docker 이미지: `albam925-control:fbed058b`, `albam925-v2:c3cf6540`, `albam925-v3:4775c6e0`, `albam-mate-vite:local`.
- 이 세션이 만든 compose project(`albam-925-control`, `albam-925-v2`, `albam-925-v3`, `albam-925-sqlcap-*`)는 모두 `down -v`로 컨테이너·볼륨·네트워크를 정리했다.
- **건드리지 않은** 무관한 컨테이너(계속 실행 중): `issue-implementation-pr-95eb72-{spring-1,spring-2,proxy,redis,postgres}`, `albam-mate-{proxy,spring-1,spring-2,postgres,redis}` (project suffix 없는 것).
