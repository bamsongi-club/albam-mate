# #867 게임 목록 동시 부하·자원 검증 결과

- 실행일: 2026-08-20
- 상태: 완료 — 로컬 격리 환경의 최종 후보 검증이며 production SLO·용량 승인으로 해석하지 않는다.
- 선택 후보: V1(query shape)
- 서버 commit: `853d1eeee384783372649a1b258c3f816d24e835`
- runner commit: `22916f36027af4187b64eebdee69a2fc67cf388f`
- runner source clean: `true`
- fixture: `game-list-170005-local-2026-08-19-frozen` (`games` 170,005건)
- fixture manifest SHA-256: `cc91b435cbac389fb9c77e0b08a241d6383d953cc361f58ebff19cc9baecc120`
- k6: `v2.1.0`
- Docker Server: `29.4.3`
- 실행 호스트: macOS arm64, 10 CPU

## 결론

VU 2·4·8의 세 단계에서 HTTP 오류·timeout으로 집계되는 `http_req_failed`는 모두 `0%`, Slice 응답 계약 check는 모두 `100%`, App 2대와 PostgreSQL의 재시작은 `0회`였다. VU 4까지 처리량이 증가했지만 VU 8에서는 p95가 `494.930ms`, p99가 `1,220.537ms`로 늘어 로컬 환경의 tail latency 증가가 관찰됐다. 이 결과만으로 운영 환경의 안전한 동시 사용자 수나 SLO를 정하지 않는다.

## 실행 조건과 토폴로지

isolated PostgreSQL에 170,005건 fixture를 복원하고 현재 애플리케이션 image를 Flyway와 함께 기동했다. proxy 뒤에 App1·App2를 두고, Redis는 애플리케이션 보조 컨테이너로 분리했다. k6는 `/api/games`의 기본 목록·relation(theme/mechanism)·complex filter를 약 1/3씩 요청했다. 각 응답은 HTTP 200, `content` 배열, `page`·`size`·`hasNext`, 정확한 Slice 키(`content`, `hasNext`, `page`, `size`)를 확인했다.

각 단계는 constant VU로 20초 실행했고 자원 수집 설정 간격은 1초였다. Docker stats와 `pg_stat_activity` 호출이 동기식인 runner 특성상 실제 표본은 단계별 6개였으며, 시작·종료 표본을 포함한다.

```bash
node scripts/measurements/game-list-concurrency.mjs \
  --base-url http://127.0.0.1:5174 \
  --server-commit 853d1eeee384783372649a1b258c3f816d24e835 \
  --app1-container albam-mate-867-spring-1 \
  --app2-container albam-mate-867-spring-2 \
  --postgres-container albam-mate-867-postgres \
  --levels 2,4,8 \
  --duration 20s \
  --sample-interval-ms 1000 \
  --output build/k6/game-list-867/concurrency.json
```

## HTTP 결과

`error/timeout`은 k6 표준 `http_req_failed` 기준이며, `checks`는 Slice 응답 계약 check 기준이다.

| 동시 VU | 요청 수 | 처리량 | p50 | p95 | p99 | max | error/timeout | checks |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 353 | 17.430 req/s | 59.662ms | 137.129ms | 248.917ms | 371.407ms | 0% (0) | 100% |
| 4 | 575 | 28.629 req/s | 84.192ms | 209.543ms | 280.810ms | 340.129ms | 0% (0) | 100% |
| 8 | 613 | 30.327 req/s | 199.416ms | 494.930ms | 1,220.537ms | 1,741.920ms | 0% (0) | 100% |

VU 8은 VU 4보다 처리량이 소폭 증가했지만 p95는 약 2.36배, p99는 약 4.35배 증가했다. 이는 이 로컬 Docker 호스트에서 동시성이 올라갈 때 tail latency가 악화되는 관찰값이며, production capacity threshold가 아니다.

## V0/V1 시나리오 분리 진단

mixed 결과의 `494.930ms` tail 원인을 분리하기 위해 같은 170,005건 dump를 새로 복원한 V0·V1 격리 런타임에서 VU 8·20초를 시나리오별로 한 번씩 실행했다. V0 서버 commit은 `7d29232f30bc4122f08a2aa4e8059cb643b9a2c1`, V1 서버 commit은 `853d1eeee384783372649a1b258c3f816d24e835`, 시나리오 선택을 지원한 runner commit은 `90361ba4`다. 원본은 `build/k6/game-list-867/targeted/v{0,1}-{base,relation,complex}.json`에 보존했다.

