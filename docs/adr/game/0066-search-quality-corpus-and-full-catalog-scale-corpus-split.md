# ADR-0066: 검색 품질 corpus와 대규모 catalog 성능 검증 corpus를 분리

- 상태: 승인됨
- 작성일: 2026-08-18
- 결정일: 2026-08-18
- 관련: [SEARCH-04 이슈 #712](https://github.com/bamsongi-club/albam-mate/issues/712), [SEARCH-04 명세](../../p2/search.md), [ADR-0060: 승인된 카탈로그 release의 AI·embedding 처리 범위를 허용](0060-approved-catalog-ai-embedding-scope.md), [ADR-0058: 외부·내부 원천을 결합한 게임 인기 점수와 기본 정렬](0058-external-ranking-and-popularity-sort.md), [ADR-0050: 17만 게임 메타데이터를 관계로 관리하고 상세 필터를 제공](0050-game-metadata-catalog-and-filters.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

SEARCH-04는 두 가지를 동시에 만족해야 한다.

- 의미 기반 검색 품질을 Sparse·Dense·Hybrid 방식으로 비교·평가한다.
- 기존 이름 검색·구조화 필터는 [ADR-0050](0050-game-metadata-catalog-and-filters.md)이 적재한 BGG 약 17만 건 전체 카탈로그에서 계속 동작해야 한다.

이 둘을 같은 corpus 하나로 처리하면 다음 문제가 생긴다.

- 17만 건 전체에 한국어명·alias·설명 enrichment와 embedding을 만들면 검수·번역 비용이 크게 늘어난다. 그런데도 실제 검색 품질 평가는 소수의 대표 게임에서만 이뤄져 비용 대비 효과가 낮다.
- 반대로 소규모 품질 corpus만으로 DB index 성능, cache, pagination, 부하테스트까지 검증하면, 실제 운영 규모(17만 건)에서 드러나는 성능 특성을 재현하지 못한다.

[ADR-0060](0060-approved-catalog-ai-embedding-scope.md)은 "어떤 catalog dataset release를 AI·embedding에 쓸 수 있는가"를 정한 정책 경계다. 이 ADR은 그 경계 **안에서**, "승인된 release 중 어떤 하위 집합을 검색 품질 corpus로 쓰고 어떤 집합을 대규모 성능 검증 corpus로 쓸지"를 정한다. 이 선택은 이후 Dense·Hybrid 비교, vector indexing, 부하테스트, corpus 확장 전략에 계속 영향을 주므로 지역적인 구현 세부가 아니라 ADR로 남긴다.

판단 기준:

- 검색 품질 평가가 반복 가능하고 검수 가능한 규모인가.
- 기존 이름 검색·필터·성능 계약이 전체 카탈로그 규모에서 계속 검증되는가.
- 품질 corpus 규모를 나중에 늘릴 때 아키텍처를 다시 설계하지 않아도 되는가.
- BoardLife 순위를 게임 **선정 기준**으로 쓰는 것과 BoardLife의 한국어 설명 등 **콘텐츠**를 실제로 적재·가공하는 것을 구분해, 기존 승인 경계를 지키는가.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 17만 건 전체를 단일 corpus로 enrichment·embedding 처리 | corpus가 하나뿐이라 파이프라인이 단순함 | 전체 번역·검수 비용이 크고, 품질 평가 신호가 희석되며, 반복 실험마다 재처리 비용이 큼 | 제외 |
| 소규모 품질 corpus만 만들고 성능 검증은 별도로 계획하지 않음 | 초기 구현이 가장 빠름 | DB index·cache·pagination·부하테스트가 실제 17만 건 규모에서 검증되지 않아 운영 전환 시 회귀 위험이 큼 | 제외 |
| BoardLife 인기 순위 Top 1,000을 품질 corpus로, BGG 약 17만 건 전체를 성능 검증 corpus로 분리하고 품질 corpus는 확장 가능하게 설계 | 품질 평가와 성능 검증의 책임이 분리되어 각각 재현·확장 가능하며, 기존 BoardLife 순위 사용 방식([ADR-0058](0058-external-ranking-and-popularity-sort.md))과 정합적 | 두 corpus의 정의·갱신·평가 기준을 각각 관리해야 하고, corpus 간 결과를 섞어 해석하지 않도록 후속 문서에서 명시해야 함 | **선택** |

## 결정

### 1. corpus를 두 계층으로 나눈다

| corpus | 범위 | 목적 |
| --- | --- | --- |
| **품질 corpus**(Quality/Enriched Corpus) | [ADR-0058](0058-external-ranking-and-popularity-sort.md)이 정의한 BoardLife 국내 순위 기준 상위 1,000개 게임 | 한국어명·alias·설명 등 enrichment, SEARCH-04 evaluation, Sparse·Dense·Hybrid 비교 |
| **전체 카탈로그**(Full Catalog) | [ADR-0050](0050-game-metadata-catalog-and-filters.md)이 적재하는 BGG 약 17만 건 | 기존 이름 검색, 구조화 필터, DB index 성능, cache, pagination, 부하테스트 |

### 2. BoardLife는 "선정 기준"과 "콘텐츠"를 분리해서 쓴다

- 품질 corpus에 어떤 게임을 넣을지는 BoardLife **순위**로만 정한다.
- BoardLife의 한국어 설명 등 실제 **콘텐츠**를 적재·가공·embedding 입력으로 쓰려면 별도 승인이 필요하다. [ADR-0060](0060-approved-catalog-ai-embedding-scope.md)과 동일하게, 필드별 provenance와 사용 권한·출처 승인이 확인된 필드만 반영한다.
- 승인되지 않은 BoardLife 콘텐츠 필드는 이 ADR만으로 자동 허용되지 않는다.

### 3. 품질 corpus는 1,000건에 영구히 묶이지 않는다

품질 corpus는 SEARCH-04의 **초기** 평가 규모다. 필요하면 아래 순서로 단계적으로 늘릴 수 있게 설계한다.

```
1,000 → 5,000 → 10,000 → 170,000
```

이를 위해 선정 기준(순위 컷오프)과 처리 파이프라인(enrichment → embedding → index)을 규모 파라미터로 다루고, 특정 구현에서 1,000을 상수로 고정하지 않는다.

### 4. 평가 결과의 적용 범위를 구분한다

- Sparse·Dense·Hybrid 비교와 검색 품질 평가는 **품질 corpus**를 기준으로 수행한다. 그 결과를 전체 카탈로그 검색 품질의 대표값으로 일반화하지 않는다.
- 전체 카탈로그의 검색 동작은 기존 이름 검색·필터 계약을 그대로 유지한다. 의미 검색을 전체 카탈로그로 확장할 때는 별도 재검토를 거친다.
- DB index 성능, cache, pagination, 부하테스트는 **전체 카탈로그** 규모를 기준으로 검증한다. 품질 corpus 규모의 측정 결과를 전체 카탈로그 성능 계약의 근거로 대신하지 않는다.

## 결과

- 얻는 것:
  - 검색 품질 개선 작업과 대규모 카탈로그 성능 검증 작업이 서로 다른 corpus·기준으로 분리되어 각각 재현 가능하고 독립적으로 검증할 수 있다.
  - 품질 corpus 확장 경로(1,000 → 5,000 → 10,000 → 170,000)가 사전에 정의되어, 이후 규모를 늘릴 때 아키텍처를 다시 설계하지 않아도 된다.
  - BoardLife 순위 사용(selection 기준)과 BoardLife 콘텐츠 사용(실제 필드 적재)이 구분되어 [ADR-0060](0060-approved-catalog-ai-embedding-scope.md)의 승인 경계를 그대로 따른다.
- 감수할 비용·위험:
  - 품질 corpus와 전체 카탈로그를 별도로 정의·갱신해야 하며, 두 corpus의 평가 결과를 혼동해서 인용하지 않도록 후속 문서·리포트에서 명시해야 한다.
  - 품질 corpus 확장 시마다 enrichment·번역·검수 비용이 다시 발생한다.
- 후속 작업:
  - [#712](https://github.com/bamsongi-club/albam-mate/issues/712)의 평가 fixture(Seed/Final)를 이 ADR의 corpus 정의를 참조하도록 갱신한다.
  - BoardLife 한국어 설명 등 콘텐츠 필드의 실제 사용 승인 여부를 확인하고, 승인되면 [ADR-0060](0060-approved-catalog-ai-embedding-scope.md) 방식의 execution manifest·allowlist에 등록한다.
  - 품질 corpus 확장 단계(5,000 → 10,000 → 170,000)로 진행할 때는 이 ADR의 재검토 조건에 따라 별도 검토를 거친다.

## 보류 및 재검토

- 지금 하지 않는 것: 특정 embedding model·provider·vector 저장소 채택, 품질 corpus를 전체 카탈로그로 즉시 확장, BoardLife 콘텐츠 필드의 실제 사용 승인, 전체 카탈로그에 대한 의미 검색 품질 계약
- 보류 이유: 이번 결정은 corpus 분리 전략과 확장 경로의 아키텍처 결정이며, 검색 기술 선택·데이터 사용 권한 승인·전체 카탈로그 의미 검색 품질 계약은 각각 별도 결정·승인이 필요하다.
- 다시 검토할 조건: 품질 corpus를 다음 확장 단계(5,000 이상)로 늘릴 때, BoardLife 콘텐츠 필드의 사용 승인 범위가 바뀔 때, 또는 전체 카탈로그로 의미 검색을 확장하기로 결정할 때

## 참고 자료

- 이 문서의 맥락·대안으로 갈음

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - [#712](https://github.com/bamsongi-club/albam-mate/issues/712) 평가 fixture가 이 ADR의 corpus 정의(품질 corpus 1,000 / 전체 카탈로그 약 17만)를 실제로 참조하는지
    - BoardLife 한국어 설명 등 콘텐츠 필드의 실제 사용 승인과 provenance 등록 여부
    - 품질 corpus enrichment·embedding 파이프라인과 전체 카탈로그 성능 검증 파이프라인이 실제로 분리 실행되는지

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
