# #925 게임 목록 relation·complex 후속 개선 — V2/V3 재실측 비교 (runs=50)

- 실행일: 2026-08-22
- 상태: 완료 — control/V2/V3 3-variant, 12라운드(각 4회 교차 순서) 재실측. 측정 프로토콜을 사용자가 GitHub issue #925 승인 코멘트(https://github.com/bamsongi-club/albam-mate/issues/925#issuecomment-5371712003)로 `--runs 50`(warm-up 5 유지)로 갱신한 뒤 처음부터 다시 측정했다. 이전(2026-08-21, `--runs 20`) 실측은 그대로 보존하고 폐기하지 않는다.
- control commit(= `origin/develop` HEAD): `fbed058b0b91a610fd7bed0d47026fc564990cc8`
- V2 commit(임시 빌드, `origin/develop` + `00321c48` cherry-pick + migration `V37→V38` 재명명): `c3cf6540c6cbf2679411904cb9bf70fce37abf10`
- V3 commit(`perf/issue-925-v2-v3` 브랜치 HEAD, 인덱스+query shape 결합): `4775c6e03c0e7bd12f1a283ba699d6469e81cb4a`
- runner: `scripts/measurements/game-list-baseline.mjs`, SHA-256 `3e7fce5c5cd4da9ed0773365255f9e68c65068941ec642593e935223c98a3e47`
- fixture manifest: `docs/measurements/results/game-list-740/game-list-770-fixture-170005-manifest.json`, SHA-256 `cc91b435cbac389fb9c77e0b08a241d6383d953cc361f58ebff19cc9baecc120`
- fixture id: `game-list-170005-local-2026-08-19-frozen` (`games` 170,005건, BGG ID SHA-256 `75bcb893bcfef7f3b0a0de363e06037d332392c038ad5eb46c33de2b553c8744`)
- Docker Desktop: 5.79GiB / 2 vCPU (이전 세션에서 이미 증설된 상태 그대로, 이번 세션에서 추가 조정하지 않음)
- 실행 호스트: macOS arm64
- 측정 프로토콜: warm-up 5 + measured runs 50 (기존 `--runs 20` 대비 2.5배)

## 결론

control/V2/V3 각 4라운드(총 12 artifact) 모두 `status=success`로 fixture 지문 검증을 통과했다. 그러나 `--runs 50`으로 바꾼 뒤에도 **gate는 여전히 미통과**다. V2는 base·flags-upcoming-exact 초과 대신 이번엔 player-count(121.1%)·flags-upcoming-exact(107.7%) 초과로 실패했고, V3는 이번 회차에서는 base(103.7%)만 겨우 통과했지만 player-count(250.0%)·relation-theme-mechanism(172.9%, **목표 시나리오인데도 control보다 악화**)·complex(120.7%)·flags-upcoming-exact(125.7%)에서 모두 초과해 오히려 이전(`--runs 20`) 회차보다 더 크게 실패했다. relation 개선 여부도 V2는 개선(75.9%)이지만 V3는 이번엔 개선되지 못했다(172.9%, 악화). 따라서 **V2·V3 모두 gate 미통과, `selectedVariant = null`(control/V0 유지, 코드 반영 없음)** — `--runs 20` 때와 최종 판정은 동일하다.

`--runs 50`으로 각 라운드 내부의 nearest-rank p95 표본 크기를 늘린 효과는 실제로 있었다(아래 "runs 20 vs runs 50 outlier 민감도 비교" 참고). 그러나 이번 12라운드에서도 **라운드 하나 전체가 여러 시나리오에서 동시에 튀는 패턴**이 재현됐다 — 이번엔 control round 2(keyword 1427.9ms, relation-theme-mechanism 329.1ms, complex 355.6ms가 같은 라운드에서 동시에 치솟음)와 V3 round 1(keyword 2374.0ms 극단치, player-count 190.0ms도 같은 라운드에서 동반 상승)에서 나타났다. medianOfFour는 4라운드 중 2개 중앙값 평균이라 극단치 1개는 어느 정도 흡수하지만, 라운드 자체가 통째로 오염되면 그 라운드의 여러 시나리오가 동시에 median을 밀어올려 5% gate를 통과하기 어렵다. 즉 `--runs 50`은 "표본 내부 노이즈"는 줄였지만 "표본 간(라운드 간) 호스트 노이즈"는 여전히 4-round 설계의 근본적 약점으로 남아 있다.

