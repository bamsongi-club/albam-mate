# 보드게임 의미 기반 검색 대안 조사 (2026-08-11)

## 결론

Albam Mate에는 **구조화 의도 해석 + 기존 필터 + 게임명 `pg_trgm`** 조합을 먼저 권장한다. 현재 카탈로그는 인원·시간·복잡도·카테고리·테마·메커니즘·추천/베스트 인원처럼 의미가 검수된 필드를 이미 가지고 있으므로, “6명이 30분 안에 할 쉬운 협력 게임”을 곧바로 이 필터들로 바꾸는 편이 vector 검색보다 제약을 정확하게 지키고 추가 인프라도 적다.

Semantic/vector search는 대체재라기보다 **자유서술형 탐색을 보강하는 후속 후보**다. “분위기가 따뜻하고 대화가 많은 게임”처럼 구조화 필드에 없는 뜻을 찾으려면 유용하지만, 먼저 검색할 한국어 설명문과 관련성 평가 데이터가 있어야 한다. 도입한다면 단독 vector 검색보다 **lexical + vector 후보를 RRF(Reciprocal Rank Fusion, 순위 기반 결합)로 합치고 구조화 필터를 필수 제약으로 적용하는 hybrid**가 적합하다. pgvector와 Elasticsearch 모두 full-text와 vector 결과를 결합하는 방식을 공식적으로 안내한다.

별도 Elasticsearch/OpenSearch 클러스터는 “semantic search라서”가 아니라 **한국어 형태소 분석, 다중 필드 relevance 튜닝, 검색 운영 도구가 PostgreSQL만으로 부족해졌을 때** 검토한다. 검색 엔진을 추가하면 색인 동기화·장애 지점·운영비도 함께 추가된다.

이 메모는 후보 조사이며 현재 [P1 검색 계약](../p1/search.md), 인덱스, 정렬 또는 운영 구성을 바꾸지 않는다.

## 쉬운 구분

- 구조화 필터는 사용자의 문장을 **정확한 체크박스 조건으로 번역**한다.
- lexical 검색은 문서 안에서 **같거나 비슷한 글자와 단어**를 찾는다.
- vector 검색은 문장들을 좌표로 바꾼 뒤 **뜻이 가까운 문장**을 찾는다.
- hybrid 검색은 lexical과 vector가 각각 고른 후보를 **한 순위표로 합친다**.
- reranker는 이미 고른 소수 후보를 더 큰 모델이 **마지막으로 재채점**한다.

Semantic search는 제품명이 아니라 의미 기반 검색 방식의 범주다. 흔히 embedding vector를 사용하지만, Albam Mate처럼 검색 의도가 검수된 필드와 강하게 겹치면 규칙·사전·필터만으로도 의미 기반 경험의 상당 부분을 만들 수 있다.

## 후보 비교

아래 정확도·비용·난이도는 제품 데이터와 현재 PostgreSQL 구조를 기준으로 한 **상대 평가**다. 실제 검색 품질은 모델 이름이나 엔진 기능이 아니라 대표 질의와 관련성 판정으로 비교해야 한다.

