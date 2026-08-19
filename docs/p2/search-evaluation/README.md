# SEARCH-04 검색 품질 평가

이 문서는 [SEARCH-04 명세](../search.md#search-04)의 **검색 품질 평가 계약**을 소유합니다.

사용자 검색 동작·fallback·완료 기준은 `docs/p2/search.md`, 품질 corpus membership·snapshot/version·index rollback 결정은 [ADR-0072](../../adr/game/0072-search-quality-corpus-membership-and-versioning.md)가 소유합니다. 이 디렉터리는 그 결정을 fixture와 validator로 실행 가능하게 고정합니다.

## 현재 상태

- profile: `development-seed`
- 상태: `draft`
- fixture: 15 query + 대표 anchor 3개
- BoardLife mapping: `provisional`

따라서 현재 fixture/validator 성공은 **SEARCH-04 최종 품질 승인이나 `quality-ready`를 의미하지 않습니다.**

## 데이터 경계

- 검색 품질 평가는 ADR-0066의 초기 **BoardLife Top 1,000 품질 corpus**를 사용합니다.
- membership는 고정 ranking snapshot의 유효 행을 BGG ID로 dedupe하고 승인 catalog에 매핑한 뒤, BoardLife rank·BGG ID 순으로 정렬해 target N을 선택합니다.
- raw rank cutoff를 catalog mapping보다 먼저 적용하지 않습니다.
- 5,000·10,000 확장은 별도 재현 가능한 품질 근거가 있을 때 새 corpus version으로 진행합니다.
- 전체 BGG 약 17만 건은 이름 검색·구조화 필터·DB index/cache/pagination/부하 **성능 검증용**이며 SEARCH-04 품질 근거로 혼용하지 않습니다.
- 한국어 description 부재는 평가 착수 blocker가 아닙니다. 출처 없는 번역·설명은 relevance 정답 근거로 사용하지 않습니다.
- #747 게임 설명 데이터 정합성 수정은 Development Seed/검색 PoC의 blocker가 아닙니다.

## 평가 단계

### Development Seed Evaluation

최종 품질 승인 전에 lexical/Sparse/Dense/Hybrid 후보를 빠르게 비교하고 개발 방향과 회귀를 확인합니다.

- query: **12~15개**
- 대표 anchor: **3개 포함**
- 세 cohort 포함
  - `exact/name variant`
  - `intent/description`
  - `intent+hard filter`
- 각 query에 query text, cohort/type, hard filter, expected/excluded game ID, relevance reason, source/version 기록
- 모든 game ID는 Top 1,000 품질 corpus 안에 있어야 함
- 판정 상태는 `provisional/development`
- 동일 fixture로 후보 결과와 지표를 반복 재현할 수 있어야 함
- Seed만으로 `quality-ready` 또는 production 검색 방식 승인 불가

Seed가 고정되면 Final 60+ query가 완료되기 전에도 lexical/Sparse/Dense/Hybrid 후보 PoC를 진행할 수 있습니다.

### Final Quality Evaluation

충분한 query 표본과 독립 판정, baseline 대비 지표로 최종 검색 방식의 품질을 승인합니다.

- 전체 query: **최소 60개**
  - `exact/name variant` ≥ 15
  - `intent/description` ≥ 25
  - `intent+hard filter` ≥ 20
- Seed의 대표 anchor query 유지
- 각 query의 hard filter, expected/excluded ID, relevance reason, source/release version 기록
- 2인 독립 판정
- 판정 불일치 시 제3 판정 보존 및 다수 합의
- 다음 지표를 동일 입력에서 재계산 가능해야 함
  - `Recall@10`
  - `MRR@10`
  - `nDCG@10`
  - `hard_filter_violation_rate`
- `hard_filter_violation_rate = 0`
- cohort별 baseline 대비 승인된 `min_delta_vs_baseline` 충족
- Seed provisional 판정을 독립 판정 없이 Final 정답으로 자동 승격하지 않음
- 승인되지 않은 catalog release / quality corpus / baseline / threshold 상태에서는 quality gate 통과 불가

Final Quality Evaluation 완료 전에는 SEARCH-04 최종 검색 방식, production index/migration/backfill, 최종 Hybrid/RRF 파라미터, `DISCOVERY-01` production 연동을 승인하지 않습니다.

## 파일 계약

- `manifest.json`: profile, catalog release, quality corpus release, checksum, cohort, approval gate
- `quality-corpus.json`: pinned ranking snapshot/hash, mapping·dedupe·정렬·target N 규칙과 fixture 참조 membership projection
- `queries.json`: query, cohort, hard filter, expected/excluded game ID, relevance reason, source/version
- `queriesSha256`·`qualityCorpusSha256`: 원자료 변경 감지용 SHA-256
- `manifest.index`: corpus version/checksum과 `BUILDING → READY` 또는 `FAILED`, 실패 시 이전 `READY` 유지 규칙

Catalog Dataset Release 승인과 Search/Embedding Execution 승인은 분리합니다. dataset release gate 통과만으로 특정 `search_text`, model/provider, embedding/index/output의 실행 승인이 생기지 않습니다.

## 검증

Lexical·Sparse offline baseline의 입력 descriptor·검증·점수 규칙·공통 결과 형식은 [실행 규약](lexical-sparse-baseline.md)을 따른다.

### 구조 검증

```bash
node scripts/p2-search-evaluation.mjs \
  --check \
  --manifest docs/p2/search-evaluation/manifest.json
```

Development Seed에서는 12~15개 query, 세 cohort, pinned version/snapshot, mapping 이후 target N membership, hard-filter 호환성, query/corpus checksum을 검증합니다.

### Final quality gate

```bash
node scripts/p2-search-evaluation.mjs \
  --quality-gate \
  --manifest docs/p2/search-evaluation/manifest.json
```

현재 `development-seed`/`draft` 상태에서는 의도적으로 실패해야 합니다. `final-quality`로 전환하려면 승인된 catalog release와 quality corpus, 60+ query, 독립 판정, baseline과 cohort threshold가 모두 필요합니다.

실행 경로는 `queriesPath`·`qualityCorpusPath`의 원자료를 읽어 manifest checksum과 대조하며 inline 데이터만으로 final gate를 통과시키지 않습니다.

### 품질 지표 재계산

결과의 key는 fixture query ID이며 `rankedGameIds`는 관련도 내림차순 결과, `hardFilterViolationGameIds`는 hard filter 위반 결과 ID입니다.

```json
{
  "Q-001": {
    "rankedGameIds": [123, 456, 789],
    "hardFilterViolationGameIds": []
  }
}
```

```bash
node scripts/p2-search-evaluation.mjs \
  --metrics \
  --manifest docs/p2/search-evaluation/manifest.json \
  --results /path/to/candidate-results.json \
  --baseline /path/to/baseline-results.json
```

실제 catalog release 승인 후에는 검수된 catalog record 배열을 `--catalog /path/to/catalog-index.json`으로 전달해 ID·hard-filter를 다시 대조합니다.

## 제외

이 문서는 다음을 소유하지 않습니다.

- 의미 검색 API·DTO·응답 계약
- 실제 lexical/Sparse/Dense/Hybrid/RRF 검색 구현
- embedding model/provider/vector DB 선택
- Flyway / production index / backfill
- backend/frontend 기능 구현
- 17만 건 전체 한국어 설명 자동 번역
- `DISCOVERY-01` 구현