## 세 variant 정의와 빌드

동일 이미지·워크트리를 재사용했다(빌드하지 않음).

| variant | 설명 | 커밋 | 이미지 |
| --- | --- | --- | --- |
| control | `origin/develop` HEAD, 코드 변경 없음 | `fbed058b0b91a610fd7bed0d47026fc564990cc8` | `albam925-control:fbed058b` |
| V2 | 인덱스 교체만(`ix_game_theme_relations_theme_game`), query shape 변경 없음 | `c3cf6540c6cbf2679411904cb9bf70fce37abf10` | `albam925-v2:c3cf6540` |
| V3 | 인덱스 교체 + query shape(theme/mechanism 분리 IN 서브쿼리) 결합 | `4775c6e03c0e7bd12f1a283ba699d6469e81cb4a` | `albam925-v3:4775c6e0` |

재사용한 임시 worktree(변경 없음):

- `/tmp/albam-925-control-build` (branch `tmp/issue-925-control-only`)
- `/tmp/albam-925-v2only-build` (branch `tmp/issue-925-v2-only`)
- V3는 기존 `/private/tmp/albam-mate-925-v2-v3` worktree를 그대로 사용(이번 세션도 커밋/파일 변경 없음)

## fixture 복원 절차 (2026-08-21 세션이 검증한 절차를 그대로 유지)

`canonical-170005.dump`는 manifest와 games/rooms hash가 불일치해 사용하지 않는다. 사용한 절차:

1. `postgres`+`redis`만 올린다.
2. `spring-1` 하나만 `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/vendor-migration/postgresql`(즉 `classpath:db/local` 제외)로 올려 Flyway가 빈 스키마를 정상 생성하게 한다 — 로컬 시드 데이터(`afterMigrate.sql`)가 170,005건 fixture와 충돌하는 것을 막기 위함.
3. `pg_restore --data-only --disable-triggers --no-owner`로 `canonical-data-only.dump`(TABLE DATA만)를 적재한다.
4. `spring-2`+`proxy`를 올려 측정한다.

12라운드 모두 이 절차로 games/rooms/BGG-ID 지문이 manifest와 정확히 일치함을 runner가 확인했다(`status=success`). `pg_restore`는 매 라운드 `room_status_correction_progress` 테이블에서 사전 시드 값과 중복되는 단일 행(`job_name=room-status-correction`) 하나만 무시하는 경고를 반복 출력했는데, 이는 fixture 데이터 자체의 문제가 아니라 스키마 생성 시 애플리케이션이 이미 심어둔 단일 초기값과의 충돌이며 games/rooms 지문 검증에는 영향이 없어 12라운드 내내 무해했다.

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
  --runs 50 \
  --output-directory <round-output-dir>
