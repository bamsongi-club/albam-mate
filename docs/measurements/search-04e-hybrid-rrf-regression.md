# SEARCH-04e Hybrid/RRF 동일 corpus live evidence

> 문서 상태: #1002 live evidence 재생성 · `timeout-observed` (40건 중 34건 완료, 6건 timeout) · 현재 serving 계약값은 `K=200`, `RRF k=60`, 공통 timeout `6초`입니다. 이 문서는 [ADR-0088](../adr/game/0088-search-04-hybrid-rrf-parallel-candidate-generation.md)의 근거를 실제 승인 corpus와 production SQL shape로 갱신한 기록입니다.

## 결론

이전 #983 evidence는 101개 합성 corpus와 `@MockitoBean DenseCandidateSource`를 사용했으므로 #1002의 production 상수 근거로 폐기합니다. 새 evidence는 승인된 1,000개 `search_text`, 같은 release와 active READY pgvector index, 실제 Cloudflare BGE-M3 query embedding, 실제 PostgreSQL sparse query shape를 고정해 8개 질의를 비교합니다.

현재 결과만으로는 품질 qrels 없이 `K=200`의 relevance 안전성을 최종 확정할 수 없습니다. full sparse pool의 top20과 비교하면 K=200 일치율은 질의별 16~19개, K=400은 19~20개, K=800은 20개였습니다. 따라서 serving 값은 기존 계약을 유지하되 `K=400`을 다음 품질 qrels 재검토의 우선 후보로 기록합니다. 이 문서는 qrels 없는 상태에서 K를 자동 승격하지 않습니다.

실제 동시 실행에서는 Dense와 Sparse를 공통 6초 deadline으로 시작한 40건 중 34건이 완료되고 6건이 timeout되었습니다. 관찰된 parallel p95는 6,002.960ms, fusion을 포함한 `observedParallelP95Ms`는 6,004.534ms이므로 이번 evidence는 공통 timeout 6초의 통과 근거가 아니라 timeout 관측과 후속 성능 검토가 필요한 상태를 기록합니다.

## 동일 corpus·release·index 고정

| 항목 | 값 |
| --- | --- |
| 승인 corpus | [`search-text-top1000.json`](../p2/search-evaluation/dense-bge-m3/search-text-top1000.json), 1,000행 |
| `search_text` SHA-256 | `ec364be3a34268d1bb6d27e3c41e2cdd31852565eec79fa31faaacda17af4ece` |
| BGG game ID membership SHA-256 | `87aff382f7a91bff93d5eddf4ee7b048bbef22e5e220a6295c0c09821d87a353` |
| execution manifest SHA-256 | `f61b97630e53edd3f6da6c421bbe4545e6efe2321ef676b0aa3377e2e3504b7b` |
| catalog release / field | `bgg-catalog-170k-v4-2026-08-19` / `catalog-fields-v1` |
| active index | `c245b97b-0b4e-43ec-987c-ea359e4a2e37`, `READY`, exact cosine |
| index provider / model | `cloudflare-workers-ai` / `@cf/baai/bge-m3` |
| dimension / normalization | 1,024 / L2 normalized |
| 내부 game ID membership / manifest SHA-256 | `fdcf8bacd8b8a7c5e8961c02b50ff727ca3460f6846b5b3dddc9dae16d1c1c92` |
| runner commit / file SHA-256 | `26a746c22b97826618ffe2c21fb67d212958bdc9` / `30d05620b42fd431bb22e889816d55c13419dd4b4aa29a2e90a14c9f0f6aecbb` |
| runner source 상태 | clean |
| execution result SHA-256 | `0c7570965bd504fdee115c073a27b655ec4f1e1394d747c27bcb6d79d82390bd` |

BGG ID와 DB 내부 `games.id`는 서로 다른 식별자이므로, runner가 승인 corpus의 BGG ID를 DB 내부 ID로 매핑한 뒤 index membership과 다시 대조합니다. Dense·corpus 비교 결과는 BGG ID로 기록하고, 전체 games serving 결과의 top20은 DB 내부 `games.id` namespace로 별도 기록합니다. index의 원시 vector나 provider 응답은 결과 artifact에 저장하지 않습니다.