| 시나리오 | V0 p50 / p95 / p99 | V1 p50 / p95 / p99 | V1 p95 변화 | V0 → V1 처리량 |
| --- | ---: | ---: | ---: | ---: |
| base | 89.542 / 238.781 / 560.736ms | 58.307 / 166.225 / 317.267ms | -30.35% | 48.739 → 63.235 req/s |
| relation | 1,677.730 / 3,773.049 / 3,780.221ms | 1,388.561 / 1,769.973 / 1,944.998ms | -53.09% | 3.918 → 5.590 req/s |
| complex | 783.842 / 1,170.729 / 1,407.169ms | 942.087 / 1,801.525 / 2,186.464ms | +53.88% | 9.252 → 7.308 req/s |

세 시나리오 모두 HTTP 오류율은 `0%`, Slice check는 `100%`, App·DB 재시작은 `0회`였다. relation은 V1에서도 p95가 `1.77초`이고 PostgreSQL CPU 최대가 V0 `174.34%`, V1 `187.45%`로 App보다 높았다. 따라서 `495ms` mixed tail은 base가 아니라 relation·complex가 섞인 DB 관계 필터 부하의 영향으로 보는 것이 타당하다.

이 분리 실행은 각 시나리오 한 round의 진단 자료이므로, 네 round 교차 순서로 확정한 V1 후보 선택 gate를 다시 판정하지 않는다. 특히 complex의 단일 실행은 V1이 V0보다 높게 관측되어 반복 확인이 필요하다. 현재 결과만으로 V2/V3 인덱스를 다시 채택하지 않으며, relation 절대 p95를 더 낮추려면 전 시나리오 회귀 gate를 포함한 별도 인덱스·query tuning 실험이 필요하다.

## V1 복합 인덱스 후보 gate

relation 지연의 원인을 확인하기 위해 V1 query shape를 고정한 채, 동일한 170,005건 frozen fixture에서 `game_theme_relations (theme_id, game_id)` 임시 인덱스 유무를 비교했다. 이 인덱스는 실험 DB에만 생성했고 Flyway migration에는 추가하지 않았다. 양쪽 모두 warm-up 5회 뒤 6개 시나리오를 순차 20회씩 실행했으며, 실행 순서는 `무인덱스 → 인덱스`, `인덱스 → 무인덱스`를 두 번 반복한 4 round다. baseline runner commit은 `c54a2cb21f188c7f1e4ec3a8d83a49847c72e679`, 8개 artifact 모두 HTTP 오류·계약 오류 없이 종료됐다.

판정 기준은 각 시나리오의 네 round p95 중앙값이 무인덱스 대비 `+5%` 이내이고, relation·complex가 각각 무인덱스보다 실제로 낮아야 한다는 것이다.

| 시나리오 | 무인덱스 p95 중앙값 | 복합 인덱스 p95 중앙값 | 변화 | 판정 |
| --- | ---: | ---: | ---: | --- |
| base | 76.433ms | 136.761ms | +78.93% | 실패 |
| keyword | 45.157ms | 41.673ms | -7.72% | 통과 |
| player-count | 53.196ms | 44.502ms | -16.34% | 통과 |
| relation-theme-mechanism | 394.080ms | 400.545ms | +1.64% | 개선 아님 |
| complex | 551.671ms | 782.917ms | +41.92% | 실패 |
| flags-upcoming-exact | 71.911ms | 50.351ms | -29.98% | 통과 |

전체 시나리오 회귀 gate와 relation·complex 개선 조건을 모두 만족하지 못했으므로 복합 인덱스 후보는 `REJECTED`다. 따라서 현재 PR에는 인덱스 migration을 추가하지 않고 V1 query shape를 유지한다.

EXPLAIN에서도 인덱스는 theme subquery의 `ix_game_theme_relations_theme_game_experiment_867` index-only scan(4,258행)에는 사용됐지만, mechanism subquery가 만드는 24,419건의 `games` PK lookup은 그대로 남았다. 즉 후보 인덱스가 관계 필터 전체 비용의 일부만 줄이며, complex 회귀와 relation 중앙값 정체를 설명한다. 후속 실험 대상으로 인덱스 추가 자체가 아니라 두 relation subquery의 join/order와 mechanism→games lookup 비용을 정하고, 아래와 같이 동일한 6시나리오 gate를 적용했다.

## V1 relation 교집합 query gate

복합 인덱스 후보가 탈락했으므로, 인덱스 migration 없이 theme·mechanism `ANY` 필터를 하나의 `DISTINCT game_id` 교집합 subquery로 묶는 query variant를 실험했다. `ANY`+`ANY` 조합에서만 적용하고 `ALL` 및 단일 relation 필터의 의미는 기존 query shape로 보존했다.