```

포트: control=5271, V2=5272, V3=5273. compose 조합: `compose.local.yml` + `compose.jvm-limit.yml`(JVM/컨테이너 메모리 상한, 2026-08-21 세션이 추가한 것 재사용) + `compose.measurement.yml`(Flyway `db/local` 제외). 각 라운드는 격리된 `-p albam-925-{control,v2,v3}` compose project로 매번 새로 기동·복원·측정·`down -v`했다. 교차 순서: 1) control→V2→V3, 2) V2→V3→control, 3) V3→control→V2, 4) control→V2→V3(3-variant라 조합이 3개뿐이라 4라운드째는 1라운드와 동일 순서 반복) — 2026-08-21과 동일한 교차 순서를 유지했다.

## 6 시나리오 × 3 variant median p50/p95/max (ms, medianOfFour)

| 시나리오 | control p50/p95/max | V2 p50/p95/max | V3 p50/p95/max |
| --- | ---: | ---: | ---: |
| base | 13.725 / 34.661 / 38.941 | 12.907 / 28.035 / 44.771 | 19.821 / 35.932 / 59.584 |
| keyword | 239.669 / 351.547 / 473.382 | 193.585 / 320.309 / 406.361 | 103.914 / 134.037 / 154.440 |
| player-count | 11.033 / 22.509 / 36.207 | 13.457 / 27.255 / 48.138 | 20.399 / 56.271 / 221.931 |
| relation-theme-mechanism | 25.686 / 39.123 / 65.167 | 21.429 / 29.709 / 39.684 | 31.789 / 67.655 / 131.651 |
| complex | 21.287 / 37.724 / 79.388 | 19.146 / 32.561 / 35.458 | 27.234 / 45.525 / 55.726 |
| flags-upcoming-exact | 8.058 / 15.637 / 23.611 | 8.341 / 16.841 / 25.433 | 11.006 / 19.653 / 27.971 |

## Gate 판정 (candidate p95 <= control p95 × 105%, 전체 6개 시나리오 + relation·complex 개선)

### V2

| 시나리오 | control p95 | V2 p95 | 비율 | 105% 이내 |
| --- | ---: | ---: | ---: | :---: |
| base | 34.661 | 28.035 | 80.9% | 예 |
| keyword | 351.547 | 320.309 | 91.1% | 예 |
| player-count | 22.509 | 27.255 | 121.1% | 아니오 |
| relation-theme-mechanism | 39.123 | 29.709 | 75.9% | 예 |
| complex | 37.724 | 32.561 | 86.3% | 예 |
| flags-upcoming-exact | 15.637 | 16.841 | 107.7% | 아니오 |

relation 개선: 예(75.9%) · complex 개선: 예 · **V2 gate: 미통과** (player-count·flags-upcoming-exact 초과)

### V3

| 시나리오 | control p95 | V3 p95 | 비율 | 105% 이내 |
| --- | ---: | ---: | ---: | :---: |
| base | 34.661 | 35.932 | 103.7% | 예 |
| keyword | 351.547 | 134.037 | 38.1% | 예 |
| player-count | 22.509 | 56.271 | 250.0% | 아니오 |
| relation-theme-mechanism | 39.123 | 67.655 | 172.9% | 아니오 |
| complex | 37.724 | 45.525 | 120.7% | 아니오 |
| flags-upcoming-exact | 15.637 | 19.653 | 125.7% | 아니오 |

relation 개선: **아니오(172.9%, 오히려 악화)** · complex 개선: 아니오 · **V3 gate: 미통과** (player-count·relation-theme-mechanism·complex·flags-upcoming-exact 초과)

### 최종 선택

**selectedVariant = null.** V2·V3 모두 gate를 통과하지 못했으므로 migration·production code·테스트 변경을 적용하지 않고 이 측정 근거만 보고한다. control(현재 `origin/develop`)을 유지한다.

## runs 20 vs runs 50 outlier 민감도 비교

`--runs 20`(2026-08-21)과 `--runs 50`(이번)에서 코드가 손대지 않는 두 시나리오(base, flags-upcoming-exact)의 라운드별 원시 p95 4개 값의 (max/min) 비율로 상대 변동폭을 비교했다:

| variant | 시나리오 | runs=20 max/min 비율 | runs=50 max/min 비율 |
| --- | --- | ---: | ---: |
| control | base | 6.8x (17.1~116.5ms) | 11.7x (22.8~267.5ms) |
| control | flags-upcoming-exact | 22.8x (12.4~282.3ms) | 3.1x (12.0~36.9ms) |
| V2 | base | 43.2x (32.8~1415.9ms) | 2.6x (19.5~50.9ms) |
| V2 | flags-upcoming-exact | 10.4x (20.1~209.6ms) | 2.5x (11.8~29.9ms) |
| V3 | base | 22.8x (22.4~510.1ms) | 5.6x (24.8~138.9ms) |
| V3 | flags-upcoming-exact | 4.9x (19.5~96.5ms) | 2.1x (13.7~29.3ms) |

6개 조합 중 5개(control-flags, V2-base, V2-flags, V3-base, V3-flags)에서 `--runs 50`이 변동폭을 뚜렷이 줄였다 — 특히 V2 base는 43배에서 2.6배로 극적으로 개선됐다. 이는 nearest-rank p95가 "20개 중 2번째로 느린 값"에서 "50개 중 3번째로 느린 값"으로 바뀌면서 표본 내부 tail 노이즈에 덜 민감해졌다는 가설과 일치한다.

다만 control-base 1개 조합은 오히려 악화됐다(6.8x → 11.7x) — control round 2에서 base(267.5ms)뿐 아니라 keyword(1427.9ms)·relation-theme-mechanism(329.1ms)·complex(355.6ms)까지 같은 라운드에서 동시에 튀었기 때문이다. 이는 "표본 내부 노이즈"가 아니라 "그 라운드 시점에 호스트 전체가 바빴던" 표본 간(라운드 간) 노이즈로, `--runs 50`으로 표본 크기를 늘려도 해소되지 않는 종류의 변동이다. 같은 패턴이 V3 round 1(keyword 2374.0ms 극단치와 player-count 190.0ms가 동시 발생)에서도 재현됐다. 결론적으로 **`--runs 50`은 라운드 내부 tail 노이즈는 확실히 줄였지만, 라운드 전체가 오염되는 호스트 자원 경쟁 문제는 그대로 남아 있고, 4-round 설계에서는 이 중 한 라운드만 나빠도 median이 gate를 넘기기 충분하다.**

## 라운드별 배경 CPU 부하 (docker stats, 라운드 시작 직전/직후 스냅샷)

이 세션이 건드릴 수 없는 두 배경 스택(`issue-implementation-pr-95eb72-*` 5개 컨테이너, `albam-mate-*` 5개 컨테이너)의 CPU 사용률 합계(2 vCPU = 200% 기준)를 각 라운드 시작 직전(pre)·직후(post)에 기록했다:

| round | 순서 | control pre/post | V2 pre/post | V3 pre/post |
| --- | --- | ---: | ---: | ---: |
| 1 | control→V2→V3 | 3.2% / 4.5% | 9.3% / 3.9% | 7.1% / 4.1% |
| 2 | V2→V3→control | 1.7% / 9.0%(control은 3번째라 pre 스냅샷은 V2·V3 완료 후) | 4.9% / 9.6% | 5.4% / 2.4% |
| 3 | V3→control→V2 | 84.4% / 4.9% | 2.7% / 2.6% | 3.3% / 3.1% |
| 4 | control→V2→V3 | 1.2% / 2.9% | 1.9% / 5.7% | 1.3% / 0.9% |

**중요한 한계**: pre/post는 라운드 시작 직전·직후의 순간 스냅샷일 뿐, 측정(약 50~90초의 k6-style HTTP 부하) 도중 배경 CPU가 어떻게 움직였는지는 캡처하지 못한다. 실제로 control round 2(keyword/relation/complex 동시 급등)와 V3 round 1(keyword 극단치+player-count 동반 상승)의 pre-스냅샷은 각각 1.7%·7.1%로 평범했다 — 즉 이 두 outlier 라운드는 배경 스택이 "라운드 시작 시점"에는 조용했지만 측정 진행 중 어느 시점에 활동했을 가능성이 높고, 이번 스냅샷 방식으로는 그 순간을 잡아내지 못했다. control round 3의 pre-스냅샷(84.4%)은 배경 부하가 뚜렷했던 유일한 사례지만, 해당 라운드 자체의 결과는 상대적으로 깨끗했다(base 23.8ms, keyword 252.4ms 등 다른 3개 라운드와 비슷한 수준). 따라서 이번 회차의 스냅샷 데이터는 "직전 배경 부하 → 해당 라운드 결과 악화"라는 직접적 인과관계를 뒷받침하지 못했고, outlier의 정확한 원인은 미확정으로 남긴다(공유 호스트에서의 순간적 CPU 경쟁으로 추정되나 실측으로 확정하지 못함).

전체 docker stats 원본(24개 파일, 라운드별 pre/post)은 `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-22-runs50/docker-stats/`에 보존했다.

## EXPLAIN (ANALYZE, BUFFERS) — 재사용

이번 위임은 HTTP p95 재측정에 집중했으므로 SQL/EXPLAIN capture는 새로 수행하지 않았다(코드가 2026-08-21 이후 변경되지 않았음). relation-theme-mechanism·complex의 인덱스·query shape 근거는 2026-08-21 문서의 동일 절을 참고한다: `/private/tmp/albam-mate-925-v2-v3/docs/measurements/k6/yejin/game-list-925-v2v3-comparison-2026-08-21.md#explain-analyze-buffers-요약--relation-theme-mechanism--complex`, 원본 evidence는 `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/sql-captures/{control,v2,v3}/`에 보존돼 있다.

