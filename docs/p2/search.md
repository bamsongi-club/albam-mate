# P2 검색 고도화 기능 명세

> **문서 상태: draft**
>
> 담당자: `@hanyejin` · 기능 ID: `SEARCH-04` · 상태 정본: [P2 기능 상태](README.md#기능별-현재-상태)

## 문서 책임

- 이 문서가 소유하는 기능 동작과 완료 기준: 사용자가 게임을 이름이 아니라 플레이 의도와 자연어 조건으로 찾는 의미 기반 검색의 사용자 흐름, 검색 결과 품질, P1 조건 필터와의 결합, 실패·fallback·복구 경계와 `SEARCH-04` 완료 기준.
- 이 문서가 소유하지 않는 API·ERD·아키텍처·기술 결정: HTTP 경로·요청/응답 타입, 테이블·컬럼·제약, 모듈 의존과 트랜잭션 구조, embedding 모델·vector 저장소·검색 엔진 선택. 각 내용은 해당 정본과 승인 ADR이 소유한다.
- 연결할 P1 종료 계약과 P2 공통 규칙: [P1 검색 종료 명세](../archive/p1/search.md)의 `SEARCH-01`~`SEARCH-03`, [P1 검색 성능·인덱스 가이드](../guides/P1_SEARCH_PERFORMANCE.md)의 측정 경계, [P1 기능 종료 상태](../archive/p1/README.md#기능별-종료-상태), 현재 [P2 공통 명세](../P2-spec.md)와 [P2 기능 상태](README.md#기능별-현재-상태).

현재 `GET /api/games?keyword=...`의 이름 부분일치 의미와 RANK-02의 `popularity_score DESC, name ASC, id ASC` 기본 정렬은 P1·3차 MVP 계약으로 유지한다. `SEARCH-04`는 기존 요청을 조용히 의미 검색으로 바꾸지 않고 별도 검색 계약으로 추가한다. 이 문서의 기능 ID·경로·현재 상태는 [P2 기능 상태](README.md#기능별-현재-상태)와 [P2 공통 명세](../P2-spec.md)에 함께 등록한다. 문서 작성은 계약 준비·구현·검증 완료를 뜻하지 않는다.

BGG 기반 검색 입력은 정책 승인된 [데이터셋의 AI·embedding 사용 범위](../game-catalog/2026-08-14-bgg-ai-embedding-approval.md)와 [ADR-0060](../adr/game/0060-approved-catalog-ai-embedding-scope.md)의 정확한 catalog release·필드·가공 allowlist를 따른다. `validateApprovedReleaseManifest`가 연결된 runner에서 실제 `releaseId`·`datasetId`·입출력 checksum·행 수·`approval.references`가 채워진 구체 manifest를 검증하기 전까지 데이터 이용 근거 선행 조건은 미충족이다. 따라서 현재 SEARCH-04 구현·평가에서 BGG 기반 AI·embedding 입력을 실행 승인으로 간주하지 않으며, API·ERD·아키텍처·모델 선택과 품질 검증도 완료된 것이 아니다.

## SEARCH-04

자연어 의도와 P1 hard filter를 결합하는 게임 의미 기반 검색 기능의 상세 명세입니다. 기능 동작과 완료 기준은 이 문서가 소유하며, 현재 상태는 [P2 기능 상태](README.md#기능별-현재-상태)에서 확인합니다.

## 구현 컨텍스트

### 해결할 사용자 문제

사용자는 “가족과 짧게 할 협력 게임”, “초보자도 할 수 있고 인원이 많은 게임”처럼 플레이 의도로 게임을 찾지만, P1의 이름 부분일치와 구조화 필터만으로는 어떤 게임이 그 의도에 맞는지 알기 어렵다. 이 기능은 승인된 게임 카탈로그의 설명·메타데이터를 이용해 의도와 가까운 게임을 먼저 보여주고, 사용자가 기존 조건 필터로 결과를 좁혀 실제 선택까지 이어지게 한다.

검색 기술을 도입하는 것 자체가 목적이 아니다. 성공 기준은 관련 게임을 더 잘 찾게 되는지, 기존 조건과 권한을 지키는지, 품질과 실패를 재현할 수 있는지다.

### 핵심 사용자 흐름

~~~text
공개 게임 탐색 화면에서 사용자가 게임을 찾을 수 있는 상태
→ 사용자가 플레이 의도 문장과 필요한 P1 조건 필터를 입력
→ 서버가 query·필터·인증 상태를 검증하고 승인된 검색 문서에서 후보를 조회
→ P1 hard filter를 결과에 다시 적용하고 관련도 순으로 중복 없이 정렬·페이지네이션
→ 사용자가 기존 게임 카드 정보와 관련도 순서, 필요 시 fallback 상태를 확인
~~~

업무 거절은 빈 query·길이 초과·허용하지 않는 필터·로그인 없이 `playedFilter`를 전달한 경우로 구분한다. 결과가 없으면 조건을 자동 완화하지 않고 빈 결과를 반환한다. 검색 인덱스·외부 검색 의존성이 timeout 또는 장애를 일으키면 제한된 시간 안에 lexical fallback을 시도하며, fallback도 사용할 수 없을 때만 기술 실패를 반환한다. 재시도는 읽기 요청에 한해 전체 timeout 예산 안에서 제한하고, 인덱스 재생성은 이전 `READY` 버전을 유지한 채 별도로 복구한다.

### 초안 인터페이스 경계

- P2는 별도 의미 검색 read contract를 추가한다. 예시 경로는 `GET /api/games/semantic-search`이며, 실제 경로·DTO·오류 코드는 [API](../API.md)와 필요한 ADR에서 확정하기 전까지 현재 제공 API로 간주하지 않는다.
- 의미 검색 contract는 `query`, `page`, `size`와 P1에서 이미 확정한 hard filter만 받는다. 기존 `GET /api/games`의 `keyword` 동작과 응답 호환성을 변경하지 않는다.
- 결과 카드의 기본 필드는 기존 `GameListItem`을 재사용할 수 있지만, 관련도 점수·embedding·내부 검색어를 사용자 응답에 노출하지 않는다. fallback 여부를 표시해야 한다면 별도 명시적 상태 필드로 계약하고 임의의 점수로 대신하지 않는다.

### 자연어 조건 해석 규칙

- `4인`, `3인 이상`, `30분 이하`, 연령·난이도처럼 수치와 범위가 분명한 표현은 P1 hard filter로 변환하고, 후보 생성 뒤에도 같은 조건을 다시 검증한다.
- `트릭테이킹`, `일꾼 놓기`, `협력`처럼 사용자가 메커니즘·카테고리·테마를 명시하면 검수된 관계와 Sparse 신호를 우선한다. Dense 유사도만으로 명시 조건을 대체하지 않는다.
- `가볍게 웃으면서`, `초보자와 즐기기 좋은`, `서로 눈치 보는`처럼 플레이 경험을 표현하면 semantic 후보·순위 신호로 사용한다. 이 경우에도 공개 데이터와 평가 fixture에 근거하지 않은 경험을 사실처럼 만들지 않는다.
- 조건이 서로 충돌하거나 “재미있는 게임”처럼 제품 기준이 없는 표현만 남으면 임의의 기본값으로 검색하지 않고 [DISCOVERY-01 게임 탐색 도우미](game-discovery-assistant.md#discovery-01)가 clarification을 요청한다.

## 범위

### 포함 범위

- 게임명만이 아니라 승인 manifest의 `approvedFields`에 포함된 게임 설명·별칭·카테고리·테마·메커니즘과 정규화된 인원·시간·복잡도·최소 연령을 이용한 의도 검색 후보 생성. 아래 필드명은 후보 목록이며 manifest allowlist를 대신하지 않는다.
- lexical·semantic·hybrid 후보 생성 방식의 평가와 선택. 특정 모델·검색 엔진·vector DB는 이 문서에서 확정하지 않는다.
- 후보 생성 뒤 `SEARCH-01`~`SEARCH-03`의 hard filter, 공개 게임 범위, `playedFilter` 권한과 페이지 경계를 적용하는 규칙.
- 의미 검색 결과의 결정적 관련도 정렬, 동일 결과 중복 제거, 빈 결과와 fallback 상태 표시.
- 기존 필터·Sparse·Dense·Hybrid 후보를 같은 질의와 fixture로 비교하는 단계별 평가 게이트.
- 평가 fixture·기대 결과·판정자·산식·최소 표본을 고정하고 P1 이름 검색 baseline과 비교하는 품질 검증.
- 인덱스 버전·생성·활성화·rollback과 query latency·fallback·zero-result·hard-filter 위반 관측.

### 제외 범위와 재검토 조건

- 이번 단계에서 하지 않는 것:
  - 현재 `GET /api/games?keyword=...`의 검색 의미·최소 검색어 길이·기본 정렬 변경
  - 사용자 검색 이력·ROOM 제목·참가자 정보·채팅·프롬프트 원문을 검색 문서에 포함
  - 승인 manifest의 release·필드·가공 allowlist 밖 BGG raw XML이나 원문을 embedding·LLM 입력으로 사용
  - 개인화 랭킹, 인기 검색어, 외부 점수 결합, 자동 리뷰·추천 문구 생성
  - 검색 결과에 설명 근거나 AI 확률을 사실처럼 표시
  - 검색 품질을 확인하기 전 production migration·모델 교체·무제한 query retry
- 제외 이유: 승인된 release·필드·가공 범위를 넘기거나, P1 공개·개인정보 경계를 바꾸고, 품질 근거가 없는 기술 선택을 제품 동작으로 굳히기 때문이다.
- 다시 검토할 관측 근거·조건:
  - 평가 fixture에서 의미 질의의 관련도 개선이 P1 lexical baseline보다 재현성 있게 확인될 때
  - catalog release·필드·가공 범위·model/provider·보존 정책이 바뀌어 새 승인 manifest가 필요할 때
  - zero-result, fallback, hard-filter 위반과 p95 비용을 고정 release에서 측정할 수 있을 때
  - 사용자 검색 이력·개인화·외부 데이터 결합은 별도 제품 범위와 개인정보·ADR 검토가 승인될 때

### 대표 평가 질의

아래 세 질의는 의미 검색과 게임 탐색 도우미의 공통 anchor fixture다. 기대 게임은 문서에서 임의로 만들지 않고, 구현 전에 팀이 공개 catalog의 game ID 10~30개와 관련성 이유·허용되지 않는 결과를 직접 라벨링한다.

| 질의 | 반드시 지킬 조건 | 의미적으로 평가할 부분 | 기대 결과 고정 방법 |
| --- | --- | --- | --- |
| 트릭테이킹 방식의 협력 게임 중 3인 이상 플레이 가능한 게임 | 3인 이상, 명시된 트릭테이킹·협력 | 메커니즘·협력 관계 | 팀 검수 game ID 10~30개와 관련성 이유를 manifest에 기록 |
| 4명이 모두 초보여도 쉽게 즐길 수 있는 재미있는 파티 게임 | 4인 가능, 제품이 승인한 쉬움의 해석 | 초보자 친화성·파티 분위기 | 팀 검수 game ID 10~30개와 허용되지 않는 결과를 manifest에 기록 |
| 일꾼 놓기 게임 중 4인 플레이가 가능하고 플레이타임이 30분 이하인 게임 | 4인 가능, 최대 플레이타임 30분 이하 | 일꾼 놓기 메커니즘 | 팀 검수 game ID 10~30개와 관련성 이유를 manifest에 기록 |

대표 질의는 `exact/name variant`, `intent/description`, `intent+hard filter` cohort에 포함하고, 기준선은 기존 필터·키워드 검색, Sparse, Dense, Hybrid를 같은 release·fixture에서 비교한다.

### 단계별 고도화 게이트

1. 대표 질의와 기대 결과·필수 조건을 먼저 고정하고, 성공을 hard-filter 정확도·검색 품질·지연·비용으로 나눈다.
2. `Game`의 출처가 확인된 필드로 deterministic한 `search_text`를 만들고 누락·중복·변경 감지 기준을 확인한다. 이 단계에서는 운영 migration이나 전체 backfill을 하지 않는다.
3. 기존 구조화·이름 검색을 baseline으로 저장한 뒤 Sparse/FTS와 `pg_trgm`을 비교한다. `pg_trgm` 결과를 의미 검색 품질로 표현하지 않는다.
4. 기준선 개선이 확인될 때만 Dense offline PoC에서 모델·차원·비용·지연·재생성 부담을 비교한다. Word2Vec·SBERT·BGE-M3·pgvector는 평가 전 채택하지 않는다.
5. Dense가 채택되면 별도 semantic mode/endpoint로 최소 구현하고, 명시 조건은 hard filter와 Sparse로 계속 보호한다. 기존 P1 목록 API의 정렬 의미는 바꾸지 않는다.
6. Dense·Sparse 후보를 결합할 때 RRF는 순위 결합으로만 사용하며 hard filter를 대체하지 않는다. reranker는 상위 후보의 품질 개선 근거가 있을 때만 후속 검토한다.
7. 운영 반영·API·ADR·Issue 갱신은 대표 질의와 확장 fixture에서 baseline 대비 개선이 재현된 뒤 진행한다. 그 다음 단계에서만 대화형 게임 탐색 도우미를 연결한다.

## 기능 규칙

### 데이터

- 원천의 소유자는 `game` 카탈로그다. 검색 인덱스는 `GAMES`와 승인된 메타데이터의 재생성 가능한 read projection이며 업무 정본이 아니다.
- 초기 후보 필드는 현재 공개 계약에 존재하는 `name`, `englishName`, 승인된 `alias`·`description`·`detailDescription`, category·theme·mechanism, 정규화된 player/time/complexity/minAge다. 실제 검색 문서에는 승인 manifest의 `approvedFields`만 포함하고, 필드별 source, 공개 상태, 가공 허용 범위와 release 버전을 fixture manifest에 기록한다.
- 검색 결과의 `id`, `bggId`, 이름·표시 메타데이터와 `playedByMe` 의미는 [API](../API.md)와 [ERD](../ERD.md)의 기존 계약을 따른다. 검색 인덱스에만 존재하는 제목·설명·점수로 공개 응답을 새로 만들지 않는다.
- 사용자 query는 요청 처리에만 사용하고 기본적으로 원문을 저장하지 않는다. 검색 문서는 `gameId`, 승인 필드의 정규화 값, source field version, release ID, index version과 필요한 embedding provenance만 가진다. query 원문·embedding 입력 원문·사용자 ID·세션·이메일·ROOM/채팅 내용은 검색 인덱스에 넣지 않는다.
- catalog release가 바뀌면 새 index version을 별도로 만들고 검증 후 원자적으로 활성화한다. 이전 index는 rollback과 장애 분석에 필요한 기간만 보존하며, 원천에서 제거되거나 공개가 철회된 게임은 다음 활성화 전에 검색 대상에서 제외한다.
- 메트릭 label에는 `queryLengthBucket`, `searchMode`, `filterPresent`, `indexVersion`, `resultBucket`, `errorCode`처럼 제한된 분류값만 사용한다. query 원문·해시, 설명 원문, 개인정보·비밀값·토큰을 label이나 중앙 로그에 넣지 않는다.

### 인증·권한

- 게임 의미 검색과 기존 게임 탐색은 공개 조회다. 유효한 세션이 있어도 다른 사용자의 `playedByMe`나 개인 데이터를 검색 결과에 섞지 않는다.
- `playedFilter=PLAYED_ONLY|EXCLUDE_PLAYED`를 사용하면 현재 세션의 사용자만 대상으로 하고, 비로그인은 P1과 같은 `401 UNAUTHENTICATED`를 반환한다. GET 조회 자체는 CSRF 토큰을 요구하지 않는다.
- 요청 query·필터는 서버에서 allowlist와 길이·형식 검증을 거친다. 검색어를 SQL·검색 DSL·Tool Calling 명령으로 그대로 해석하지 않으며 모든 저장소 호출은 사용자 권한을 전달한다.
- 향후 챗봇이나 내부 Tool Calling이 의미 검색을 호출해도 별도 관리자 경로나 서비스 계정으로 권한을 상승시키지 않는다. 호출자는 사용자 인증 주체와 `playedFilter` 권한을 그대로 전달하고 동일한 오류 계약을 사용한다.

### 상태·동시성·일관성

- 카탈로그의 현재 공개 상태와 게임 메타데이터는 `game`이 소유한다. 검색 projection·생성 작업의 상태는 검색 기능이 소유하되, source catalog를 수정하지 않는다.
- index 상태는 최소 `BUILDING → READY`, `BUILDING → FAILED`, `READY → RETIRED` 전이를 가진다. `READY`만 조회에 사용하며 `BUILDING`을 부분 결과로 노출하지 않는다.
- 하나의 source release에 대해 활성화할 index version은 하나다. 늦게 끝난 이전 release의 build가 현재 release를 덮어쓰지 못하게 source release와 expected version을 원자적으로 확인한다.
- 검색 요청은 상태를 변경하지 않으므로 같은 query의 재시도는 결과 저장·중복 업무를 만들지 않는다. index rebuild·cutover는 별도의 idempotency key 또는 source release/version으로 중복 활성화를 막는다.
- 최종 결과는 후보 조회 뒤 P1 hard filter를 다시 적용한 집합에서 만든다. 필터 종류 사이는 AND, 동일 필터의 선택지는 P1의 OR/ANY/ALL 의미를 유지한다. `playedFilter`·공개 범위·현재 catalog 유효성 위반 결과는 관련도가 높아도 반환하지 않는다.
- 의미 검색 정렬은 `relevance DESC`를 우선하고 동률은 `name ASC, id ASC`로 결정한다. 기존 P1 endpoint의 `popularity_score DESC, name ASC, id ASC` 기본 정렬은 의미 검색 endpoint의 관련도 정렬로 대체하지 않는다. 필터 적용과 전체 건수·페이지 경계 계산은 페이지를 자른 뒤 다시 거르지 않는다.

### 실패·복구

- 업무 거절: 빈 query, 허용 길이 초과, 잘못된 page/size·P1 filter·검색 mode는 `400 VALIDATION_ERROR`로 거절한다. 로그인 없이 `playedFilter`를 사용하면 `401 UNAUTHENTICATED`다.
- 정상적인 no-result: 모든 hard filter와 검색 후보를 적용한 뒤 `200 OK`의 빈 페이지를 반환한다. 결과가 없다는 이유로 인원·시간·연령·테마 필터를 자동 완화하지 않는다.
- semantic/hybrid index timeout·provider 오류: 요청 전체 timeout 안에서 제한된 단일 내부 재시도 후 승인된 lexical fallback을 사용한다. 응답에는 fallback 상태를 명시하고 `fallback_count`와 원인 코드를 관측한다.
- semantic `READY` index가 없는 것만으로는 실패로 판정하지 않는다. 승인된 lexical fallback source가 있으면 P1 hard filter를 적용한 `200 OK` 결과와 명시적 fallback 상태(`SEMANTIC_INDEX_UNAVAILABLE`)를 반환한다. semantic index와 lexical fallback을 모두 사용할 수 없을 때만 `503 SEARCH_UNAVAILABLE`을 반환하며, 부분 후보를 성공 결과로 포장하지 않고 기존 P1 이름 검색 endpoint에는 영향을 주지 않는다.
- build 실패·배포 중단: 이전 `READY` index를 계속 서빙하고 새 버전은 활성화하지 않는다. 이전 버전이 없더라도 승인된 lexical fallback source가 있으면 semantic 기능을 명시적 fallback 상태의 `200 OK`로 제공한다. semantic index와 lexical fallback이 모두 없으면 semantic 요청만 `503 SEARCH_UNAVAILABLE`로 반환하고 catalog 원본과 P1 검색은 계속 제공한다.
- 복구는 원인 코드와 source release를 고정해 재생성하고, fixture·index 검증을 다시 통과한 뒤 atomic cutover한다. rollback은 이전 `READY` pointer로 되돌리며 사용자 데이터나 게임 원천을 보상 삭제하지 않는다.

## 정본 변경 지도

| 정본 | 변경 필요 여부 | 반영할 내용·링크 |
| --- | --- | --- |
| [API](../API.md) | 필요 | 별도 의미 검색 요청·응답·fallback 상태·`SEARCH_UNAVAILABLE` 오류·인증/필터 계약을 등록한다. 기존 [GAME-01](../API.md#game-01-게임-목록검색)의 `keyword` 의미와 응답 호환성은 유지한다. |
| [ERD](../ERD.md) | 조건부 필요 | 영속 index metadata·source release·version·상태·활성 pointer가 필요하다고 결정될 때만 테이블·제약·보존을 반영한다. vector/검색 projection은 승인 release·필드 allowlist와 rollback/삭제 경계를 연결해 반영한다. |
| [아키텍처](../ARCHITECTURE.md) | 필요 | `game` 내부 의미 검색 read service와 projection/index build port의 책임, 외부 검색 adapter 의존 방향, query·catalog·fallback 흐름을 반영한다. 현재 `game` 모듈의 게임 목록·검색 책임은 유지한다. |
| [ADR](../adr/README.md) | 필요 | [ADR-0060](../adr/game/0060-approved-catalog-ai-embedding-scope.md)의 승인 release·필드·가공 범위를 전제로 lexical/semantic/hybrid 대안, hard filter 적용 경계, index version/cutover, fallback·품질 합격 기준과 물리 선택을 별도 ADR로 승인한다. |
| 운영 가이드 | 필요 | index build·검증·활성화·rollback, 고정 fixture와 release SHA, 장애 시 fallback/disabled 판단, query 원문 금지와 지표 확인 절차를 추가한다. 기존 [P1 검색 성능 가이드](../guides/P1_SEARCH_PERFORMANCE.md)는 P1 이름 부분일치와 `pg_trgm` 측정 경계로 유지하며 P2 의미 품질 계약으로 재해석하지 않는다. |

## 완료 기준

### SEARCH-04 완료 기준

- `SEARCH-04-AC1`: 자연어 의도 query와 지원하는 P1 hard filter를 입력하면 공개 catalog에 속한 게임만 반환하고, hard filter를 모두 만족한 결과를 `relevance DESC, name ASC, id ASC` 순으로 페이지네이션한다. 판정은 고정 fixture의 HTTP 응답·필터 재계산·정렬 검증으로 한다.
- `SEARCH-04-AC2`: 기존 `GET /api/games?keyword=...`가 P1의 부분일치·공개 범위·RANK-02 `popularity_score DESC, name ASC, id ASC`·페이지 메타데이터를 그대로 반환하고, 의미 검색 도입만으로 기존 응답이 의미 검색 결과로 바뀌지 않는다. 판정은 기존 P1 회귀 테스트와 before/after 계약 비교로 한다.
- `SEARCH-04-AC3`: 빈 query·길이·필터·페이지 검증 오류와 비로그인 `playedFilter` 요청이 확정한 `400`·`401` 오류로 거절되고, 잘못된 요청이 index 조회나 사용자 데이터 조회를 실행하지 않는다. 판정은 HTTP 계약 테스트와 보안 로그 검증으로 한다.
- `SEARCH-04-AC4`: 의미 검색 결과의 hard-filter 위반률이 0이고, 동일 query·동일 index version의 반복 요청이 동일한 결과 순서와 페이지 경계를 반환한다. 판정은 최소 60개 평가 query와 동시 반복 요청 결과 비교로 한다.
- `SEARCH-04-AC5`: 의미 검색 평가 fixture가 구현 전에 대표 평가 질의 3개를 포함한 query, 필수 조건, 기대 게임 ID 10~30개, 제외 게임 ID, 기대 이유, 출처·버전을 고정하고, 최소 60개 query를 `exact/name variant` 15개 이상, `intent/description` 25개 이상, `intent+hard filter` 20개 이상으로 분포시킨다. 각 cohort와 전체 집합에서 2명의 독립 판정자·Recall@10·MRR@10·nDCG@10 산식을 재현하며, fixture manifest에 cohort별 각 지표의 `min_delta_vs_baseline`과 `hard_filter_violation_rate=0`을 기록한다. `exact/name variant`는 baseline 비회귀, 의미 cohort는 담당자·리뷰어가 승인한 baseline 대비 최소 개선값을 각각 통과해야 하며, 값이 없거나 승인되지 않은 cohort는 품질 합격으로 판정하지 않는다.
- `SEARCH-04-AC6`: no-result는 조건을 완화하지 않은 빈 `200` 결과이고, index/provider timeout과 semantic index 부재는 승인된 lexical fallback이 있으면 명시적 fallback 상태의 `200 OK`, fallback도 없으면 `503 SEARCH_UNAVAILABLE`로 수렴한다. 판정은 no-result·timeout·index 없음·fallback 불가 장애 주입 테스트와 사용자 표시 상태 확인으로 한다.
- `SEARCH-04-AC7`: source release가 바뀌거나 index build가 실패해도 `BUILDING`·실패 버전이 사용자에게 노출되지 않고, 이전 `READY` 버전 유지 또는 승인된 fallback으로 처리되며 rollback 후 결과가 이전 버전으로 복구된다. 판정은 두 release의 순서 역전·중복 build·cutover 중단 PostgreSQL/통합 검증으로 한다.
- `SEARCH-04-AC8`: 검색 query 원문, 설명 원문, 사용자 ID·이메일·세션·토큰이 metric label과 중앙 로그에 없고, index·query 데이터의 보존·삭제가 승인된 source release 경계를 따른다. 판정은 구조화 로그·metric payload·보존/삭제 점검으로 한다.
- `SEARCH-04-AC9`: 고정 release·image digest·catalog fixture·index version에서 성공·거절·no-result·fallback·복구 canary를 재현하고, 지연·오류·fallback·zero-result·index freshness·품질·비용 증거를 지정 위치에 보존한다. 판정은 배포 manifest와 원자료 hash가 일치하는지 확인한다.

## 검증 계획

| 증거 | fixture·환경 | 판정 기준 |
| --- | --- | --- |
| 단위·통합 테스트 | query 정규화, 허용 필드, hard filter 재적용, deterministic ranking, no-result, fallback DTO. Spring/H2 또는 mock은 규칙 조립에 사용 | P1 필터 의미를 바꾸지 않고 경계·중복·페이지 계산이 통과한다. |
| PostgreSQL·외부 의존성 검증 | 현재 지원 PostgreSQL, 승인된 catalog release, 최소 50 query fixture, `ANALYZE`와 index version 고정. 외부 provider를 쓰면 provider/model·timeout·응답 fixture를 pin | 실제 저장 제약·검색 계획·index cutover·provider timeout·재시도가 재현되고, H2만으로 완료 판정하지 않는다. |
| 프론트엔드·계약 검증 | 의미 query 입력, 결과 관련도 순서, P1 필터 조합, 빈 결과, fallback/서비스 오류와 기존 게임 카드 회귀 | 사용자는 성공 결과와 degraded/fallback 상태를 구분해 확인하고, 실패 시 이전 결과를 새 결과로 오인하지 않는다. |
| 품질 평가 | `docs/p2` 하위 평가 fixture에 대표 질의 3개와 최소 60개 query를 보존하고 `exact/name variant` 15개 이상, `intent/description` 25개 이상, `intent+hard filter` 20개 이상의 고정 분포와 cohort별 기대·제외 game ID·출처를 기록한다. 2인 독립 판정, 불일치 제3 판정과 cohort별 baseline manifest를 사용한다. | 모든 cohort와 전체 집합에서 hard-filter violation rate `0`, cohort별·전체 Recall@10·MRR@10·nDCG@10이 manifest의 baseline 대비 승인 임계값을 각각 통과한다. 대표 질의의 기대 결과·필수 조건·관련성 이유가 없거나 표본·분포·임계값이 승인되지 않은 상태는 품질 합격으로 표시하지 않는다. |
| 실패·복구 검증 | index `BUILDING`/`FAILED`, provider timeout·5xx, stale release, cutover 중단, retry 중복, 이전 `READY` rollback | 부분 결과·잘못된 release·필터 우회가 없고 fallback 또는 `503`으로 결정적으로 수렴한다. 기존 P1 검색과 catalog 원본은 영향받지 않는다. |

## 배포와 실측

- 고정 release SHA·image digest: 구현·검증 배포 시 애플리케이션 release SHA, image digest, catalog release ID, index version, fixture manifest hash와 embedding/provider를 사용한다. 현재 값은 배포 기록에서 채우며 비워 둔 값을 완료 근거로 사용하지 않는다.
- 배포 환경과 필수 설정: 현재 지원 PostgreSQL과 동일한 schema·locale·catalog release, 검색 index 상태 저장소, 승인된 provider/model 또는 lexical-only fallback, timeout·retry·feature flag를 고정한다. 개발용 작은 fixture와 production catalog를 같은 품질 근거로 취급하지 않는다.
- migration·호환·rollback 순서: API/코드의 backward-compatible read 경계를 먼저 배포하고, 새 projection/index를 `BUILDING`으로 생성·검증한 뒤 `READY` pointer를 원자적으로 활성화한다. 실패하면 이전 `READY` pointer 또는 lexical fallback으로 되돌리고, 물리 schema 제거는 별도 호환 기간과 ADR 승인 뒤에 한다.
- 핵심 성공·거절·실패·복구 canary: 이름 변형·의도 query·의도+인원/시간/테마 필터, zero-result, 비로그인 `playedFilter`, provider timeout, index 없음(lexical fallback `200` 또는 양쪽 불가 `503`), build 실패, release 순서 역전, rollback 후 재검색을 포함한다.
- 수집할 지연·오류·업무 결과·비용 신호: mode별 request count, p50/p95/p99 latency, `VALIDATION_ERROR`·`UNAUTHENTICATED`·`SEARCH_UNAVAILABLE`, fallback rate, zero-result rate, hard-filter violation count, index freshness/build duration, 평가 metric, provider 호출 수·token/추정 비용을 수집한다.
- 금지할 label·로그 필드: query 원문·query hash, 게임 설명·검색 문서 원문, 사용자 ID·이메일·닉네임·세션·CSRF token, ROOM·채팅·프롬프트·provider 응답 원문을 넣지 않는다.
- 유효 실측과 `INVALID` 판정 조건: release SHA·image digest·catalog/index/fixture hash 중 하나라도 고정되지 않았거나, baseline과 candidate의 query·필터·page·size·데이터가 다르거나, provider/model·timeout·retry가 기록되지 않았거나, 원자료·오류·fallback 로그가 누락되면 `INVALID`다. script 실행 성공만으로 유효 측정으로 표시하지 않는다.
- 증거 보존 위치: 품질 fixture·manifest는 `docs/p2`의 검색 평가 경로에, HTTP/DB/외부 의존성 원자료와 hash는 `docs/measurements/p2/search/`에, 운영 배포 manifest·rollback 기록은 운영 증거 저장소에 보존한다. 개인 query 원문과 개인정보는 보존하지 않는다.