## 실행 경로와 재현

- runner: [`search-04e-hybrid-rrf-live.mjs`](../../scripts/measurements/search-04e-hybrid-rrf-live.mjs)
- 고정 execution manifest: [`search-04e-hybrid-rrf-live.manifest.json`](./search-04e-hybrid-rrf-live.manifest.json)
- 보존 결과: [`search-04e-hybrid-rrf-live.json`](./search-04e-hybrid-rrf-live.json)
- 회귀 검증: [`search-04e-hybrid-rrf-live.test.mjs`](../../scripts/measurements/search-04e-hybrid-rrf-live.test.mjs)
- Dense: active pgvector index의 동일 corpus 1,000행에서 `PgVectorDenseCandidateSource`와 같은 top-1,000 SQL을 실행하고, Cloudflare Workers AI direct REST `@cf/baai/bge-m3` query embedding 40회(8개 질의×5회)를 같은 dense branch에서 측정
- Sparse serving: `StructuredSparseCandidateSource`와 같은 전체 `games` 범위·field weight·public relation·`LIMIT 200` SQL을 실제 PostgreSQL에 40회 실행
- Sparse corpus 비교: 승인 1,000개 내부 ID CTE와 `LIMIT 1000`을 별도 실행해 full-pool overlap 비교에만 사용. serving latency와 corpus 비교 latency를 섞지 않음
- Fusion: Dense 1,000행과 Sparse full pool을 기준으로 K `[50, 100, 200, 400, 800, 1000]`, RRF k `[10, 30, 60, 100, 200]`을 비교
- timeout: 각 질의에서 Dense(Cloudflare 5초 provider timeout + pgvector)와 Sparse serving을 실제로 동시에 시작하고, 공통 6초 deadline 안의 요청별 status·완료·실패·경과 시간을 보존. 이번 실행은 40건 중 6건이 timeout-observed였습니다.

```bash
set -a; . /path/to/albam-mate/.env; set +a
node scripts/measurements/search-04e-hybrid-rrf-live.mjs \
  --search-text docs/p2/search-evaluation/dense-bge-m3/search-text-top1000.json \
  --postgres-container <postgres-container> \
  --manifest docs/measurements/search-04e-hybrid-rrf-live.manifest.json \
  --out docs/measurements/search-04e-hybrid-rrf-live.json

node scripts/measurements/search-04e-hybrid-rrf-live.mjs \
  --validate docs/measurements/search-04e-hybrid-rrf-live.json \
  --manifest docs/measurements/search-04e-hybrid-rrf-live.manifest.json
node --test scripts/measurements/search-04e-hybrid-rrf-live.test.mjs
```

실행 시 `CLOUDFLARE_ACCOUNT_ID`와 `CLOUDFLARE_API_TOKEN`의 존재만 확인하며 실제 token은 로그·artifact에 기록하지 않습니다. 현재 결과는 local active index에 대한 측정이며 production cutover를 의미하지 않습니다.

## 회귀 질의와 candidate K 비교

`overlap`은 각 K의 hybrid top20이 Sparse full pool을 K=1000으로 실행한 hybrid top20과 공유하는 개수입니다. `dropped`는 full-pool top20에서 빠진 개수입니다.

| fixture | 질의 | sparse full | Dense anchor rank | K=200 overlap / dropped | K=400 overlap / dropped | K=800 overlap / dropped |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| STONE-01 | 일꾼 놓고 밥 먹이는 게임 | 1,000 | 38 | 17 / 3 | 20 / 0 | 20 / 0 |
| STONE-02 | 일꾼 배치하고 식량으로 부족을 부양하는 게임 | 1,000 | 15 | 18 / 2 | 20 / 0 | 20 / 0 |
| STONE-03 | place workers and feed your population | 763 | 12 | 19 / 1 | 20 / 0 | 20 / 0 |
| STONE-04 | worker placement and food management game | 937 | 97 | 16 / 4 | 19 / 1 | 20 / 0 |
| COMMON-01 | 게임 | 1,000 | - | 16 / 4 | 20 / 0 | 20 / 0 |
| COMMON-02 | game | 731 | - | 17 / 3 | 19 / 1 | 20 / 0 |
| COMMON-03 | 플레이어 | 273 | - | 18 / 2 | 20 / 0 | 20 / 0 |
| COMMON-04 | 카드 게임 | 1,000 | - | 19 / 1 | 19 / 1 | 20 / 0 |