| 접근법 | 잘 맞는 검색 의도 | 예상 정확도 | 상대 비용 | 구현 난이도 | Albam Mate 판단 |
| --- | --- | --- | --- | --- | --- |
| 구조화 의도 해석 + 검수 필터 | “4명”, “60분 이하”, “복잡도 2 이하”, “협력·추리”처럼 현재 필드로 표현되는 조건 | 조건 추출이 맞으면 매우 높음. 수치·범주 제약을 결과가 반드시 지킬 수 있음 | 낮음 | 낮음~중간. 단위·범위·별칭 사전과 충돌 규칙 필요 | **1순위**. 현재 17만 건 검색 계약과 직접 결합 가능 |
| exact/prefix + `pg_trgm` | 정확한 게임명, 한·영문 일부, 오타·띄어쓰기 차이 | 탐색형 의미에는 낮고 이름 찾기에는 높음 | 낮음 | 낮음. PostgreSQL 안에서 유지 | **1순위 lexical 경로**. 현재 substring 인덱스 측정 근거가 있음 |
| PostgreSQL Full Text Search + 동의어/시소러스 | 설명·태그 속 단어, 구문, 도메인 별칭 | 알려진 어휘·동의어에는 중간~높음, 처음 보는 표현의 의미 유사성에는 제한적 | 낮음~중간 | 중간. `tsvector`, GIN, 사전 관리와 한국어 토큰화 검증 필요 | 검수된 한국어 설명문이 생기면 **2순위** |
| pgvector dense vector | “가볍게 웃으며 할 게임”, “브라스 느낌의 경제 게임” 같은 자유 표현 | 적합한 한국어 embedding과 문서가 있으면 의미 recall이 좋아질 수 있으나 exact title·숫자 제약은 보장하지 않음 | 중간. 문서 embedding 생성·저장, 질의 추론 필요 | 중간~높음. 모델 버전, 재색인, ANN 튜닝, 평가 필요 | 자유서술 탐색 품질 격차가 입증될 때 **후속 실험** |
| lexical 후보 + semantic reranker | 검색 결과는 대체로 맞지만 상위 순서가 아쉬운 경우 | 작은 후보 집합의 상위 정밀도 개선에 적합 | 높음. 질의마다 더 큰 모델 추론 | 중간~높음 | 후보 recall이 충분한 뒤에만 검토. 전체 17만 건 retrieval 대체용이 아님 |
| Elasticsearch/OpenSearch full-text·hybrid | 한국어 분석, 동의어, 다중 필드 점수, 검색 전용 운영·평가 기능이 필요한 경우 | 설계·튜닝에 따라 높일 수 있으나 엔진 도입만으로 보장되지 않음 | 높음. 별도 클러스터·동기화·모니터링 필요 | 높음 | PostgreSQL 후보가 요구 품질/SLO를 못 맞춘 증거가 있을 때만 검토 |
| 개인화 추천/협업 필터링 | “내가 좋아할 게임”, 비슷한 사용자의 선호 | 충분한 행동 데이터가 있으면 개인 의도에 적합 | 중간~높음 | 높음. 이벤트·피드백·콜드스타트 처리 필요 | 검색과 목적이 다름. 플레이/평가 데이터가 부족한 현재 대안은 아님 |

### 1. 구조화 의도 해석

현재 [P1 검색 명세](../p1/search.md)는 수치 범위와 검수된 관계를 명시한다. 따라서 자연어를 다음처럼 **검증 가능한 조건**으로 변환할 수 있다.

```text
"친구 6명이 30분 안에 할 쉬운 협력 게임"
→ playerCount=6
→ maxPlayTimeMinutes=30
→ complexityMax=<제품이 승인한 '쉬운' 경계>
→ mechanism/category/theme=<검수된 '협력' 코드>
```

수치와 코드 변환은 규칙·사전으로 먼저 처리하고, 해석하지 못한 표현은 조용히 추정하지 않는다. 특히 “쉬운”의 복잡도 경계나 “파티 게임”의 taxonomy 매핑은 기술값이 아니라 제품 정책이므로 승인된 사전으로 관리해야 한다. 이 접근은 vector 유사도가 높은 결과라도 6명·30분 제약을 어기는 문제를 피한다.

### 2. `pg_trgm` 이름·오타 검색

PostgreSQL `pg_trgm`은 문자열을 trigram으로 비교하고 GiST/GIN 인덱스로 similarity와 `LIKE`/`ILIKE`를 지원한다. 이름 일부와 철자가 비슷한 제목을 찾는 데 알맞지만, “협상 게임”과 “거래가 핵심인 게임”의 개념적 관계를 이해하지는 않는다. 또한 공식 문서는 검색 문자열에서 추출할 trigram이 많을수록 인덱스가 효과적이며, 추출 가능한 trigram이 없으면 전체 인덱스 스캔으로 퇴화할 수 있다고 설명한다.

현재 프로젝트의 게임명 키워드 측정(별도 측정 문서 미작성)은 `lower(name) LIKE '%keyword%'` 경로에서 임시 GIN 후보 효과를 확인했다. 이는 게임명 탐색 경로의 근거이지, semantic/vector 검색 필요성의 근거는 아니다.

### 3. PostgreSQL Full Text Search와 도메인 사전

