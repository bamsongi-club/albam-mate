# SEARCH-04 검색 품질 평가

이 문서는 [SEARCH-04 명세](../search.md#search-04)의 **검색 품질 평가 계약**을 소유합니다.

사용자 검색 동작·fallback·완료 기준은 `docs/p2/search.md`, 품질 corpus membership·snapshot/version·index rollback 결정은 [ADR-0072](../../adr/game/0072-search-quality-corpus-membership-and-versioning.md)가 소유합니다. 이 디렉터리는 그 결정을 fixture와 validator로 실행 가능하게 고정합니다.

## 현재 상태

- profile: `development-seed`
- 상태: `draft`
- canonical fixture: 15 query + 대표 anchor 3개
- #885 비교 fixture: 별도 `semantic-30-v1` 30 query
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
- `search-candidate-semantic-30-input.json.judgementPacket`: canonical blind packet 경로·SHA-256 descriptor
- `quality-corpus.json`: pinned ranking snapshot/hash, mapping·dedupe·정렬·target N 규칙과 fixture 참조 membership projection
- `queries.json`: query, cohort, hard filter, expected/excluded game ID, relevance reason, source/version
- `search-candidate-semantic-30-input.json`: #885 `semantic-30-v1` 후보 비교 manifest
- `search-candidate-comparison/semantic-30-queries.json`: 의미기반 30 query와 `semantic-core`·`contrast-hard-semantic`·`hybrid-hard-filter` 분류
- `search-candidate-comparison/semantic-30-human-judgement-packet.json`: 후보명·score·source rank를 숨긴 독립 판정용 packet
- `search-candidate-comparison/semantic-30-judge-a.json`, `semantic-30-judge-b.json`, `semantic-30-judge-c.json`: A/B 독립 판정과 C 판정 packet. 현재 C는 AI worklist를 병합한 provisional adjudication이며 독립 human 판정 반환 후 교체함
- `search-candidate-comparison/semantic-30-judge-c-worklist.json`: A/B 불일치 585건만 담은 C 입력 목록. 현재 파일은 `ai-drafted-not-independent-human` 상태라 provisional adjudication에만 사용함
- [`semantic-30-third-judge-guide.md`](search-candidate-comparison/semantic-30-third-judge-guide.md): 독립 제3 판정자에게 전달할 점수 기준·입력 규칙·완료 확인 문구
- `search-04-search-candidate-qrels`: 두 독립 판정과 불일치 query의 제3 판정을 합의한 qrels 형식. `packetSha256`는 canonical packet descriptor와 일치해야 함
- `search-candidate-comparison/semantic-30-search-candidate-qrels.json`: canonical packet 기준 provisional qrels. 독립 제3 판정 전에는 approved qrels가 아님
- `search-candidate-comparison/semantic-30-metrics.json`: provisional qrels 기준 semantic-30 방식 비교·RRF 참고 결과
- `queriesSha256`·`qualityCorpusSha256`: 원자료 변경 감지용 SHA-256
- `manifest.index`: corpus version/checksum과 `BUILDING → READY` 또는 `FAILED`, 실패 시 이전 `READY` 유지 규칙

Catalog Dataset Release 승인과 Search/Embedding Execution 승인은 분리합니다. dataset release gate 통과만으로 특정 `search_text`, model/provider, embedding/index/output의 실행 승인이 생기지 않습니다.

## 검증

### #868 Dense BGE-M3 offline PoC

구체 실행 범위는 [#868 승인 코멘트](https://github.com/bamsongi-club/albam-mate/issues/868#issuecomment-5341110812)로 고정한다.

- 후보: 로컬 `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181` 1개
- 인코딩: query/document prefix 없음, dense-only, CLS pooling, 1,024차원, L2 normalization, normalized dot/cosine
- 입력: 동일 Development Seed의 Top 1,000 `search_text`·quality corpus·display map과 새 play-intent Q-010~Q-012
- 출력: query별 Top 20, provenance가 있는 candidate pool, model·score를 숨긴 blind judgement export
- provenance: 승인된 입력·모델 artifact manifest checksum, source Git SHA, Python·`sentence-transformers`·PyTorch·NumPy·device를 함께 기록한다.
- 경계: Q-010~Q-012는 사람 판정 전 `unjudged`이며, 이 결과는 quality-ready·finalist·production model 승인으로 해석하지 않는다.
- gold 준비: 후보 설명을 붙인 독립 사람 판정 packet은 다음 명령으로 생성한다. packet의 `grade`는 두 사람이 독립적으로 0·1·2를 채운 뒤 불일치 시 제3 판정으로 합의해야 하며, 빈 packet은 gold qrels가 아니다.
- 현재 packet: [`dense-bge-m3/gold-judgement-packet.json`](dense-bge-m3/gold-judgement-packet.json)은 3개 query·60개 후보의 설명만 포함하며 아직 gold qrels가 아니다.
- #884의 15개 query fixture와 #878의 3개 Dense query fixture는 기존 evidence로 보존하며, 공통 방식 비교에는 사용하지 않는다. #885에서 승인한 `semantic-30-v1`은 별도 동일 fixture로 Lexical·Sparse·Dense를 재실행했다. 현재 semantic-30 결과는 [`semantic-30-metrics.json`](search-candidate-comparison/semantic-30-metrics.json)에 기록했으며, 30개 질의 범위의 임시 선택 방식은 `dense-bge-m3`다.

실행 manifest와 모든 입력·출력 checksum은 [`dense-bge-m3/manifest.json`](dense-bge-m3/manifest.json)에 보존한다. 승인된 로컬 모델 snapshot과 고정된 [`model-artifact-manifest.json`](dense-bge-m3/model-artifact-manifest.json)을 준비한 뒤 다음 명령으로 새 results를 생성한다. 모델 파일과 입력 manifest가 승인 snapshot과 다르면 실행을 거부한다.

```bash
python3 scripts/search-evaluation/run-bge-m3.py \
  --model-path /path/to/local/BAAI-bge-m3-snapshot \
  --model-manifest docs/p2/search-evaluation/dense-bge-m3/model-artifact-manifest.json \
  --model-revision 5617a9f61b028005a4858fdac845db406aefb181 \
  --source-git-head 592de01644e33554dcce5a13bfcb5e9d5bfac882 \
  --quality-corpus docs/p2/search-evaluation/dense-bge-m3/quality-corpus.json \
  --search-text docs/p2/search-evaluation/dense-bge-m3/search-text-top1000.json \
  --display-map docs/p2/search-evaluation/dense-bge-m3/display-map-top1000.json \
  --queries docs/p2/search-evaluation/dense-bge-m3/queries.json \
  --out /tmp/search-04-bge-m3-results.json
```

새 results를 검증된 candidate pool·blind export·gold judgement packet과 manifest checksum으로 조립한 뒤, 조립된 파일을 `--check`한다. `--check`만 실행하면 manifest가 가리키는 기존 산출물만 검사하므로 새 results의 재현성 검증으로 간주하지 않는다.

```bash
node scripts/search-evaluation/dense-bge-m3-execution.mjs \
  --assemble \
  --manifest docs/p2/search-evaluation/dense-bge-m3/manifest.json \
  --results /tmp/search-04-bge-m3-results.json
node scripts/search-evaluation/dense-bge-m3-execution.mjs \
  --check \
  --manifest docs/p2/search-evaluation/dense-bge-m3/manifest.json
node --test scripts/search-evaluation/dense-bge-m3-execution.test.mjs
```

Lexical·Sparse offline baseline의 입력 descriptor·검증·점수 규칙·공통 결과 형식은 [실행 규약](lexical-sparse-baseline.md)을 따른다.

### #885 후보 종합 비교

후보 종합 비교기는 [`scripts/search-evaluation/search-candidate-comparison.mjs`](../../../scripts/search-evaluation/search-candidate-comparison.mjs)다. 비교 전에 각 후보의 query fixture, 결과 파일과 SHA-256을 입력 manifest의 descriptor로 검증한다.

```bash
node scripts/search-evaluation/search-candidate-comparison.mjs \
  --check \
  --manifest /path/to/search-candidate-semantic-30-input.json
```

후보 간 query ID·문구·cohort·`analysisClass`·hard filter가 하나라도 다르면 비교를 중단한다. ID만 같고 query 문구가 다른 결과를 같은 질의로 취급하지 않으며, `expectedGameIds`·provisional ID를 qrels 대신 사용하지 않는다.

사람 판정 packet은 검증된 후보 Top-K union의 공개 catalog evidence만 포함하고 model·score·source rank를 숨긴다. packet의 `evaluation.topK`와 `evaluation.candidatePoolSha256`는 qrels에 그대로 보존해야 하며, metrics 단계는 두 값을 packet과 대조한다.

```bash
node scripts/search-evaluation/search-candidate-comparison.mjs \
  --packet \
  --manifest /path/to/search-candidate-semantic-30-input.json \
  --out /tmp/search-04-candidate-judgement-packet.json
```

생성된 packet을 두 판정자에게 각각 복사해 `grade`(0·1·2)와 `rationale`를 독립적으로 입력합니다. 두 packet은 query·candidate·evidence를 수정하지 않아야 하며, 조립기는 packet 구조와 candidate pool을 다시 대조합니다. 판정이 다른 query가 있으면 `--judge-c`를 추가하지 않는 한 qrels 생성을 거부합니다.

```bash
node scripts/search-evaluation/search-candidate-comparison.mjs \
  --qrels \
  --manifest /path/to/search-candidate-semantic-30-input.json \
  --canonical-packet /path/to/semantic-30-human-judgement-packet.json \
  --judge-a /path/to/semantic-30-judge-a.json \
  --judge-b /path/to/semantic-30-judge-b.json \
  --judge-a-id judge-a \
  --judge-b-id judge-b \
  --out /tmp/approved-search-candidate-qrels.json
```

`--manifest`의 `judgementPacket.path`가 가리키는 동일 파일을 `--canonical-packet`으로 지정해야 하며, 조립기는 manifest descriptor의 SHA-256도 다시 검증합니다. packet을 다시 생성하면 manifest의 descriptor SHA-256도 함께 갱신·검증해야 합니다.

불일치 query가 있으면 제3 판정 packet을 추가합니다. 제3 판정자는 불일치 candidate만 `grade`·`rationale`를 채우고 나머지는 빈 값으로 둡니다. 조립 결과는 canonical packet SHA-256, 각 판정자의 grade·rationale, query별 합의 방식(`independent-agreement` 또는 `third-judge-majority`)을 보존합니다.

현재 semantic-30은 A/B 불일치 585건을 [`semantic-30-judge-c-worklist.json`](search-candidate-comparison/semantic-30-judge-c-worklist.json)으로 관리한다. 현재 worklist는 `ai-drafted-not-independent-human` 상태이며, A/B가 모두 다른 117건을 포함하므로 C 값을 provisional adjudication 값으로 사용한다. 이 경로는 독립 human qrels가 아니며, [`semantic-30-third-judge-guide.md`](search-candidate-comparison/semantic-30-third-judge-guide.md)는 이후 독립 판정자로 교체할 때의 전달 기준이다.

```bash
node scripts/search-evaluation/search-candidate-comparison.mjs \
  --qrels \
  --manifest /path/to/search-candidate-semantic-30-input.json \
  --canonical-packet /path/to/semantic-30-human-judgement-packet.json \
  --judge-a /path/to/semantic-30-judge-a.json \
  --judge-b /path/to/semantic-30-judge-b.json \
  --judge-c /path/to/semantic-30-judge-c.json \
  --judge-a-id judge-a \
  --judge-b-id judge-b \
  --judge-c-id judge-c \
  --out /tmp/approved-search-candidate-qrels.json
```

독립 판정자 2명의 0·1·2 grade와 불일치 시 제3 판정 consensus가 `approved` 된 뒤에만 `--metrics`가 Recall@10·MRR@10·nDCG@10·hard-filter violation을 계산한다. qrels의 `evaluation.topK`·`candidatePoolSha256`가 packet과 다르면 metrics를 거부한다. 지표가 준비되어도 최종 방식은 자동 선택하지 않고 선택·탈락 근거를 별도로 기록한다.

현재처럼 독립 human C 대신 AI C worklist를 임시 사용해야 할 때는 반드시 명시적인 provisional flag를 붙인다. A/B 일치값은 유지하고, A/B 불일치값은 C를 provisional consensus로 사용하며, 3-way 충돌도 허용한다. 결과 상태는 `provisional-ai-adjudication`이고 approved qrels가 아니다.

```bash
node scripts/search-evaluation/search-candidate-comparison.mjs \
  --qrels \
  --provisional-ai-adjudication \
  --manifest /path/to/search-candidate-semantic-30-input.json \
  --canonical-packet /path/to/semantic-30-human-judgement-packet.json \
  --judge-a /path/to/semantic-30-judge-a.json \
  --judge-b /path/to/semantic-30-judge-b.json \
  --judge-c /path/to/semantic-30-judge-c.json \
  --judge-a-id judge-a \
  --judge-b-id judge-b \
  --judge-c-id judge-c-ai-drafted \
  --out /tmp/provisional-ai-adjudication-qrels.json

node scripts/search-evaluation/search-candidate-comparison.mjs \
  --metrics \
  --provisional-ai-adjudication \
  --hybrid-rrf \
  --manifest /path/to/search-candidate-semantic-30-input.json \
  --out /tmp/provisional-ai-adjudication-metrics.json
```

Hybrid/RRF는 필요할 때만 이미 검증된 ranked output에 `--hybrid-rrf`를 붙여 한 번 추가한다. 결합 규칙은 고정 `RRF k=60`, 동일 query의 기존 후보 union, `score DESC·gameId ASC` tie-break이며 새 후보를 생성하거나 결과에 맞춰 파라미터를 튜닝하지 않는다.

```bash
node scripts/search-evaluation/search-candidate-comparison.mjs \
  --metrics \
  --hybrid-rrf \
  --manifest /path/to/search-candidate-semantic-30-input.json \
  --judgements /path/to/approved-search-candidate-qrels.json \
  --out /tmp/search-04-candidate-comparison.json
```

`semantic-30-v1` 실행 manifest는 [`search-candidate-semantic-30-input.json`](search-candidate-semantic-30-input.json)이다. 승인된 fixture SHA-256은 `84522f97b196d12db33b082fc26529218555b9408a973e6b6da3577587387142`이고 evaluation Top-K는 20이다. 결과는 `search-candidate-comparison/semantic-30-lexical-results.json`, `semantic-30-sparse-results.json`, `semantic-30-dense-results.json`에 보존한다. blind packet은 30 query·1,369 candidate row이며 후보명·score·source rank를 숨기고, packet의 candidate pool checksum을 기록한다. `semantic-30-search-candidate-qrels.json`과 `semantic-30-metrics.json`은 현재 AI C worklist 기준 `provisional-ai-adjudication` 참고 결과를 기록한다. 이 기준에서 Dense는 Recall@10 `0.4557`, MRR@10 `0.5764`, nDCG@10 `0.5518`, hard-filter 위반율 `0%`로 provisional 참고 선택이지만, 독립 제3 인간 판정 전에는 최종 방식으로 승인하지 않는다. 독립 human qrels만 사용하려면 provisional flag 없이 표준 qrels·metrics 경로를 다시 실행한다.

이번 C packet의 585개 불일치 점수는 AI worklist로 직접 작성했으며, 그중 A/B/C가 모두 다른 3-way 충돌은 117개다. 따라서 qrels 조립·metrics 계산은 `provisional-ai-adjudication`으로 재현되지만 C 전체가 별도 독립 제3 인간 판정자로 입력된 것은 아니며, 이 결과만으로 Final Quality Evaluation 완료나 production 전환을 승인하지 않는다. 30개 질의 결과의 주요 지표는 Dense `Recall@10 0.4557 / MRR@10 0.5764 / nDCG@10 0.5518`, Hybrid/RRF `0.2199 / 0.2551 / 0.2726`이다. 최종 60+ query 품질 게이트는 별도로 충족해야 한다.

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
