# ADR-0072: SEARCH-04 품질 corpus membership·version·rollback 계약

- 상태: 제안됨
- 작성일: 2026-08-18
- 결정일: 미정
- 관련: [SEARCH-04 이슈 #712](https://github.com/bamsongi-club/albam-mate/issues/712), [SEARCH-04 명세](../../p2/search.md), [SEARCH-04 평가 fixture](../../p2/search-evaluation/README.md), [ADR-0066: 검색 품질 데이터와 대규모 catalog 성능 검증 데이터를 분리](0066-search-quality-corpus-and-full-catalog-scale-corpus-split.md), [ADR-0060: 승인된 카탈로그 release의 AI·embedding 처리 범위를 허용](0060-approved-catalog-ai-embedding-scope.md), [ADR-0058: 외부·내부 원천을 결합한 게임 인기 점수와 기본 정렬](0058-external-ranking-and-popularity-sort.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

[ADR-0066](0066-search-quality-corpus-and-full-catalog-scale-corpus-split.md)은 SEARCH-04 품질 평가용 BoardLife Top 1,000과 약 17만 건 전체 catalog의 성능·기능 검증을 분리했다. 다만 실제 평가 fixture에서 어떤 순서로 Top N을 고르고, ranking snapshot·corpus·index version을 어떻게 고정하며, 실패한 새 index를 어떻게 되돌리는지는 별도 실행 계약으로 남아 있다.

이 계약이 없으면 raw ranking cutoff를 mapping보다 먼저 적용하거나, 같은 version에 다른 snapshot을 덮어쓰거나, 한국어 데이터 부족을 이유로 임의로 game을 대체하거나, 17만 건 catalog를 다음 quality cutoff처럼 해석할 수 있다. 이는 평가 결과의 재현성과 [ADR-0060](0060-approved-catalog-ai-embedding-scope.md)의 승인 release 경계를 흐린다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| ranking 원자료의 rank `<= N`을 먼저 자르고 catalog mapping을 수행 | 구현이 단순하다 | mapping 누락·중복으로 실제 membership가 줄어들고, 순위가 낮은 유효 게임을 임의로 잃는다 | 제외 |
| mapping·중복 제거 후 membership를 확정하고, fixture version만 문서로 관리 | 현재 seed를 빠르게 고정할 수 있다 | snapshot/hash와 index cutover가 실행 시점에 달라질 수 있어 재현·rollback 근거가 약하다 | 제외 |
| pinned snapshot에서 유효 행을 검증하고 BGG ID를 dedupe·catalog mapping한 뒤 deterministic하게 target N을 고르며, manifest와 index checksum/version을 함께 검증 | selection·재현·rollback 경계를 실행 가능한 계약으로 고정한다 | 새 snapshot·corpus version마다 enrichment·검수·검증 비용이 발생한다 | **선택** |

## 결정

### 1. 품질 corpus membership를 deterministic하게 선택한다

유효한 BoardLife ranking snapshot의 행을 다음 순서로 처리한다.

1. 승인된 snapshot ID·version·SHA-256과 rank 형식을 검증한다.
2. 같은 BGG ID는 유효한 가장 낮은 BoardLife rank를 남겨 dedupe한다.
3. 승인된 Albam Mate catalog release의 BGG ID와 정확히 mapping하고, mapping되지 않은 행은 membership에서 제외한다.
4. mapping된 unique game만 BoardLife rank 오름차순, 동일 rank에서는 BGG ID 오름차순으로 정렬한다.
5. 그 결과의 앞에서 target N개를 선택한다. 초기 target N은 1,000이며 raw rank cutoff를 mapping보다 먼저 적용하지 않는다.

현재 저장소의 `quality-corpus.json`은 전체 원천을 복제한 것이 아니라 이 규칙을 참조하는 development projection이다. `memberCount`가 target N보다 작거나 mapping이 provisional인 상태를 임의의 데이터로 채우지 않으며, quality-ready 승인으로 표시하지 않는다.

### 2. snapshot·corpus·evaluation version을 함께 고정한다

manifest는 evaluation version, quality corpus version, selection rule version, source snapshot ID·version·SHA-256을 고정한다. 같은 version에 다른 ranking snapshot이나 mapping 결과를 덮어쓰지 않는다. 새 BoardLife ranking snapshot, catalog release, selection rule 또는 projection checksum은 새 corpus/evaluation version으로 취급한다.

BoardLife rank는 membership 선정 기준일 뿐 BoardLife 콘텐츠의 사용 승인이 아니다. 실제 enrichment·embedding 입력은 [ADR-0060](0060-approved-catalog-ai-embedding-scope.md)의 승인 release·field allowlist·provenance를 별도로 충족해야 한다.

### 3. 언어 데이터 부족으로 membership를 대체하지 않는다

한국어명·설명·alias의 부족을 이유로 유효한 mapped game을 제외하거나 다른 game으로 채우지 않는다. 누락·중복·검수 필요 행은 provisional 상태와 review 근거로 남긴다. 콘텐츠 보강과 ranking membership 변경은 서로 다른 승인·version 경계를 따른다.

### 4. quality scale과 full catalog를 분리한다

1,000 → 5,000 → 10,000은 품질 corpus의 단계적 확장 후보이며 자동 확장 경로가 아니다. 다음 단계로 가려면 같은 fixture 계약으로 Sparse/Dense/Hybrid 비교를 재현하고, baseline 개선·long-tail 필요·enrichment/embedding 비용 근거·독립적으로 검증 가능한 새 corpus version을 제출해야 한다.

약 17만 건 full catalog는 이름 검색·구조화 필터·DB index/cache/pagination/부하 검증 범위다. 이를 다음 quality cutoff 또는 품질 평가 결과의 대표 corpus로 취급하지 않으며, full-catalog semantic search는 별도 제품·품질·비용 gate로 재검토한다.

### 5. index version은 fail-closed로 전환·rollback한다

새 manifest/index version은 `BUILDING`으로 생성하고 enrichment·embedding·index 산출물 checksum, fixture 구조·membership 검증, quality gate를 통과한 뒤에만 `READY`로 활성화한다. 검증·배포가 실패하면 새 version은 `FAILED`로 남기고 기존 `READY` active pointer를 유지한다. rollback은 이전 `READY` pointer로 되돌리며 원천 catalog나 사용자 데이터를 파괴적으로 삭제·되돌리지 않는다.

manifest의 index version·corpus version·projection SHA-256은 서로 일치해야 하고, `BUILDING`을 사용자 검색 결과에 노출하지 않는다. 상태 전환과 active pointer의 원자성·운영 보존 기간은 실제 index 구현 ADR에서 별도로 확정한다.

### 6. 정본과 책임을 나눈다

- 이 ADR: membership algorithm, snapshot/version pin, quality scale boundary, index cutover·rollback 정책
- [`docs/p2/search.md`](../../p2/search.md): 사용자 동작·실패·API와 SEARCH-04 완료 기준
- [`docs/p2/search-evaluation/`](../../p2/search-evaluation/README.md): executable manifest·projection·query·validator·checksum
- [ADR-0066](0066-search-quality-corpus-and-full-catalog-scale-corpus-split.md): quality corpus와 full catalog를 분리하는 상위 결정

이 후속 ADR은 ADR-0066의 승인 결정문을 수정하거나 대체하지 않는다. ADR-0066의 기존 `1,000 → 5,000 → 10,000 → 170,000` 표기는 scale 검토의 역사적 범위를 나타내며, 이 문서는 quality corpus 확장과 full catalog semantic search의 별도 gate 경계를 실행 계약으로 구체화한다.

## 결과

- 얻는 것:
  - 같은 snapshot·catalog release·selection rule에서 같은 membership와 checksum을 재현할 수 있다.
  - provisional mapping·언어 데이터 부족·full catalog 성능 검증을 quality approval과 혼동하지 않는다.
  - 실패한 새 index가 기존 `READY` 검색을 덮어쓰지 않으며, active version을 근거와 함께 rollback할 수 있다.
- 감수할 비용·위험:
  - snapshot·mapping·projection이 바뀔 때마다 새 version, checksum, fixture 검증과 review가 필요하다.
  - target N보다 mapped game이 적으면 quality corpus를 임의 보충하지 않아 평가 규모가 일시적으로 작을 수 있다.
- 후속 작업:
  - [#712](https://github.com/bamsongi-club/albam-mate/issues/712)의 실행 fixture와 validator를 이 계약의 version·selection 필드에 연결한다.
  - 승인된 catalog release와 BoardLife mapping 검수가 끝나면 provisional projection을 새 corpus version으로 재생성한다.
  - 실제 embedding/index provider·schema·운영 cutover를 선택할 때 별도 ADR과 quality evidence를 추가한다.

## 보류 및 재검토

- 지금 하지 않는 것: 전체 17만 건을 quality corpus로 자동 확장, 한국어 데이터 보강을 위한 임의 대체, 특정 embedding model/provider/vector DB·persistent schema 채택, 실제 승인 manifest 없는 quality-ready 전환
- 보류 이유: 현재 mapping과 catalog release가 provisional이며, full-catalog semantic search와 물리 index 구현은 이 membership 계약만으로 결정할 수 없다.
- 다시 검토할 조건: 5,000·10,000 단계의 독립 quality evidence, long-tail 개선 근거, 승인된 새 catalog/snapshot release, enrichment·embedding 비용과 운영 p95 측정, 또는 full-catalog semantic search를 제품 범위로 확정할 때

## 참고 자료

- [SEARCH-04 이슈 #712](https://github.com/bamsongi-club/albam-mate/issues/712)
- [SEARCH-04 평가 fixture](../../p2/search-evaluation/README.md)
- [SEARCH-04 명세](../../p2/search.md)
- [ADR-0066: 검색 품질 데이터와 대규모 catalog 성능 검증 데이터를 분리](0066-search-quality-corpus-and-full-catalog-scale-corpus-split.md)
- [ADR-0060: 승인된 카탈로그 release의 AI·embedding 처리 범위를 허용](0060-approved-catalog-ai-embedding-scope.md)

## 검증

- 상태: 미검증
- 근거: 없음
- 미검증:
    - 실제 승인된 catalog release와 BoardLife ranking snapshot이 이 selection contract의 모든 source field를 충족하는지
    - full ranking 원천에서 dedupe·mapping·target N 결과를 독립적으로 재현할 수 있는지
    - 실제 index builder의 `BUILDING → READY/FAILED` 전환, active pointer 원자 cutover와 이전 `READY` 보존

> 상태 값과 번호·대체 규칙은 [루트 README](../README.md)를 따른다.
