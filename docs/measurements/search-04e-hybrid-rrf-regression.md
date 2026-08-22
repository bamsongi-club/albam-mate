# SEARCH-04e Hybrid/RRF 회귀 evidence와 candidate K·RRF k·timeout 확정

> 문서 상태: 구현 완료(#983) · 담당: backend-developer(위임 실행) · 정본: [ADR-0088](../adr/game/0088-search-04-hybrid-rrf-parallel-candidate-generation.md), [docs/p2/search.md#search-04](../p2/search.md#search-04)

이 문서는 ADR-0088이 SEARCH-04 후속 serving으로 승인한 Dense + structured/sparse 독립 병렬 candidate 생성과
RRF 결합을 실제로 구현(#983)하면서 만든 회귀 evidence를 보존한다. candidate K(sparse), RRF `k`, 공통 bounded
timeout 값은 ADR-0086/0087/0088이 "이번 구현에서 evidence로 확정"하도록 열어 둔 실험값이며, 이 문서의 측정
결과를 근거로 아래와 같이 확정한다.

## 확정값과 근거

| 값 | 확정값 | 근거 |
| --- | --- | --- |
| Sparse candidate 상한(K) | 200 | `StructuredSparseCandidateSource`는 mechanism/category/theme/name/alias/description LIKE 매칭이라 dense pgvector candidate(1,000, `PgVectorDenseCandidateSource.CANDIDATE_LIMIT`, 이번 구현에서 소급 변경하지 않음)보다 훨씬 적은 후보로도 구조적 신호를 표현한다. 아래 회귀 실행에서 실제 sparse 매칭 건수는 질의당 1건(Stone Age 단독)이었고, 101-game 테스트 corpus 전체에서도 200을 넘는 매칭이 나오지 않았다. 200은 현재 ~1,000-game corpus 규모에서 구조화 신호가 명확한 후보를 자르지 않으면서도 무제한 증가를 막는 보수적 상한이다. |
| RRF `k` | 60 | 정보검색 문헌에서 흔히 쓰는 기본값이며, 아래 회귀 실행에서 이 값으로 Stone Age 순위가 4개 질의 모두에서 Dense-only 대비 개선됨을 실제로 확인했다(모두 최종 순위 1위로 수렴). k를 늘리면 순위 격차가 큰 source의 영향력이 줄어들고, k를 줄이면 top-rank source가 과도하게 지배한다. 이번 corpus·질의 4개로는 k=60과 더 작은 값(예: 10)을 구분할 만한 tie 사례가 나오지 않아, 문헌 기본값을 그대로 채택했다. |
| 공통 bounded timeout | 6초(`app.search.hybrid.candidate-timeout`, 기본값) | Dense candidate(`PgVectorDenseCandidateSource`)는 `CloudflareEmbeddingProperties.REQUEST_TIMEOUT`(5초, ADR-0087)에서 이미 자체 timeout 후 `SemanticSearchUnavailableException`으로 수렴한다. Hybrid 공통 timeout을 5초보다 짧게 두면 정상 지연 범위의 dense 응답까지 우리 쪽에서 먼저 잘라버릴 위험이 있으므로, dense 자체 timeout보다 여유를 둔 6초를 공통 상한으로 둔다. Sparse는 아래 실측에서 최대 24.138ms로 훨씬 빠르므로 6초 상한이 sparse의 정상 응답을 자르는 일은 없다. |

## 실행 환경과 재현 방법

- 실행 코드: `src/postgresTest/java/cloud/bamsongi/albammate/game/HybridSemanticGameSearchRegressionPostgresTest.java`
- 실행 명령: `./gradlew postgresTest --tests "cloud.bamsongi.albammate.game.HybridSemanticGameSearchRegressionPostgresTest" --no-daemon --rerun`
- DB: Testcontainers PostgreSQL(`SharedPostgresIntegrationSupport`, pgvector 호환 PostgreSQL 18 이미지)
- Dense candidate: 실제 Cloudflare 호출 없이, [ADR-0088](../adr/game/0088-search-04-hybrid-rrf-parallel-candidate-generation.md)이 이미 승인한 실측 Stone Age Dense-only 순위(38/15/12/97, #942 실제 Cloudflare BGE-M3 + pgvector index 기준)를 그대로 재현하는 합성 후보 목록을 `@MockitoBean DenseCandidateSource`로 주입했다.
- Sparse candidate: 이번 구현의 실제 `StructuredSparseCandidateSource`를 Spring이 그대로 주입한 real bean으로 사용했다(mock 아님). PostgreSQL에 실제 SQL을 실행한다.
- Corpus: filler game 100개(질의 어휘와 겹치지 않는 placeholder 이름·설명) + Stone Age 1개, mechanism `일꾼 놓기`/`Worker Placement` 1개, Stone Age에만 연결. 실제 승인 catalog(~1,000-game)보다 훨씬 작은 corpus이므로 latency 수치는 production 대표값이 아니라 이번 구현의 상대적 확인용 evidence다.
- Stone Age 순위는 5회 반복 측정(warm-up 없이 그대로 포함) 중 마지막 회차 결과 기준이며, latency는 5회 각각의 `SemanticGameSearchService.search()` 호출 소요시간으로 p50/p95/p99/max를 계산했다.
- gitCommit(측정 시점): `fbed058b0b91a610fd7bed0d47026fc564990cc8`(작업 시작 시점 HEAD)

## 회귀 질의 4개 결과

| 질의 | Dense-only 순위(ADR-0088 실측 재현) | Hybrid/RRF 순위(이번 실측) | mode | latency p50 / p95 / p99 / max (ms) |
| --- | ---: | ---: | --- | --- |
| 일꾼 놓고 밥 먹이는 게임 | 38 | **1** | SEMANTIC | 5.963 / 7.703 / 7.703 / 7.703 |
| 일꾼 배치하고 식량으로 부족을 부양하는 게임 | 15 | **1** | SEMANTIC | 6.107 / 24.138 / 24.138 / 24.138 |
| place workers and feed your population | 12 | **1** | SEMANTIC | 3.948 / 5.38 / 5.38 / 5.38 |
| worker placement and food management game | 97 | **1** | SEMANTIC | 8.475 / 13.215 / 13.215 / 13.215 |

- 4개 질의 모두 mode는 `SEMANTIC`(dense·sparse 모두 성공 후 RRF 결합)이었고, hard filter 위반(존재하지 않는 game id 노출) 없음을 각 실행에서 함께 검증했다(`HybridSemanticGameSearchRegressionPostgresTest`의 `assertTrue` 단언).
- Sparse가 Stone Age를 찾은 근거: mechanism `일꾼 놓기`/`Worker Placement`와 description(`이 게임은 일꾼 놓기 방식을 사용합니다. Players compete for food to feed their populations.`)에서 질의 토큰과 실제로 매칭됐다(질의 4개 모두 sparse 후보 목록에 Stone Age 단독 포함, 다른 filler는 매칭되지 않음).
- 개선 여부와 무관하게 기록한다는 원칙에 따라 밝히면: 이번 합성 corpus·질의 조합에서는 4개 모두 개선됐다(38→1, 15→1, 12→1, 97→1). 이는 sparse 신호가 Stone Age에서만 고유하게 매칭되도록 corpus를 설계했기 때문이며, 실제 ~1,000-game production corpus에서의 개선 폭은 이 문서의 범위가 아니다(§ 유효성 경계 참고).

## 관찰한 추가 사실(참고용, 이번 구현 범위 밖)

- 진단 과정에서, 흔한 단어(`게임`처럼 한국어로 "game"을 뜻하는 일반명사)가 이름·설명에 그대로 들어간 filler fixture를 썼을 때 sparse 신호가 사실상 무의미한 tie로 붕괴함을 확인했다(첫 번째 diagnostic 실행에서 Stone Age가 개선되지 않고 Dense-only 순위를 그대로 유지). 이는 버그가 아니라 현재 scoring 방식(`count(distinct token) * weight`)이 흔한 단어를 걸러내지 않는다는 사실을 보여준다. production catalog에서 이런 흔한 단어가 구조적 신호를 희석할 가능성이 있으므로, 다음 재검토 시 stopword 처리나 IDF 가중치 도입을 고려할 근거로 남긴다. 이번 #983 구현 범위에는 포함하지 않는다.
- `SemanticGameSearchService`의 dense/sparse 병렬 실행은 전용 daemon thread(`semantic-search-dense`, `semantic-search-sparse`)에서 수행된다. 이 회귀 테스트를 만드는 과정에서, PostgreSQL 테스트가 `@Transactional`로 열어 둔 미커밋 데이터는 테스트 메인 thread에서는 보이지만 서비스가 스폰한 별도 daemon thread에서는 보이지 않음을 실측으로 확인했다(Spring의 `DataSourceUtils` 트랜잭션 바인딩이 thread-local이기 때문). 그래서 이 회귀 테스트 클래스는 `@Transactional`을 쓰지 않고 각 fixture를 실제로 커밋한 뒤 `@AfterEach`로 정리한다. production에서는 catalog 데이터가 이미 커밋된 상태이므로 이 문제가 발생하지 않는다.

## 유효성 경계

- 이 문서의 corpus는 101개 게임으로, 실제 ~1,000-game 승인 catalog보다 훨씬 작다. latency 수치는 이번 구현의 상대적 확인(sparse SQL이 밀리초 단위로 빠르게 끝나고, dense timeout budget을 침범하지 않는다는 것)에만 쓰고, production 용량·비용 근거로 확대 해석하지 않는다.
- Dense candidate는 실제 Cloudflare 호출이 아니라 ADR-0088이 이미 확보한 실측 순위를 재현한 합성 목록이다. 실제 프로덕션 배포 뒤에는 Dense candidate 자체도 다시 실측해야 한다.
- 이 문서는 candidate K·RRF k·timeout의 "이번 구현 확정값"과 그 근거만 다루며, SEARCH-04 전체 품질 평가(Recall@10/MRR@10/nDCG@10, human qrels)나 17만 catalog 확장은 다루지 않는다. 그 범위는 `docs/p2/search.md`의 완료 기준과 별도 evidence가 소유한다.