PostgreSQL FTS는 문서를 `tsvector`, 질의를 `tsquery`로 만들고 `ts_rank`/`ts_rank_cd`로 lexical relevance를 계산한다. 여러 열을 합친 generated `tsvector`와 GIN 인덱스를 둘 수 있으며, synonym dictionary는 단어를, thesaurus dictionary는 구문까지 선호 용어로 정규화할 수 있다. 예를 들어 “마피아 게임 → 소셜 디덕션”, “덱빌딩 → 덱 구축” 같은 검수 별칭을 명시적으로 다룰 수 있다.

장점은 PostgreSQL 안에서 transaction과 필터를 함께 유지하고, 왜 매치됐는지 설명하기 쉽다는 점이다. 한계는 사전에 없는 paraphrase를 일반화하지 못한다는 점과 한국어 분석 품질을 별도로 검증해야 한다는 점이다. 공식 문서도 parser와 dictionary 구성이 토큰→lexeme 변환을 결정한다고 설명하므로, `simple` 구성을 그대로 쓰기 전에 실제 한국어 게임명·설명 corpus로 `ts_debug`와 검색 품질을 확인해야 한다.

### 4. pgvector semantic 검색

pgvector는 기본 exact nearest-neighbor 검색과 HNSW/IVFFlat approximate index를 제공한다. 공식 문서에 따르면 approximate index는 속도를 위해 recall을 일부 교환한다. HNSW는 IVFFlat보다 speed-recall trade-off가 좋지만 build가 느리고 메모리를 더 사용하며, IVFFlat은 build가 빠르고 메모리가 적지만 query 성능 trade-off가 더 크다.

Albam Mate에서 vector를 쓰려면 게임명만 embedding하지 말고, 출처와 품질이 확인된 한국어 설명·메커니즘·테마의 **검색용 문서 조립 규칙**을 먼저 고정해야 한다. 모델·버전·문서 조립 규칙이 바뀌면 embedding을 다시 생성해야 하며, 외부 embedding API를 쓰면 적재 시와 질의 시 inference 비용·가용성도 생긴다.

또한 pgvector 공식 문서는 approximate index에서 필터가 index scan 뒤 적용되어 결과 수가 줄 수 있고, iterative scan으로 더 찾아볼 수 있다고 설명한다. 인원·시간 같은 hard filter가 많은 현재 검색에서는 recall·latency를 대표 조합별로 반드시 측정해야 한다.

### 5. 전용 검색 엔진과 reranker

Elasticsearch는 lexical full-text, vector, hybrid, reranking을 한 검색 계층에서 제공하며 Korean `nori` 분석 플러그인은 `mecab-ko-dic` 기반 형태소 분석을 제공한다. OpenSearch도 embedding ingest pipeline, k-NN vector field, keyword+neural query, 점수 정규화/결합 pipeline을 공식 지원한다. 기능 폭은 넓지만 PostgreSQL과 별도로 색인을 만들고 변경을 동기화해야 하므로, 이 선택은 retrieval 알고리즘이 아니라 운영 아키텍처 결정이다.

Semantic reranker는 retrieval 후보의 순서만 다시 매긴다. Elastic 공식 문서도 더 크고 복잡한 모델을 실시간 실행하므로 작은 top-k 후보의 마지막 단계에 적합하다고 설명한다. 따라서 lexical 후보 자체가 관련 게임을 놓치는 문제를 reranker만으로 해결할 수는 없다.

## 권장 hybrid 구성

```text
사용자 질의
  ├─ 1. 규칙·검수 사전: 인원/시간/복잡도/카테고리/테마/메커니즘 추출
  ├─ 2. lexical 후보: exact title/alias → pg_trgm → (후속) 한국어 FTS
  └─ 3. optional vector 후보: 검수된 설명 embedding
             ↓
      lexical/vector 순위를 RRF로 결합
             ↓
      hard filter를 반드시 만족시키고 제품 boost 적용
             ↓
      optional top-k semantic rerank
             ↓
      안정적인 tie-break + 페이지 응답
```

적용 순서는 다음이 안전하다.

