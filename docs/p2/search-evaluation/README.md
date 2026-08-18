# SEARCH-04 평가 fixture

이 디렉터리는 [SEARCH-04 명세](../search.md#search-04)의 평가 계약을 실행 가능한 형태로 고정합니다.

현재 fixture는 `development-seed` 프로파일의 `draft`입니다. 15개 query와 대표 anchor 3개는 lexical/Sparse/Dense/Hybrid 후보를 비교하는 개발용 입력이며 최종 품질 승인 자료가 아닙니다.

## 데이터 경계

- 검색 품질 평가는 ADR-0066의 초기 **BoardLife Top 1,000 품질 corpus**를 사용합니다.
- 전체 BGG 약 17만 건은 이름 검색·구조화 필터·DB index/cache/pagination/부하 검증용이며, 이 fixture의 품질 근거로 혼용하지 않습니다.
- `quality-corpus.json`은 외부 원천을 복제하지 않고 현재 seed가 참조하는 game ID의 membership·hard-filter projection만 보존합니다.
- BoardLife mapping은 현재 `provisional`이고 중복·검수 필요 행이 남아 있으므로, catalog release나 SEARCH-04 품질 승인을 의미하지 않습니다.

## 파일 계약

- `manifest.json`: profile, catalog release, quality corpus release, checksum, cohort와 승인 gate
- `quality-corpus.json`: Top 1,000 원천의 snapshot/hash와 현재 seed가 참조하는 membership projection
- `queries.json`: query, cohort, hard filter, 기대·제외 game ID, 이유, source/version
- `queriesSha256`·`qualityCorpusSha256`: 원자료 변경 감지용 SHA-256
- anchor 3개: 명세의 대표 질의를 `provisional/development` 상태로 보존

## 검증

구조 검증은 다음 명령으로 실행합니다.

```bash
node scripts/p2-search-evaluation.mjs \
  --check \
  --manifest docs/p2/search-evaluation/manifest.json
```

이 명령은 profile의 12~15개 범위, 세 cohort, Top 1,000 membership, hard-filter 호환성, query·corpus checksum을 확인합니다.

품질 게이트는 현재 의도적으로 실패합니다. `final-quality` profile로 전환하려면 승인된 catalog release와 quality corpus, 60개 이상 query, 독립 판정·불일치 제3 판정, baseline·cohort threshold를 별도로 채워야 합니다.

```bash
node scripts/p2-search-evaluation.mjs \
  --quality-gate \
  --manifest docs/p2/search-evaluation/manifest.json
```

후보·baseline 결과의 `Recall@10`, `MRR@10`, `nDCG@10`과 `hard_filter_violation_rate`는 다음 입력으로 다시 계산합니다. 결과의 key는 fixture query ID이며, `rankedGameIds`는 관련도 내림차순 결과, `hardFilterViolationGameIds`는 hard filter를 위반한 결과 ID입니다.

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

실제 catalog release가 승인된 뒤에는 검수된 catalog record 배열을 `--catalog /path/to/catalog-index.json`으로 추가해 ID·hard-filter를 다시 대조합니다. 현재 fixture의 provisional 상태와 script 실행 성공만으로 품질 승인을 표시하지 않습니다.

이 작업은 검색 API·DTO·ERD·backend·frontend·Flyway·provider/model 변경을 포함하지 않습니다.
