# ADR-0088: SEARCH-04 후속 serving은 Dense + structured/sparse 병렬 후보를 RRF로 결합한다

- 상태: 승인됨
- 작성일: 2026-08-22
- 결정일: 2026-08-22
- 관련: [SEARCH-04 검색 명세](../../p2/search.md#search-04), [ADR-0086](0086-search-04-dense-serving-architecture.md), [ADR-0087](0087-search-04-cloudflare-managed-bge-m3-serving.md), [#973 DECISION 이슈](https://github.com/bamsongi-club/albam-mate/issues/973), [#973 결정 코멘트](https://github.com/bamsongi-club/albam-mate/issues/973#issuecomment-5372473896), [#983 SEARCH-04e 구현 이슈](https://github.com/bamsongi-club/albam-mate/issues/983)
- 대체 대상: [ADR-0087](0087-search-04-cloudflare-managed-bge-m3-serving.md)의 "보류 및 재검토" 절 중 `HNSW/Hybrid/RRF` 항목만 대체한다. ADR-0087의 provider 선택(Cloudflare managed BGE-M3 direct REST)·index 저장(pgvector exact cosine)·fail-closed 기본값 결정은 그대로 유지하며 대체하지 않는다.
- 후속 ADR: 없음

## 맥락

ADR-0087은 SEARCH-04 dense serving을 Cloudflare managed BGE-M3 + pgvector로 전환하면서 "지금 하지 않는 것"에 `HNSW/Hybrid/RRF`를 명시했다. 이 결정 자체는 provider/runtime 교체 범위로 한정됐고, Hybrid/RRF 도입 여부는 별도로 열어 둔 재검토 대상이었다.

#942의 실제 Cloudflare BGE-M3/pgvector index delivery 이후, Stone Age(BGG 34635)를 대상으로 표현 변형 질의 4종을 같은 1,000-game index에서 재현했다.

| Query | Dense | Sparse/structured | Hybrid/RRF |
| --- | ---: | ---: | ---: |
| `일꾼 놓고 밥 먹이는 게임` | 38 | 11 | **6** |
| `일꾼 배치하고 식량으로 부족을 부양하는 게임` | 15 | 11 | **3** |
| `place workers and feed your population` | 12 | 15 | **4** |
| `worker placement and food management game` | 97 | 16 | **20** |

Stone Age의 search text에는 이미 `일꾼 놓기`, `compete for food`, `feed their populations`가 포함돼 있어 데이터 부재 문제가 아니었다. Dense-only는 같은 의도의 표현 변화만으로 순위가 `#12`~`#97`까지 흔들렸고, 구어체·복합 조건(conjunction)에서 불안정성이 더 크게 관찰됐다. 기존 structured signal(`WORKER_PLACEMENT`)을 결합한 Hybrid/RRF는 네 질의 모두에서 Dense-only 대비 순위가 개선됐다.

판단 기준은 다음과 같다.

1. #836/PR #950의 `DenseCandidateSource`와 P1 hard-filter 재검증·결정적 pagination·fallback 계약을 소급 변경하지 않는다.
2. ADR-0087의 provider/index 결정(Cloudflare BGE-M3, pgvector exact cosine, fail-closed 기본값)을 바꾸지 않는다.
3. Dense 표현 민감도 문제를 구조적으로 완화하되, candidate 생성 지연을 직렬 누적시키지 않는다.
4. 부분 실패(Dense만 실패, Sparse만 실패, 둘 다 실패)를 기존 `LEXICAL_FALLBACK`/`UNAVAILABLE` 의미와 충돌 없이 수렴시킨다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| Dense-only 유지 (ADR-0087 현행 유지) | 추가 구현·장애 지점이 없다. | Stone Age 재현 실험에서 표현 변형에 따라 순위가 `#12`~`#97`까지 흔들리는 문제가 그대로 남는다. | 제외 |
| Dense + Sparse 직렬 실행 후 RRF 결합 | 구현이 단순하고 공유 상태 경합이 없다. | 두 candidate source의 latency가 누적되어 사용자 체감 지연이 늘어난다. | 제외 |
| Dense + Sparse **독립 병렬** 실행 후 bounded timeout 안에서 RRF 결합 | 두 candidate source의 latency가 겹쳐 전체 지연이 느린 쪽 하나에 수렴한다. 표현 변형 질의에서 순위가 개선된다(위 실험표). | 병렬 실행·부분 실패·공통 timeout budget을 새로 설계해야 하고, candidate K·RRF `k`·tie-break를 production 값으로 확정하는 별도 evidence가 필요하다. | 선택 |

## 결정

### 1. Dense와 structured/sparse candidate 생성은 독립 병렬 실행을 기본으로 한다

`DenseCandidateSource`(ADR-0087의 Cloudflare BGE-M3 + pgvector 구현)는 그대로 두고, mechanism/category/theme/name/alias/description 계열에서 후보를 생성하는 sparse/structured candidate source를 신설한다. 두 candidate source는 공통 bounded timeout budget 안에서 서로 독립적으로(한쪽이 다른 쪽의 시작을 막지 않게) 실행한다.

### 2. 결합은 RRF 계열로 하되 세부 값은 #983 evidence로 확정한다

두 candidate list는 RRF(Reciprocal Rank Fusion)로 결합한다. candidate K, sparse scoring 방식, RRF `k`는 이 ADR이 고정하지 않는다. #983 구현에서 이슈에 명시된 회귀 질의로 만든 실측 evidence를 근거로 확정하고, 그 값과 근거를 `docs/p2/search.md`와 구현 PR에 남긴다. RRF는 순위 결합으로만 쓰며 hard filter를 대체하지 않는다.

### 3. 기존 P1 계약과 fallback 경계는 그대로 유지한다

Hybrid 병합 후보도 기존 `GameListSpecification`으로 P1 hard filter·`playedFilter`·현재 catalog 유효성을 재검증하고, 결정적 pagination을 유지한다. Dense/Sparse 부분 실패와 공통 timeout 초과는 기존 `SemanticGameSearchMode`(`LEXICAL_FALLBACK`/`UNAVAILABLE`)와 충돌하지 않는 상태로 수렴시키며, 세부 상태 정의는 #983에서 완성한다. relevance/fusion 내부 점수는 public API 응답에 노출하지 않는다.

## 결과

- 얻는 것:
  - Dense-only의 표현 변화 민감도를 구조적으로 완화할 수 있는 경로(Hybrid/RRF)를 SEARCH-04 후속 serving의 승인 범위로 연다.
  - ADR-0087의 provider/index 선택과 #836/PR #950의 기존 계약을 소급 변경하지 않는다.
- 감수할 비용·위험:
  - candidate source가 두 개로 늘어나 장애 지점과 운영 관측 대상이 늘어난다.
  - candidate K·RRF `k`·timeout 최종값이 이 ADR 시점에는 미확정이라, #983 evidence가 부실하면 production 값의 근거가 약해질 위험이 있다.
- 후속 작업:
  - #983에서 sparse/structured candidate source, 병렬 실행, RRF 결합, 부분 실패·timeout 경계, P1 계약 재검증을 구현하고 PostgreSQL로 검증한다.
  - 확정한 candidate K·RRF `k`·timeout과 그 근거를 `docs/p2/search.md`에 반영한다.

## 보류 및 재검토

- 지금 하지 않는 것: 17만 전체 catalog 확대·backfill, AI Gateway/cache, 새 quality fixture·human qrels 신설, 검색 결과에 내부 relevance/vector/fusion score 공개, `GET /api/games?keyword=...` 등 기존 P1 계약 변경
- 보류 이유: 이 결정은 candidate 생성·결합 아키텍처만 다루며, catalog 규모 확장이나 품질 평가 체계 개편, 기존 공개 계약 변경은 별도 승인 범위다.
- 다시 검토할 조건: #983 evidence에서 회귀 질의 4종의 Hybrid가 Dense-only 대비 개선되지 않거나, 병렬 실행의 운영 latency/장애율이 기준을 넘거나, full-catalog로 확장하거나, 승인된 human qrels/Final Quality gate 근거가 새로 생길 때

## 참고 자료

- 이 문서의 맥락·대안으로 갈음.

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
  - #983의 실제 sparse/structured candidate source·병렬 실행·RRF 결합 구현과 H2/PostgreSQL 검증
  - candidate K·RRF `k`·timeout production 값의 evidence 기반 확정과 문서 반영
  - Hybrid/RRF의 운영 배포 이후 latency·장애율 실측

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