동일한 `game-list-170005-local-2026-08-19-frozen` fixture와 manifest를 사용해 직전 서버 commit `c837428cacb4dce077ed7d66afd80d68e0570b35`를 control, 현재 commit `7efd640d9fd017ae43de6e7296a5dcfd4e5bd93c`를 candidate로 두었다. runner commit은 `7efd640d9fd017ae43de6e7296a5dcfd4e5bd93c`, runner SHA-256은 `3e7fce5c5cd4da9ed0773365255f9e68c65068941ec642593e935223c98a3e47`이며, 양쪽 모두 warm-up 5회·측정 20회·6개 시나리오를 네 번 paired round로 실행했다. 8개 artifact는 모두 `status=success`, HTTP 오류 0건, Slice 계약 check 통과였다.

판정 기준은 각 시나리오의 네 round p95 중앙값이 control 대비 `+5%` 이내이고, relation·complex는 control보다 낮아야 한다는 기존 T5 gate다.

| 시나리오 | control p95 중앙값 | 교집합 query p95 중앙값 | 변화 | 판정 |
| --- | ---: | ---: | ---: | --- |
| base | 21.155ms | 19.684ms | -6.95% | 통과 |
| keyword | 16.332ms | 16.320ms | -0.08% | 통과 |
| player-count | 15.780ms | 14.327ms | -9.21% | 통과 |
| relation-theme-mechanism | 143.406ms | 35.282ms | -75.40% | 통과 |
| complex | 114.616ms | 38.908ms | -66.05% | 통과 |
| flags-upcoming-exact | 16.853ms | 16.592ms | -1.55% | 통과 |

전체 회귀 및 relation·complex 개선 gate를 만족했으므로 교집합 query variant를 최종 선택한다. 일부 round에서 로컬 Docker host 변동으로 candidate p95 outlier가 있었지만, 사전 합의한 네 round 중앙값 기준은 통과했다.

동일 DB에서 수행한 보조 `EXPLAIN (ANALYZE, BUFFERS)`는 기존 분리 subquery의 실행 시간이 `302.812ms`, `games_pkey` lookup 24,419회였고, 교집합 subquery는 `39.516ms`, `games_pkey` lookup 70회였다. 교집합 plan은 `idx_game_mechanism_relations_mechanism_game`와 기존 `game_theme_relations_pkey`를 사용하며 새 migration은 필요하지 않다. 이 단일 실행 시간은 p95 gate를 대체하지 않고 query 구조를 설명하는 진단 증거로만 사용한다.

focused 검증은 `GameMatchModeHttpIntegrationTest`의 ANY/ALL·Slice 보존 테스트와 `GameListFilterPostgresTest`의 uncorrelated subquery·기존 index 보존 테스트를 통과했다.

## App·PostgreSQL 자원

CPU는 Docker stats가 보고한 컨테이너 CPU percentage라서 PostgreSQL처럼 multi-core를 사용하는 컨테이너는 100%를 넘을 수 있다. memory는 사용량 MiB의 단계별 최댓값이다. DB connection은 `pg_stat_activity`의 단계별 최댓값이다.

| 동시 VU | App1 CPU | App2 CPU | PostgreSQL CPU | App1 memory | App2 memory | PostgreSQL memory | DB total / active | restart |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 10.32% | 10.46% | 110.05% | 691.4MiB | 702.7MiB | 251.4MiB | 29 / 3 | 0 |
| 4 | 11.20% | 11.45% | 157.73% | 693.4MiB | 704.4MiB | 254.1MiB | 29 / 4 | 0 |
| 8 | 17.06% | 16.03% | 185.96% | 695.4MiB | 707.6MiB | 258.7MiB | 29 / 8 | 0 |

메모리 사용량과 전체 connection 수는 단계가 올라가도 큰 변화가 없었고, active connection은 VU와 함께 3→4→8로 증가했다. `wait_event IS NOT NULL` 표본은 단계별 28이었지만 PostgreSQL idle client session도 포함하므로, 이를 DB query queue 길이로 해석하지 않는다.

## 보존·한계

최종 결과 JSON과 단계별 k6 summary/log는 로컬 `build/k6/game-list-867/`에 생성했고 Git에는 커밋하지 않는다. runner는 App image OCI revision과 서버 commit, fixture manifest SHA-256, runner/k6 파일 SHA-256을 함께 검증했다.

이번 측정은 동일한 macOS Docker Desktop 호스트에서 warm-cache에 가까운 격리 환경으로 수행했다. production 네트워크, 외부 observability, 실제 App/DB 인스턴스 크기, 장시간 soak, 장애 주입은 포함하지 않는다. 따라서 V1 선택은 [후보 비교 결과](../../results/game-list-740/game-list-867-2026-08-19.md)의 순차 p95 gate로 확정하고, 본 문서는 동시 부하에서 오류·자원·tail latency를 확인한 보조 증거로 사용한다.