Common query 4개 모두 Sparse full count가 200을 넘고 Stone Age 질의 4개도 같은 조건을 충족하므로, 이전 101-game corpus에서 불가능했던 truncation 비교를 실제 승인 corpus에서 수행했습니다. RRF k=60을 기준으로 top20 overlap 범위는 k=10에서 10~15개, k=30에서 15~19개, k=100에서 16~18개, k=200에서 13~17개였습니다. 이 지표는 relevance qrels를 대체하지 않으므로 RRF k=60은 보수적으로 유지합니다.

## latency와 timeout

| 단계 | samples | p50 (ms) | p95 (ms) | max (ms) |
| --- | ---: | ---: | ---: | ---: |
| Cloudflare Dense query embedding | 40 | 197.979 | 1,548.654 | 2,012.476 |
| Dense pgvector candidate query | 40 | 99.957 | 509.879 | 1,135.408 |
| Sparse serving SQL · all games `LIMIT 200` | 40 | 1,089.321 | 6,002.960 | 6,008.459 |
| Sparse corpus SQL · approved corpus `LIMIT 1000` | 8 | 120.292 | 876.134 | 876.134 |
| Parallel Dense + Sparse | 40 | 1,930.188 | 6,002.960 | 6,008.459 |
| Fusion | 34 | 0.396 | 1.574 | 1.620 |

`parallel` p95는 각 요청에서 Dense와 Sparse를 실제 동시 시작한 뒤의 요청별 경과시간 p95이고, `observedParallelP95Ms`는 여기에 완료된 요청의 fusion p95를 더한 `6,004.534ms`입니다. Cloudflare provider timeout은 5초로 고정하고 공통 deadline은 6초로 비교했습니다. 6건의 timeout이 관측되었으므로 공통 timeout 6초를 통과했다고 해석하지 않으며, 이 값은 실제 장애율·p99 SLO를 확정하는 측정도 아닙니다.

### #1053 sparse 텍스트 인덱스 후속 측정

2026-08-24 KST에 승인된 catalog release의 `01-games-full.sql` artifact(170,005행, SHA-256 `7866812e8ecd22942eccc3dee4553b49161af6297399c907b6a2953a9abb3c19`, 206,704,274 bytes)를 PostgreSQL 18 Testcontainers에 적재하고, `rejuvenation` 3글자 이상 sparse serving query를 migration 전후 5회씩 실행했다. baseline은 V39의 새 trigram·bigram 인덱스를 제거한 상태이며, 최종 token 길이별 `UNION` query shape는 유지했다. candidate에서는 기존 2글자 이름 검색 계약을 대표하는 `게임` query도 5회 실행했다. 같은 T5 실행에서 V39의 extension·function과 7개 GIN 인덱스를 포함한 전체 migration wall-clock도 기록한다.

| 상태 | 실행 시간 배열 (ms) | p50 (ms) | p95 (ms) | max (ms) |
| --- | --- | ---: | ---: | ---: |
| V39 migration 전체 적용 | `23,688.132` | - | - | 23,688.132 |
| baseline · `rejuvenation` | `[282.373, 330.017, 273.574, 268.916, 277.177]` | 277.177 | 330.017 | 330.017 |
| V39 candidate · `rejuvenation` | `[4.547, 2.656, 2.729, 2.690, 2.554]` | 2.690 | 4.547 | 4.547 |
| V39 candidate · `게임` | `[53.013, 38.613, 30.443, 30.287, 29.778]` | 30.443 | 53.013 | 53.013 |

