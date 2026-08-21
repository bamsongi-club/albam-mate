# ADR-0087: SEARCH-04 dense serving은 Cloudflare managed BGE-M3와 pgvector로 한다

- 상태: 승인됨
- 작성일: 2026-08-21
- 결정일: 2026-08-21
- 관련: [SEARCH-04 검색 명세](../../p2/search.md#search-04), [ADR-0060](0060-approved-catalog-ai-embedding-scope.md), [ADR-0072](0072-search-quality-corpus-membership-and-versioning.md), [ADR-0086](0086-search-04-dense-serving-architecture.md), [#965 evidence 승인](https://github.com/bamsongi-club/albam-mate/issues/965#issuecomment-5364669134), [#942 delivery T1~T5 승인](https://github.com/bamsongi-club/albam-mate/issues/942#issuecomment-5364669345)
- 대체 대상: [ADR-0086](0086-search-04-dense-serving-architecture.md)의 Python BGE-M3 service 결정
- 후속 ADR: 없음

## 맥락

ADR-0086은 pinned local BGE-M3를 별도 Python service로 실행하도록 선택했다. 그러나 Spring production 컨테이너는 512 MiB이고, 1,000-game 초기 corpus만으로 전용 model runtime·weight·health 운영을 추가하면 AWS 상시 비용과 운영 대상이 검색 기능 규모에 비해 커진다.

[#965 승인 범위](https://github.com/bamsongi-club/albam-mate/issues/965#issuecomment-5364669134)에서 기존 Top 1,000 `search_text`(SHA-256 `ec364be3a34268d1bb6d27e3c41e2cdd31852565eec79fa31faaacda17af4ece`)와 `semantic-30-v1` query 30개(SHA-256 `84522f97b196d12db33b082fc26529218555b9408a973e6b6da3577587387142)만 Cloudflare Workers AI `@cf/baai/bge-m3`의 direct REST `text` mode로 검증했다. 17만 catalog, 새 fixture, human qrels와 public API/UI는 범위 밖이다.

판단 기준은 다음과 같다.

1. query와 문서를 같은 provider/model/vector 계약으로 생성하면서 #950 core와 공개 계약을 바꾸지 않는다.
2. 외부 provider나 index의 실패를 기존 `SemanticSearchUnavailableException`과 lexical fallback 경계로 수렴시킨다.
3. `BUILDING → READY` active pointer와 이전 `READY` rollback을 유지한다.
4. 상시 model 서버 없이 1,000-game initial delivery를 운영할 수 있다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| Python BGE-M3 service + pgvector | 기존 local revision·offline PoC를 그대로 재현한다. | 별도 runtime·weight·health와 AWS 상시 비용이 필요하다. | 대체됨 |
| Cloudflare `@cf/baai/bge-m3` direct REST + pgvector | Spring에서 단순 HTTP로 호출하고 model server를 운영하지 않는다. 1,000 corpus와 semantic-30 evidence를 실제로 얻었다. | provider revision·retention region은 공개 문서만으로 완전히 pin할 수 없고, timeout/429/5xx를 fallback으로 처리해야 한다. | 선택 |
| Cloudflare score mode 또는 AI Gateway | 별도 orchestration을 붙일 수 있다. | vector backfill 계약과 다르고 새 운영·비용 경계가 생긴다. | 제외 |

## 결정

### 1. Cloudflare의 vector 공간을 새 index version으로만 사용한다

Spring의 `infra/search` adapter는 `POST /client/v4/accounts/{accountId}/ai/run/@cf/baai/bge-m3`에 `text` embedding request만 보낸다. `query + contexts` score mode, AI Gateway, Python image·weight·runtime은 사용하지 않는다.

응답은 `result.data`의 정확히 1,024개 finite·non-zero 값이어야 하며, client가 명시적으로 L2 normalize한다. Cloudflare 문서 vector와 query는 같은 provider/model, 1,024 dimension, `text` mode와 normalization provenance를 가진 **새** index version에만 저장한다. 기존 local BGE-M3 document vector와 Cloudflare query vector를 섞지 않는다.

외부 요청 timeout은 5초, 내부 retry는 0회로 둔다. timeout, 429/5xx, malformed response, 차원 불일치와 invalid vector는 `SemanticSearchUnavailableException`으로 변환한다. token, Authorization header, query 원문과 provider response 원문은 예외·로그·fixture·index에 남기지 않는다.

### 2. 초기 1,000 row는 pgvector exact cosine으로 조회한다

DB image에는 pgvector extension을 포함하고, PostgreSQL 전용 forward migration으로 `vector(1024)` embedding과 index version/provenance/active pointer를 저장한다. 초기 1,000 row는 HNSW를 추가하지 않고 exact cosine distance (`<=>`) Top-K로 조회한다. 이 규모에서는 separate approximate-index build와 그 recall/maintenance 위험보다 deterministic한 query가 우선이다.

backfill 입력은 deployment가 제공하는 승인 `search_text` release artifact여야 한다. `Game`의 현재 필드에서 임의로 다시 조립하거나 jar에 fixture를 넣지 않으며, release ID·field version·manifest SHA-256·Top 1,000 checksum을 함께 검증한다. 새 index는 `BUILDING`으로 만들고 Top 1,000 checksum, row count 1,000, provider/model/dimension/normalization provenance가 모두 일치할 때만 `READY`로 활성화한다. 하나라도 실패하면 새 version을 `FAILED`로 두고 기존 `READY` active pointer를 유지한다. rollback은 이전 `READY` pointer로 되돌리며 source catalog와 사용자 데이터를 삭제하지 않는다. `BUILDING`·`FAILED`·부분 backfill·provenance 불일치 version은 후보 조회에 노출하지 않는다.

### 3. 기본값은 fail-closed이고 query는 일시 전송만 한다

Cloudflare Account ID와 API token은 환경변수 secret으로만 읽는다. 기본 profile 또는 미구성 환경은 외부 호출 없이 `UnavailableSemanticGameCandidateSource`를 유지한다. runtime에서는 semantic query 원문만 Cloudflare에 일시 전송할 수 있으며, 사용자 ID·세션·ROOM/chat/prompt 및 allowlist 밖 document field는 전송하거나 저장하지 않는다. 집계된 request count·dimension·norm·latency·token/비용·error code만 운영 증거로 보존한다.

## 결과

- 얻는 것:
  - model server 없이 Cloudflare direct REST와 PostgreSQL만으로 SEARCH-04 initial index delivery를 구성한다.
  - #950 `DenseCandidateSource`와 `SemanticGameSearchService`는 그대로 두고 provider/index 장애를 기존 lexical fallback 또는 UNAVAILABLE로 수렴한다.
- 감수할 비용·위험:
  - Cloudflare가 local pinned revision과 동일하다고 보장하지 않으므로 모든 document와 query를 새 version으로 재임베딩해야 한다.
  - provider 지연·quota·장애와 공개 문서로 확인하지 못한 retention region은 운영 전 재확인이 필요하다.
- 후속 작업:
  - #942에서 T1~T5의 Cloudflare adapter, pgvector schema/backfill/activation/rollback과 PostgreSQL 검증을 구현한다.
  - public API/UI와 full-catalog semantic search는 각각의 별도 승인 범위를 기다린다.

## 보류 및 재검토

- 지금 하지 않는 것: 17만 전체 backfill, HNSW/Hybrid/RRF, AI Gateway/cache, 새 quality fixture·human qrels, public API/UI 변경
- 보류 이유: 이 결정은 고정 Top 1,000 initial delivery의 provider/runtime만 대체한다. semantic-30은 `provisional-ai-adjudication`이며 final production quality gate가 아니다.
- 다시 검토할 조건: Cloudflare response contract/model·가격·data policy가 바뀌거나, full-catalog로 확장하거나, provider p95/error rate가 운영 기준을 넘거나, PostgreSQL hard-filter 재적용 뒤 `intent+hard filter` regression이 확인될 때

## 참고 자료

- [Cloudflare BGE-M3 model](https://developers.cloudflare.com/ai/models/%40cf/baai/bge-m3/)
- [Cloudflare Workers AI pricing](https://developers.cloudflare.com/workers-ai/platform/pricing/)
- [Cloudflare Workers AI data usage](https://developers.cloudflare.com/workers-ai/platform/data-usage/)
- [pgvector 공식 문서](https://github.com/pgvector/pgvector)
- [`semantic-30` provisional 평가 요약](../../p2/search-evaluation/search-candidate-comparison/semantic-30-evaluation-report.md)

## 검증

- 상태: 미검증
- 근거:
  - 계약: [#965 승인](https://github.com/bamsongi-club/albam-mate/issues/965#issuecomment-5364669134)과 [#942 T1~T5 승인](https://github.com/bamsongi-club/albam-mate/issues/942#issuecomment-5364669345)이 provider/input/privacy와 구현 테스트 경계를 고정했다.
  - 구현: `CloudflareEmbeddingClient`·`PgVectorDenseCandidateSource`·release/provenance 검증과 PostgreSQL 전용 `V37` migration을 추가했다. 기본·미구성 환경은 기존 `UnavailableSemanticGameCandidateSource`를 유지하며, 외부 요청 timeout은 설정값과 무관하게 5초로 고정한다.
  - 테스트: `CloudflareEmbeddingClientTest`와 전체 H2 coverage/`verifyCoverageRuleTargets`를 통과했다. 실제 pgvector Testcontainers에서 `SchemaValidationPostgresTest` 5개와 `PgVectorSemanticIndexPostgresTest` T3/T4 3개가 extension·migration·exact cosine·partial backfill 차단·rollback을 검증했다. V37 이후 기존 PostgreSQL Testcontainers도 pgvector 호환 PostgreSQL 18/15 이미지로 정렬했다.
  - 측정: Cloudflare smoke는 HTTP 성공·1,024 dimension·finite/non-zero와 pre-normalization L2 norm `0.99995206`을 확인했다. Top 1,000은 1,000 성공·0 실패·invalid 0이었고, semantic-30은 30 성공·0 실패였다. query round-trip은 warm-up 제외 p50 `117.698 ms`, p95 `1300.999 ms`, max `1311.392 ms`였다.
  - 측정: local baseline과 Cloudflare의 Top-10 overlap은 `semantic-core` 20개가 최소 9/10·평균 9.90/10, `contrast-hard-semantic` 5개가 최소 9/10·평균 9.80/10이었다. `intent+hard filter` 5개는 4~6/10이므로 pgvector candidate 뒤 기존 hard filter를 실제로 재검증하기 전에는 품질 채택 근거로 쓰지 않는다. human qrels가 없으므로 Cloudflare Recall@10·MRR@10·nDCG@10은 `unavailable`이다.
  - 측정: 실제 Cloudflare usage 기준 1,000 document backfill은 약 `$0.0107`, query 평균은 약 `$0.000000264`, 월 10만 query는 약 `$0.0264`였다. Workers AI free daily quota/plan·가격은 배포 시점에 다시 확인한다.
- 미검증:
  - 실행 가능한 승인 release artifact를 이용한 실제 Top 1,000 backfill·activation과 rollback 배포 절차
  - 실제 production deployment와 provider data retention/region 계약 확인

> 상태 값과 번호·대체 규칙은 [README](README.md)를 따른다.
