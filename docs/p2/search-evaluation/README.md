# SEARCH-04 평가 fixture

이 디렉터리는 [SEARCH-04 명세](../search.md#search-04)의 평가 계약을 실행 가능한 형태로 고정합니다.

현재 fixture 상태는 `draft`입니다. `queries.json`은 3개 대표 anchor와 총 60개 query를 보존하지만, `expectedGameIds`·`excludedGameIds`는 공개 catalog 메타데이터에서 만든 검수 전 제안입니다. 각 query의 `judgements`는 비어 있고 cohort별 `min_delta_vs_baseline`도 비어 있으므로 품질 합격 자료가 아닙니다.

## 파일 계약

- `manifest.json`: SEARCH-04 schema, catalog release/field version, cohort 최소 표본, 판정자·baseline·threshold 상태
- `queries.json`: query, cohort, hard filter, 기대·제외 game ID, 이유, 출처·버전
- `queriesSha256`: query 원자료 변경 감지용 SHA-256
- anchor 3개: 명세에 정의된 대표 질의, 각 10개 제안 기대 ID와 제외 ID·이유
- cohort 분포: `exact/name variant` 15개, `intent/description` 25개, `intent+hard filter` 26개

## 검증

저비용 구조 검증은 다음 명령으로 실행합니다.

```bash
node scripts/p2-search-evaluation.mjs \
  --check \
  --manifest docs/p2/search-evaluation/manifest.json
```

품질 게이트는 아직 의도적으로 실패합니다. 승인된 구체 catalog release, 독립 판정 2개와 불일치 시 제3 판정, baseline 및 cohort별 승인 threshold를 채운 뒤에만 실행할 수 있습니다.

```bash
node scripts/p2-search-evaluation.mjs \
  --quality-gate \
  --manifest docs/p2/search-evaluation/manifest.json
```

고정 fixture에 대한 후보·baseline 결과의 `Recall@10`, `MRR@10`, `nDCG@10`과
`hard_filter_violation_rate`는 다음 입력 형식으로 다시 계산합니다. 결과의 key는 fixture
query ID이며, `rankedGameIds`는 관련도 내림차순 결과, `hardFilterViolationGameIds`는
hard filter를 위반한 결과 ID입니다.

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

공개 catalog ID와 hard filter 수치를 함께 확인할 때는 검수된 catalog record 배열을
`--catalog /path/to/catalog-index.json`으로 추가합니다. 현재는 구체 release가 등록되지
않았으므로 이 인자를 생략한 구조 검증만 품질 승인으로 해석할 수 없습니다.

BGG 데이터셋 및 AI·embedding 사용 정책 승인은 [승인 범위 문서](../../game-catalog/2026-08-14-bgg-ai-embedding-approval.md)와 [ADR-0060](../../adr/game/0060-approved-catalog-ai-embedding-scope.md)을 따릅니다. 정책 승인을 실제 실행 가능한 release 승인으로 추정하지 않으며, 이 fixture 자체가 embedding 생성이나 운영 검색 구현을 수행하지 않습니다.

T1~T6 검증은 [validator 테스트](../../../scripts/p2-search-evaluation.test.mjs)에서 구조·표본·catalog ID·hard filter·지표·판정·범위 경계를 각각 검사합니다. 이 경계 밖의 API·DTO·ERD·backend·frontend·Flyway·provider/model 변경은 SEARCH-04 fixture 작업에 포함하지 않습니다.