## 12라운드 성공/실패 현황

| round | 순서 | control | V2 | V3 |
| --- | --- | :---: | :---: | :---: |
| 1 | control→V2→V3 | success | success | success (keyword 극단치 2374.0ms) |
| 2 | V2→V3→control | success | success | success |
| 3 | V3→control→V2 | success (배경 CPU pre 84.4%) | success | success |
| 4 | control→V2→V3 | success | success | success |

12/12 artifact 모두 `status=success`, fixture 지문 검증(games/rooms/BGG-ID/관계 테이블 hash) 통과. `control round 2`(keyword/relation/complex 동시 급등)와 `V3 round 1`(keyword 극단치+player-count 동반 상승)은 다수 시나리오가 같은 라운드에서 동시에 튀는 호스트 노이즈 패턴으로 보이지만 배제하지 않고 median 계산에 그대로 포함했다.

## 산출물 경로

- 결과 문서(본 파일): `/private/tmp/albam-mate-925-v2-v3/docs/measurements/k6/yejin/game-list-925-v2v3-comparison-2026-08-22-runs50.md` — **커밋하지 않음**
- 이전(runs=20) 결과 문서(그대로 보존): `/private/tmp/albam-mate-925-v2-v3/docs/measurements/k6/yejin/game-list-925-v2v3-comparison-2026-08-21.md`
- raw JSON/CSV(12 라운드): `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-22-runs50/raw/{control,v2,v3}-r{1,2,3,4}/`
- docker stats 스냅샷(24개, 라운드별 pre/post): `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-22-runs50/docker-stats/`
- gate 계산 스크립트·결과: `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-22-runs50/compute-gate.mjs`, `gate-result.json`
- 라운드 실행 스크립트: `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-22-runs50/run-round.sh`
- SQL/EXPLAIN evidence(2026-08-21 재사용, 새로 수행하지 않음): `/private/tmp/albam-925-v2v3-measurement.uqio4e/rerun-2026-08-21/sql-captures/{control,v2,v3}/`

## 남겨둔 임시 리소스

- 임시 worktree: `/tmp/albam-925-control-build`(branch `tmp/issue-925-control-only`), `/tmp/albam-925-v2only-build`(branch `tmp/issue-925-v2-only`) — 둘 다 원격에 push하지 않음. 이번 세션도 변경하지 않았다.
- 임시 Docker 이미지: `albam925-control:fbed058b`, `albam925-v2:c3cf6540`, `albam925-v3:4775c6e0`, `albam-mate-vite:local` — 재사용, 재빌드하지 않음.
- 이 세션이 만든 compose project(`albam-925-control`, `albam-925-v2`, `albam-925-v3`)는 매 라운드 `down -v`로 컨테이너·볼륨·네트워크를 정리했다. 12라운드 종료 후 `docker ps -a`·`docker volume ls`로 `albam-925-*` 잔존물이 없음을 확인했다.
- **건드리지 않은** 무관한 컨테이너(계속 실행 중, 이 세션과 무관한 다른 세션 소유): `issue-implementation-pr-95eb72-{spring-1,spring-2,proxy,redis,postgres}`, `albam-mate-{proxy,spring-1,spring-2,postgres,redis}`(project suffix 없는 것).