1. **현재 API 안의 검색 개선**: 이름 exact match/별칭을 가장 먼저 두고, `pg_trgm` 후보와 구조화 필터를 결합한다.
2. **자연어 필터 실험**: 실제 질의에서 수치·단위·검수 taxonomy만 추출한다. 모호한 표현은 사용자가 고칠 수 있도록 해석된 필터 chip을 보여준다.
3. **FTS 실험**: 출처가 확인된 설명문이 확보되면 한국어 tokenizer와 도메인 synonym을 비교한다.
4. **vector offline 실험**: 같은 질의·관련성 판정으로 lexical 대비 recall/nDCG 개선을 확인한다. exact 제목과 hard filter 정확도가 나빠지면 단독 vector는 채택하지 않는다.
5. **hybrid 실험**: lexical·vector 각각 top-N을 구하고 점수 크기가 아니라 순위를 사용하는 RRF로 합친다. 필요한 경우 소수 top-k만 rerank한다.
6. **검색 엔진 판단**: PostgreSQL 구현의 품질·p95·운영 한계가 재현될 때 Elasticsearch/OpenSearch를 같은 평가 세트로 비교한다.

현재 API는 `name ASC, id ASC` 정렬이 계약이므로 relevance 정렬을 기존 API에 조용히 섞으면 안 된다. 별도 검색 모드/endpoint와 평가를 먼저 두고, 계약 변경이 확정되면 명세와 ADR을 함께 갱신해야 한다.

## 평가 기준

기술 선택 전에 실제 사용자 의도를 다음 bucket으로 나눈 대표 질의와 사람이 판정한 관련 게임 목록을 만든다.

| 질의 bucket | 예시 | 우선 지표 |
| --- | --- | --- |
| 제목 탐색 | “카탄”, 철자 오류, 한·영 별칭 | MRR@10, zero-result rate |
| 구조화 조건 | “5명이 45분 안에 하는 협력 게임” | filter constraint 정확도, Recall@10 |
| 자유 탐색 | “서로 이야기 많이 하는 분위기 좋은 게임” | nDCG@10, Recall@20 |
| 혼합 | “2인용 브라스 같은 90분 이하 게임” | hard-filter 위반 0건, nDCG@10 |

Elasticsearch의 공식 ranking evaluation API도 typical query와 relevance rating을 두고 precision, recall, MRR, DCG/nDCG 등을 계산한다. 엔진을 도입하지 않더라도 같은 평가 원칙을 PostgreSQL 후보 비교에 사용할 수 있다. 온라인 성능은 현재 프로젝트 관례대로 같은 fixture·질의 분포에서 p95/p99, 오류율, DB CPU·메모리·I/O, index 크기와 embedding 재생성 시간을 함께 본다.

## 선택 요약

- **지금 바로 선택**: 구조화 의도 해석 + 검수 별칭 + 기존 필터 + `pg_trgm`.
- **설명문 검색이 필요할 때**: PostgreSQL FTS와 한국어 tokenization을 먼저 offline 비교.
- **표현이 달라도 같은 뜻을 찾아야 하고 lexical 격차가 측정될 때**: pgvector를 추가한 PostgreSQL hybrid.
- **상위 순서만 문제일 때**: 작은 후보 집합 semantic reranker.
- **한국어 분석·다중 필드 relevance·검색 운영 도구가 PostgreSQL 경계를 넘을 때**: Elasticsearch/OpenSearch.
- **“내 취향”이 핵심일 때**: 검색 엔진보다 개인화 추천 문제로 별도 설계.

## 공식 자료

- PostgreSQL, [`pg_trgm`](https://www.postgresql.org/docs/current/pgtrgm.html)
- PostgreSQL, [Full Text Search 함수와 ranking](https://www.postgresql.org/docs/current/functions-textsearch.html)
- PostgreSQL, [Text Search Tables and Indexes](https://www.postgresql.org/docs/current/textsearch-tables.html)
- PostgreSQL, [Text Search Dictionaries](https://www.postgresql.org/docs/current/textsearch-dictionaries.html)
- pgvector, [공식 저장소 README: exact/approximate index, filtering, hybrid search](https://github.com/pgvector/pgvector)
- Elasticsearch, [Search approaches](https://www.elastic.co/docs/solutions/search/search-approaches)
- Elasticsearch, [Hybrid search와 RRF](https://www.elastic.co/docs/solutions/search/hybrid-search)
- Elasticsearch, [Semantic reranking](https://www.elastic.co/docs/solutions/search/ranking/semantic-reranking)
- Elasticsearch, [Korean `nori` analysis plugin](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori)
- Elasticsearch, [Ranking evaluation](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/search-rank-eval)
- OpenSearch, [Hybrid search](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/index/)
