# ADR-0086: SEARCH-04 dense semantic serving core와 index delivery를 분리한다

- 상태: 승인됨
- 작성일: 2026-08-20
- 결정일: 2026-08-20
- 관련: [SEARCH-04 검색 명세](../../p2/search.md#search-04), [#897 semantic-30 implementation selection](https://github.com/bamsongi-club/albam-mate/issues/897), [#836 core 승인·T1~T6](https://github.com/bamsongi-club/albam-mate/issues/836#issuecomment-5357413078), [#871 API·화면 연결](https://github.com/bamsongi-club/albam-mate/issues/871), [#942 index delivery](https://github.com/bamsongi-club/albam-mate/issues/942), [ADR-0060](0060-approved-catalog-ai-embedding-scope.md), [ADR-0072](0072-search-quality-corpus-membership-and-versioning.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

SEARCH-04는 자연어 의도로 게임 후보를 찾되, 기존 P1 hard filter·공개 범위·`playedFilter`와 페이지 경계를 정확히 유지해야 한다. #897은 `dense-bge-m3`를 **#836 구현용 후보 생성 방식**으로 선택했지만, 사용한 AI C 판정은 `provisional-ai-adjudication`이며 human qrels·Final Quality Evaluation·production 승인과는 다르다.

기존 offline PoC는 고정한 로컬 `BAAI/bge-m3@5617a9f61b028005a4858fdac845db406aefb181`, 1,024차원 CLS pooling, L2 normalization과 normalized dot product를 사용한다. 반면 현재 Spring production compose는 앱 컨테이너에 `mem_limit: 512m`을 선언하고, PostgreSQL compose는 `postgres:18.4` 이미지와 pgvector extension enablement를 아직 선언하지 않는다. 모델과 vector index를 Spring 프로세스에 함께 넣거나, 새 schema·backfill·activation을 core issue에 섞으면 실패·rollback의 소유가 불명확해진다.

판단 기준은 다음과 같다.

1. 기존 P1 이름 검색과 HTTP 응답을 바꾸지 않고, semantic 후보의 정확한 filter 재검증을 보장한다.
2. 승인 release·필드 allowlist·모델 revision을 벗어난 입력 또는 부분 index를 성공으로 보이지 않는다.
3. 1,000-game 초기 corpus에서 운영 대상과 rollback 경계를 작게 유지한다.
4. 모델·vector 배포 장애가 core와 #871의 API/UI 구현을 막지 않으며, fallback 의미가 명확하다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 전용 Python BGE-M3 service + shared PostgreSQL pgvector | 현재 pinned PoC의 model/provenance를 재사용하고, catalog release·active index version과 같은 PostgreSQL 운영 경계에서 관리한다. | pgvector-enabled image·extension, HNSW build, migration/backfill·rollback 배포가 필요하다. | 선택 |
| 전용 Python BGE-M3 service + 독립 vector service | embedding process와 vector search를 모두 Spring에서 분리한다. | 초기 1,000-game 규모에 새 운영 대상·동기화·rollback 경계가 추가된다. pgvector rollout이 불가능할 때만 재검토한다. | 보류 |
| Spring JVM 안의 model 또는 외부 embedding provider | 단일 프로세스 또는 즉시 호출 경로처럼 보인다. | 512 MiB 앱 컨테이너와 맞지 않고, pinned local BGE-M3 선택·입력 provenance 또는 비용·보존 경계를 바꾼다. | 제외 |

## 결정

### 1. #836은 model을 실행하지 않는 internal serving core를 소유한다

`game.contract`가 `SemanticGameSearchQuery`와 `DenseCandidateSource` port를 소유한다. `game` service는 자연어 query와 구조화된 P1 criteria를 받아 candidate ID·내부 relevance만 소비한다. vector, embedding, score 원문과 model/provider 세부는 public result에 넣지 않는다.

실제 BGE-M3 encoder와 pgvector 조회 adapter는 `infra`와 별도 Python service로 둔다. 업무 모듈은 `infra` 구현을 직접 참조하지 않으며, index가 아직 없는 기본 runtime은 fail-closed unavailable source를 사용한다. 따라서 #836은 BGE-M3 가중치, pgvector schema 또는 model container를 포함하지 않는다.

### 2. 초기 release와 후보·필터·페이지 경계를 고정한다

실제 `READY` index는 executable approval을 받은 1,000-game quality release의 allowlisted `search_text`만 사용한다. concrete release manifest가 없으면 semantic 성공을 만들지 않고 lexical fallback만 허용한다. 약 17만 전체 catalog semantic serving은 별도 제품·품질·비용 gate 없이는 열지 않는다.

candidate source는 초기 corpus 전체의 결정적 `(gameId, relevance)` 순서를 core에 제공한다. core는 중복을 제거하고 기존 `GameListSpecification`으로 공개 범위·P1 hard filter·`playedFilter`를 PostgreSQL에서 다시 적용한 뒤 `relevance DESC, name ASC, id ASC`로 정렬한다. 페이지는 이 filtered ordering 뒤에 자르며 `page * size`만 vector candidate에 요청하지 않는다.

### 3. 자연어 filter parsing과 public API/UI를 core에서 분리한다

`4인`, `60분 이하`, 난이도 같은 P1 조건은 #871이 기존 구조화 filter 값으로 전달한다. #836은 raw query에서 수치·범위를 추출하거나 조건을 추측하지 않고 전달받은 filter를 다시 검증한다. #871은 public Controller·DTO·API 문서와 상태 표시를 소유하고, 기존 `GET /api/games?keyword=...`는 P1 부분일치·인기순·Slice 응답을 유지한다.

### 4. 실패 상태와 actual index delivery를 분리한다

#836 내부 result status는 `SEMANTIC`, `LEXICAL_FALLBACK`, `UNAVAILABLE`이다. 정상 dense 조회가 빈 결과를 주면 빈 `SEMANTIC`이며 조건을 완화하지 않는다. dense index/provider가 실패했지만 기존 lexical 경로가 살아 있으면 같은 hard filter로 `LEXICAL_FALLBACK`을 만들고, 둘 다 불능일 때만 `UNAVAILABLE`을 만든다. query 원문·hash·사용자 ID·세션·토큰·예외 상세는 result, metric label, log에 넣지 않는다.

[#942](https://github.com/bamsongi-club/albam-mate/issues/942)는 Python BGE-M3 service, pgvector-enabled DB image/extension, forward schema/migration·backfill, `BUILDING → READY` activation, 이전 `READY` pointer rollback과 운영 증거를 소유한다. `BUILDING`·`FAILED` version은 조회 성공으로 노출하지 않는다.

## 결과

- 얻는 것:
  - #836은 model·vector 배포 여부와 독립적으로 P1 filter 정확성, 결정적 정렬·페이지, fallback 의미를 자동 검증할 수 있다.
  - #871은 검증된 내부 result contract만 호출하고 public API/UI에 집중할 수 있다.
  - #942는 DB image·extension·data backfill과 model runtime의 배포·rollback 책임을 한 범위로 다룬다.
- 감수할 비용·위험:
  - executable catalog release와 #942가 준비되기 전에는 semantic result가 아니라 degraded fallback만 제공된다.
  - pgvector HNSW build·메모리·recall/filter 특성은 실제 release와 PostgreSQL에서 별도 검증해야 한다.
  - 1,000-game corpus는 초기 운영 범위를 줄이지만 전체 catalog 요구를 해결하지 않는다.
- 후속 작업:
  - #836은 core·PostgreSQL integration test와 #871 handoff contract를 구현한다.
  - #942는 실제 index delivery의 승인 T-ID, deployment/rollback runbook과 release evidence를 별도로 확정한다.

## 보류 및 재검토

- 지금 하지 않는 것:
  - pgvector schema, extension/image, HNSW index, BGE-M3 container, migration/backfill, active pointer와 실제 rollout
  - raw query 안의 P1 숫자·범위 parsing, public HTTP endpoint·DTO·frontend, production quality approval, 17만 전체 corpus serving
- 보류 이유:
  - 각각은 운영·공개 계약 또는 품질 범위를 바꾸며, #836의 internal core 검증과 같은 PR에 넣으면 rollback과 책임 경계가 섞인다.
- 다시 검토할 조건:
  - pgvector extension/image rollout이 지원되지 않거나 1,000-game release에서 지연·recall·filter 비용이 기준을 만족하지 않을 때
  - executable approved release, human qrels 또는 Final Quality gate, corpus 규모·provider/model·보존 정책이 바뀔 때

## 참고 자료

- [BAAI BGE-M3 model card](https://huggingface.co/BAAI/bge-m3)
- [pgvector 공식 문서](https://github.com/pgvector/pgvector)
- [`run-bge-m3.py`](../../../scripts/search-evaluation/run-bge-m3.py)와 [`dense-bge-m3 manifest`](../../p2/search-evaluation/dense-bge-m3/manifest.json)의 고정 model/provenance 규약

## 검증

- 상태: 미검증
- 근거:
  - 계약: [#836 승인 코멘트](https://github.com/bamsongi-club/albam-mate/issues/836#issuecomment-5357413078)가 다섯 결정과 T1~T6을 고정했고, [#942](https://github.com/bamsongi-club/albam-mate/issues/942)가 actual index delivery 범위를 분리했다.
  - 구현: `game.contract`의 `SemanticGameSearch`·`DenseCandidateSource`와 `game.service.SemanticGameSearchService`가 후보 ID를 P1 `GameListSpecification`으로 재검증하고, `infra.search`의 fail-closed adapter가 index 부재를 lexical fallback 경로로 연결한다. provider/index 불능만 lexical fallback으로 전환하며 semantic core 오류는 숨기지 않는다.
  - 테스트: rebase 뒤 `SemanticGameSearchServiceTest`의 T2~T6 exact selector 9개와 `SemanticGameSearchPostgresTest`의 T1·T2·T4 exact selector 4개가 통과해 P1 filter·결정적 페이지·empty/fallback/unavailable 경계를 확인했다.
- 미검증:
  - #836의 최신 remote PR·CI와 #871 API/UI 연결
  - #942의 model/pgvector deployment, release-bound backfill, activation·rollback과 운영 실측

> 상태 값과 번호·대체 규칙은 [README](README.md)를 따른다.