baseline 계획에는 `Seq Scan on games`가 있었고, `rejuvenation` candidate 계획은 4개 trigram GIN 경로를, `게임` candidate 계획은 4개 bigram expression GIN 경로를 사용하며 두 계획 모두 `games` Seq Scan이 없었다. 두 candidate max 모두 6초 deadline보다 작았다. 이 결과는 승인 fixture에서의 PostgreSQL 계획·회귀 측정이며, 기존 40건 live evidence의 timeout 관측값이나 production p95/p99 SLO를 대체하지 않는다. fixture가 없는 일반 CI에서는 T5 대규모 측정을 실행하지 않고, 승인 artifact 경로를 제공한 측정 환경에서만 checksum·행 수와 함께 재현한다. 이번 실행의 V39 전체 적용 시간은 `migrationMs=23,688.132ms`였다.

#### V39 배포 경계

V39은 일반 `CREATE INDEX`로 7개 GIN 인덱스를 순차 생성한다. PostgreSQL의 일반 인덱스 생성은 각 빌드 동안 `games` 쓰기와 경합할 수 있으므로, 아래 `migrationMs`는 승인 fixture와 Testcontainers에서의 참고값일 뿐 production 허용 다운타임이나 무중단 보장이 아니다. 현재 저장소의 Flyway는 애플리케이션 시작 시 transactional migration을 실행하므로 `CREATE INDEX CONCURRENTLY` 전환은 별도 non-transactional migrator와 배포 절차 승인이 필요한 후속 운영 범위다. 운영 적용 전에는 실제 DB 인스턴스에서 V39 전체 적용 시간을 재측정하고 쓰기 유입을 멈추는 maintenance window를 확보한다.

## 현재 상수 판정

| 상수 | 현재 serving 값 | 이번 live evidence 판정 |
| --- | ---: | --- |
| Sparse candidate K | 200 | 유지. full-pool top20 손실이 관찰되어 최종 relevance 안전성은 미확정이며 K=400을 다음 qrels 검토 후보로 기록 |
| RRF k | 60 | 유지. 비교 결과는 기록했지만 8개 질의만으로 relevance 우열을 확정하지 않음 |
| 공통 timeout | 6초 | 계약값은 유지하되 이번 실행은 `timeout-observed`로 판정. 40건 중 6건 timeout, `observedParallelP95Ms=6,004.534ms`이므로 6초 통과 근거로 사용하지 않음 |

## 재발 방지 경계

`search-04e-hybrid-rrf-live.test.mjs`는 다음 변조를 실패시킵니다.

- 고정 execution manifest·catalog release·quality corpus·search_text의 실제 bytes checksum 불일치
- execution manifest에 기록한 runner commit/file checksum/source clean 상태 불일치
- 결과·latency·요청별 완료 상태를 포함한 result digest 변조
- 승인 corpus와 BGG/index membership checksum 불일치
- production Dense pgvector SQL이 아닌 JS 순위 계산 또는 Dense 후보 상한 변조
- production Sparse 전체 범위·`LIMIT 200`과 corpus `LIMIT 1000` 경계 변조
- 5초 provider timeout·6초 공통 deadline·동시 실행 요청 결과 누락
- 요청 단위 `completed`·`timeout`·`failure` 상태 및 serving query status 변조
- `DenseCandidateSource`를 mock source로 대체하거나 승인되지 않은 query fixture 사용
- Stone Age 4개와 common query 4개 중 하나 누락
- common query에서 Sparse full count가 200을 넘지 않는 evidence
- active READY index의 row count·provider·model·dimension 불일치

현재 repository의 `HybridSemanticGameSearchRegressionPostgresTest`는 구현-level smoke test로만 남아 있으며, mock Dense 결과를 사용하는 이전 101-game evidence를 대체하지 않습니다.

## 폐기된 이전 evidence

이전 문서 버전의 실행은 다음 이유로 #1002 acceptance evidence에서 제외합니다.

- 101개 합성 corpus라 Sparse 결과가 200개를 넘을 수 없음
- Dense가 실제 Cloudflare 호출이 아니라 `@MockitoBean DenseCandidateSource` 합성 목록임
- 실제 승인 release/index와 Dense·Sparse가 같은 corpus를 사용했는지 검증하지 않음

그 실행은 #983의 구현 smoke test 기록으로만 해석하며, candidate K·RRF k·timeout의 현재 live 근거로 사용하지 않습니다.
