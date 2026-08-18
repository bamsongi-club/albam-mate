# P2 게임 탐색 도우미 기능 명세

> **문서 상태: draft**
>
> 담당자: `@hanyejin` · 기능 ID: `DISCOVERY-01` · 상태 정본: [P2 기능 상태](README.md#기능별-현재-상태)

## 문서 책임

- 이 문서가 소유하는 기능 동작과 완료 기준: 사용자의 자연어 게임 탐색 요청을 해석하고 `SEARCH-04` 읽기 전용 검색 도구를 호출하며, 검색 결과에 근거한 응답·추가 질문·거절·fallback을 결정하는 게임 탐색 도우미의 동작과 `DISCOVERY-01` 완료 기준.
- 이 문서가 소유하지 않는 API·ERD·아키텍처·기술 결정: HTTP 경로·DTO·오류 코드, 저장 구조와 대화 이력 보존, 모듈·provider·model·prompt framework 선택과 외부 검색 adapter 구현. 각 소유 정본과 승인 ADR에서 확정한다.
- 연결할 P1 종료 계약과 P2 공통 규칙: [P1 검색 종료 명세](../archive/p1/search.md), [P1 기능 종료 상태](../archive/p1/README.md#기능별-종료-상태), [SEARCH-04 게임 의미 기반 검색](search.md#search-04), [P2 공통 명세](../P2-spec.md), 현재 [P2 기능 상태](README.md#기능별-현재-상태).

`DISCOVERY-01`은 일반 목적의 자율 비서가 아니라 `SEARCH-04`를 호출하는 읽기 전용 게임 탐색 도우미다. 현재 문서의 논리적 도구명·오류명·응답 필드는 구현 전 [API](../API.md)와 승인 ADR에서 확정하며, 문서 작성만으로 도우미 endpoint나 provider 도입이 완료된 것으로 판정하지 않는다.

도우미가 사용할 수 있는 BGG 기반 catalog 입력의 정책 범위는 [승인 데이터셋의 AI·embedding 사용 범위](../game-catalog/2026-08-14-bgg-ai-embedding-approval.md)와 [ADR-0060](../adr/game/0060-approved-catalog-ai-embedding-scope.md)의 release·필드·가공 allowlist를 따른다. 구체 승인 manifest를 runner gate로 검증하기 전에는 BGG 기반 입력을 전달하지 않는다. 승인 범위는 catalog 데이터에 한정하며, 사용자 query·개인정보·대화 보존과 provider/model 선택을 자동 승인하지 않는다.

## `AI-01`과의 경계

`DISCOVERY-01`과 [`AI-01` AI 모임 도우미](assistant.md#ai-01-ai-모임-도우미)는 같은 자연어 경험으로 합치지 않는다. 이 문서는 `SEARCH-04`를 호출하는 **읽기 전용 게임 탐색**을 소유하고, `AI-01`은 별도 도우미 화면에서 서버 추천과 사용자의 명시적 확인 뒤 기존 Room 생성 유스케이스로 이어지는 **확인형 쓰기 흐름**을 소유한다.

- `DISCOVERY-01`: 검색 후보·조건·fallback을 반환하며 ROOM 생성·참가·매칭·채팅 등 상태 변경 tool을 호출하지 않는다.
- `AI-01`: 확인 전 Room·ChatRoom을 만들지 않고, `AI-02`가 소유하는 `game.contract` 후보 조회와 `AI-03`이 소유하는 승인된 `room.contract` 확인형 command를 통해서만 Room 생성 흐름을 시작한다. `SEARCH-04`를 우회 호출하거나 `game` repository·catalog를 직접 읽지 않는다. 기능 동작·구현 순서·검증 항목은 [AI-01 기능 명세](assistant.md#확정된-사항과-소유-정본)가 정리하고, 기술 결정은 [ADR-0068](../adr/platform/0068-p2-ai-provider-consent-and-operation-boundary.md)·[ADR-0069](../adr/room/0069-p2-ai-draft-confirmation-and-idempotent-room-command.md)·[ADR-0070](../adr/room/0070-p2-room-region-closed-set-and-compatibility.md)가 소유한다.
- 두 기능의 공통 자연어 입력·게임 후보 조회·운영 관측은 소유 정본과 공개 계약을 먼저 정한 뒤 연결하며, 한 기능의 provider·저장·권한 결정을 다른 기능의 계약으로 복사하지 않는다.

## 구현 컨텍스트

### 해결할 사용자 문제

사용자는 “가족과 짧게 할 협력 게임”, “초보자도 할 수 있고 인원이 많은 게임”처럼 플레이 의도로 요청하지만, 구조화 필터와 검색어로 다시 번역하는 과정에서 막힌다. 게임 탐색 도우미는 자연어에서 검색 가능한 조건과 의도를 추출해 `SEARCH-04`에 전달하고, 실제 검색 결과에 근거한 선택지를 보여준다. 도우미가 모르는 게임이나 실행하지 않은 작업을 지어내지 않는 것이 최종 성공 조건이다.

### 핵심 사용자 흐름

~~~text
사용자가 공개 게임 탐색 화면과 유효한 세션을 가진 상태
→ 사용자가 자연어로 플레이 의도·인원·시간·테마 등 검색 조건을 입력
→ 서버가 입력·세션·CSRF·기능 flag와 도우미 정책을 검증
→ 도우미가 숫자·범위 조건은 P1 hard filter로, 명시적 메커니즘·카테고리·테마는 검수된 Sparse 조건으로, 플레이 느낌은 SEARCH-04 semantic query로 구조화
→ 도우미가 허용된 조건만 검증하고 호출자 권한을 전달해 SEARCH-04 읽기 전용 도구를 1회 호출
→ SEARCH-04가 hard filter·공개 범위·fallback 상태를 적용한 결과를 반환
→ 도우미가 반환된 게임과 근거만으로 응답하고, 조건이 모호하면 검색하지 않고 추가 질문
→ 사용자가 결과·적용 필터·fallback 상태를 확인하고 검색 조건을 수정
~~~

업무 거절은 검색과 무관한 요청, 쓰기 작업 요청, 허용하지 않은 필터와 비로그인 `playedFilter`로 구분한다. 기술 실패는 도우미 provider·검색 도구 timeout, 잘못된 tool arguments, 외부 의존성 오류로 구분한다. 검색 도구가 성공한 경우에만 결과를 보여주며, 도우미가 실행하지 않은 검색 결과·게임 설명·업무 처리를 성공한 것처럼 표시하지 않는다.

## 범위

### 포함 범위

- 자연어 요청에서 `query`, P1 hard filter, 정렬·페이지 요구를 추출하고 허용 목록으로 검증하는 읽기 흐름. 숫자·범위는 구조화 필터로, 명시적 메커니즘·카테고리·테마는 검수된 Sparse 조건으로, 플레이 경험 표현은 semantic query 신호로 분리한다.
- `SEARCH-04` logical `search_games` 도구를 통한 후보 조회와 사용자 권한·fallback 상태 전달. 실제 도구명·DTO·경로는 API와 ADR에서 확정한다.
- 검색 결과의 game ID·표시 필드·적용 조건·fallback 상태만 사용한 근거 있는 응답과 모호한 요청에 대한 clarification.
- 모호한 플레이 경험을 임의의 기본값으로 채우지 않고, “어떤 재미를 원하시나요?”처럼 필요한 조건을 확인한 뒤 다음 요청에서 검색하는 흐름. 평가 기준은 [SEARCH-04 대표 평가 질의](search.md#대표-평가-질의)를 사용한다.
- 사용자·catalog 텍스트를 지시문이 아닌 untrusted data로 취급하는 prompt-injection 방어, 호출 수·지연·비용·품질 관측.
- 도우미 기능을 feature flag로 켜고 끄며, 비활성화 시 사용자가 `SEARCH-04` 직접 검색으로 계속 탐색할 수 있는 fallback.

### 제외 범위와 재검토 조건

- 이번 단계에서 하지 않는 것:
  - ROOM 생성·참가, 매칭 신청·취소, 채팅·알림·찜·카탈로그 수정처럼 상태를 바꾸는 tool calling
  - 검색 결과에 없는 게임·평점·설명·개인화 이유를 생성하거나 외부 웹을 근거처럼 사용하는 동작
  - 사용자 검색 이력·대화 원문·개인 프로필을 저장해 개인화하는 동작
  - 여러 도우미가 자율적으로 연쇄 호출하거나 사용자 확인 없이 다음 행동을 실행하는 loop
  - provider·model·vector DB·prompt framework를 이 기능 문서에서 선택하는 결정
- 제외 이유: 현재 P1 업무 불변식과 개인정보 경계를 바꾸는 쓰기 권한을 추가하지 않고, `SEARCH-04` 결과 품질·권한·fallback을 먼저 검증하기 위해서다.
- 다시 검토할 관측 근거·조건:
  - 쓰기 tool별 API·권한·CSRF·확인 UX와 보상/rollback 계약이 별도 명세·ADR로 승인될 때
  - 도우미 결과의 grounding, hard-filter 위반, clarification과 비용·지연을 고정 fixture에서 재현할 수 있을 때
  - 실제 사용자 요청에서 직접 검색 대비 해결률 개선과 안전 거절 결과가 승인된 표본으로 확인될 때

## 기능 규칙

### 데이터

- 검색 대상 원천과 공개·가공 이용 근거는 `SEARCH-04`와 `game` catalog가 소유한다. 도우미는 검색 index나 catalog를 직접 수정하지 않는다.
- 도우미 입력 query와 생성 응답 원문은 기본적으로 저장하지 않으며, 실제 실행 시에는 검증을 통과한 승인 manifest의 `approvedFields`에 포함된 catalog 필드·현재 사용자 요청·필요한 구조화 필터만 전달한다. manifest 검증 전에는 BGG 필드를 전달하지 않는다. 사용자 ID·이메일·세션·CSRF token·비밀값·ROOM/채팅 원문은 prompt와 tool input에서 제외한다.
- catalog 설명·게임명·사용자 입력은 실행 지시가 아닌 untrusted data다. 그 안의 prompt injection 문구가 system policy·tool allowlist·사용자 권한을 바꾸지 못한다.
- 응답에 포함하는 게임·필터·fallback 근거는 같은 요청의 유효한 `SEARCH-04` 응답에서만 가져온다. 도우미가 만든 game ID·점수·출처를 사실처럼 추가하지 않는다.
- metric label과 중앙 로그에는 query·prompt·응답·게임 설명 원문, query hash, 사용자 식별자와 provider 원문을 넣지 않는다. 관측은 길이 bucket·intent cohort·tool outcome·index version·error code처럼 제한된 값만 사용한다.

### 인증·권한

- 도우미 요청은 P1의 사용자 세션·인증·CSRF·업무 불변식을 통과해야 하며, 자연어 입력을 받는 호출의 HTTP 보호 정책을 API 정본에서 명시한다. 읽기 기능이라는 이유로 인증·CSRF 검사를 임의로 우회하지 않는다.
- 도우미는 현재 호출자의 권한과 사용자 식별 맥락을 `SEARCH-04`에 전달한다. 서비스 계정·관리자 경로·내부 우회 API로 승격하지 않으며 `playedFilter`는 현재 세션 사용자에 대해서만 허용한다.
- 모델이 만들 수 있는 도구는 allowlist의 `SEARCH-04` 읽기 도구 하나로 제한한다. SQL·검색 DSL·URL·임의 tool name을 모델 출력 그대로 실행하지 않고 서버가 schema·길이·필터·페이지를 검증한다.
- 현재 범위에는 쓰기 도구가 없으므로 확인 화면을 통과해도 ROOM·매칭·채팅·알림 상태는 변하지 않는다. 향후 쓰기 도구는 사용자 확인·별도 권한·별도 기능 계약 없이 추가하지 않는다.

### 상태·동시성·일관성

- 도우미 실행기는 요청 단위의 임시 상태를 소유하고, catalog·검색 후보·index version은 `game`·`SEARCH-04`가 소유한다. 대화 이력과 장기 memory는 현재 범위에서 저장하지 않는다.
- 사용자 한 턴의 도우미 요청은 검색 도구를 최대 1회 호출한다. 조건이 모호하면 도구를 호출하지 않고 질문하며, 추가 검색은 사용자의 다음 입력으로 새 요청을 만든다.
- 요청 재시도는 읽기 결과만 재계산하며 tool 호출에 쓰기 부작용이 없다. `request_id`와 호출자·index version을 함께 기록할 수 있을 때 동일 재시도는 동일한 결과 경계와 fallback 상태를 유지한다.
- `SEARCH-04`가 반환한 하나의 index version과 응답만 사용한다. 오래된 tool 결과와 새 요청 결과를 섞지 않으며, 늦게 도착한 응답이 현재 요청 결과를 덮어쓰지 못하게 한다.

### 실패·복구

- 업무 거절: 검색 의도가 없거나 지원하지 않는 쓰기 작업을 요청하면 도구를 호출하지 않고 지원 범위와 직접 검색 경로를 안내한다. 비로그인 `playedFilter`와 허용하지 않은 필터는 P1·SEARCH-04의 확정 오류 계약으로 거절한다.
- 모호한 요청: 게임을 단정해 반환하지 않고 필요한 조건을 한 번에 확인하는 질문을 반환한다. 사용자가 답하지 않아도 임의의 기본값으로 검색하지 않는다.
- 도우미 provider timeout·5xx·응답 검증 실패: 전체 timeout 안에서 제한된 1회 재시도 후 `DISCOVERY_UNAVAILABLE` 또는 직접 `SEARCH-04`로 이동할 수 있는 명시적 fallback 상태를 반환한다. 도구 결과가 없으면 성공 결과나 설명을 생성하지 않는다.
- `SEARCH-04` timeout·index 부재·provider 오류는 해당 기능의 `200` lexical fallback 또는 `503 SEARCH_UNAVAILABLE` 계약을 그대로 전달한다. 도우미가 오류를 빈 성공 결과나 추정 결과로 바꾸지 않는다.
- tool arguments가 schema를 벗어나거나 권한이 맞지 않으면 도구 호출을 중단하고 내부 원문·비밀값을 사용자 응답에 노출하지 않는 안전 오류로 수렴한다. 반복 재시도·무한 도우미 loop는 허용하지 않는다.
- 복구는 feature flag를 끄고 `SEARCH-04` 직접 검색을 제공하거나 provider·tool 상태를 복원한 뒤 고정 fixture와 canary를 다시 통과시키는 순서다. 이 기능은 쓰기 상태가 없으므로 보상 트랜잭션을 만들지 않는다.

## 정본 변경 지도

| 정본 | 변경 필요 여부 | 반영할 내용·링크 |
| --- | --- | --- |
| [API](../API.md) | 필요 | 도우미 요청·응답, clarification·거절·`DISCOVERY_UNAVAILABLE`, tool schema, 사용자 인증·CSRF와 fallback 상태를 등록한다. `SEARCH-04`의 검색 결과·오류 계약을 재정의하지 않는다. |
| [ERD](../ERD.md) | 불필요(초기 범위) | 요청·대화 이력과 도우미 audit을 저장하지 않는다. 저장이 필요해지면 개인정보·보존·삭제·접근 제약을 별도 ERD 변경과 ADR로 검토한다. |
| [아키텍처](../ARCHITECTURE.md) | 필요 | 도우미 실행기와 `SEARCH-04` tool port/adapter의 책임, 호출자 권한 전달, provider·검색 timeout·fallback 흐름을 반영한다. |
| [ADR](../adr/README.md) | 필요 | [ADR-0060](../adr/game/0060-approved-catalog-ai-embedding-scope.md)의 승인 catalog 범위를 전제로 provider/model·prompt/tool schema·호출 예산·prompt injection·fallback·향후 쓰기 tool 승인 경계를 비교하고 별도 ADR로 승인한다. |
| 운영 가이드 | 필요 | provider key·feature flag·timeout·비용 상한·canary·rollback, query/prompt/응답 원문 금지와 `DISCOVERY_UNAVAILABLE` 대응 절차를 추가한다. |

되돌리기 어렵거나 검색·챗봇·매칭에 영향을 주는 provider·model·저장·권한 선택은 [ADR 템플릿](../adr/0000-template.md)으로 별도 기록한다. 이 문서의 논리적 tool 이름은 ADR과 API가 승인하기 전까지 구현 계약이 아니다.

## 완료 기준

### DISCOVERY-01

- `DISCOVERY-01-AC1`: 자연어 의도와 지원 P1 hard filter가 포함된 요청이 유효하면 도우미가 호출자 권한으로 `SEARCH-04` 읽기 도구를 최대 1회 호출하고, 응답에는 도구가 반환한 게임·적용 조건·fallback 상태만 포함된다. 판정은 고정 HTTP 계약 fixture와 tool call trace·응답 game ID 대조로 한다.
- `DISCOVERY-01-AC2`: 도우미가 관리자·서비스 계정·임의 SQL/DSL·쓰기 도구를 호출하지 않고, 비로그인 `playedFilter`와 미허용 조건을 확정 오류로 거절한다. 판정은 권한별 tool allowlist 테스트와 호출 principal·도구 인자 검증으로 한다.
- `DISCOVERY-01-AC3`: 조건이 모호하거나 지원하지 않는 작업이면 검색 결과를 지어내지 않고 clarification 또는 안전 거절을 반환하며, 사용자가 답하기 전 검색 도구를 호출하지 않는다. 판정은 모호한 의도·쓰기 요청·prompt injection fixture의 tool call 0건과 응답 상태 비교로 한다.
- `DISCOVERY-01-AC4`: 응답의 모든 게임 ID·필터·fallback 설명이 같은 요청의 `SEARCH-04` 응답과 일치하고, hard-filter violation rate와 근거 없는 게임·평가·업무 결과 주장이 0이다. 판정은 tool response replay와 구조화 claim 검증으로 한다.
- `DISCOVERY-01-AC5`: 도우미 provider·tool timeout·응답 schema 오류가 제한된 재시도 뒤 `DISCOVERY_UNAVAILABLE` 또는 직접 `SEARCH-04` fallback으로 수렴하며, 오류를 성공 결과로 포장하지 않는다. 판정은 장애 주입·timeout 예산·사용자 화면 상태 확인으로 한다.
- `DISCOVERY-01-AC6`: 같은 `request_id` 재시도와 늦은 응답이 쓰기 부작용·중복 상태를 만들지 않고, 동일 index version에서 결과 순서와 fallback 상태가 일관된다. 판정은 동시 재시도·응답 순서 역전·중복 tool trace 비교로 한다.
- `DISCOVERY-01-AC7`: query·prompt·응답·사용자 식별자·비밀값이 metric label과 중앙 로그에 없고 catalog 또는 사용자 입력의 prompt injection이 권한·tool allowlist를 바꾸지 못한다. 판정은 로그/metric payload와 adversarial fixture 검사로 한다.
- `DISCOVERY-01-AC8`: 평가 fixture가 `SEARCH-04`의 대표 질의 3개를 포함해 최소 60개 시나리오를 `direct intent` 20개, `intent+hard filter` 15개, `ambiguous/missing constraint` 10개, `no-result/fallback` 10개, `prompt injection/unsupported action` 5개로 고정한다. tool schema validity `100%`, hard-filter violation `0`, 근거 없는 결과 주장 `0`을 전체·cohort별로 확인하고 자연어 응답 평가는 2인 독립 판정과 제3 판정으로 재현한다.
- `DISCOVERY-01-AC9`: 고정 release·image digest·provider/model·prompt/tool schema version·SEARCH-04 index version·fixture hash에서 성공·clarification·거절·fallback·복구 canary를 재현하고, 유효하지 않은 측정은 `INVALID`로 표시한다. 판정은 배포 manifest와 원자료 hash 대조로 한다.

## 검증 계획

| 증거 | fixture·환경 | 판정 기준 |
| --- | --- | --- |
| 단위·통합 테스트 | intent/constraint 추출, allowlist/schema 검증, principal 전달, clarification·거절, grounding, 단일 tool 호출과 retry budget | 임의 도구·권한 상승·근거 없는 결과가 없고, SEARCH-04 계약을 바꾸지 않는다. |
| PostgreSQL·외부 의존성 검증 | 승인된 SEARCH-04 catalog/index fixture, 현재 지원 PostgreSQL, provider/model·prompt/tool schema version pin, timeout·5xx 응답 fixture | 실제 hard filter·index version·fallback과 provider 장애가 재현되며 H2/mock만으로 완료 판정하지 않는다. |
| 프론트엔드·계약 검증 | 자연어 입력, 적용 filter, 검색 결과 근거, clarification, 직접 검색 fallback, 거절·서비스 오류 화면 | 사용자가 도우미 응답과 실제 검색 결과·fallback 상태를 구분하고, 실행하지 않은 작업을 완료로 오인하지 않는다. |
| 품질 평가 | `docs/p2` 평가 fixture 최소 60개와 위 cohort 분포, 2인 독립 판정·불일치 제3 판정 | tool schema validity `100%`, hard-filter violation `0`, unsupported claim `0`; cohort별 clarification·grounding·응답 품질 기준과 산식을 manifest에 고정한다. |
| 실패·복구 검증 | provider timeout·5xx·malformed tool output, SEARCH-04 index 없음·fallback, feature flag off, prompt injection, 응답 순서 역전 | 제한된 retry 뒤 명시적 오류·fallback으로 수렴하고 권한·결과·로그 경계를 위반하지 않는다. |

## 배포와 실측

- 고정 release SHA·image digest: 애플리케이션 release SHA, image digest, provider/model version, prompt/tool schema version, SEARCH-04 catalog/index version, fixture manifest hash를 함께 기록한다.
- 배포 환경과 필수 설정: provider key는 secret store에서만 읽고 feature flag, timeout·retry budget, token/비용 상한, 직접 SEARCH-04 fallback과 API contract version을 고정한다. 현재 값이 없는 상태는 완료 근거가 아니다.
- migration·호환·rollback 순서: 초기 범위는 영속 migration 없이 backward-compatible API와 feature flag를 먼저 배포하고, canary 통과 뒤 도우미를 활성화한다. provider·tool 장애 시 flag off와 직접 SEARCH-04로 rollback하며, 대화 이력 저장은 별도 호환·ADR 절차를 따른다.
- 핵심 성공·거절·실패·복구 canary: direct intent, intent+hard filter, clarification, no-result, 비로그인 `playedFilter`, 쓰기 작업 거절, prompt injection, provider timeout, malformed tool output, feature flag off, 직접 검색 fallback을 포함한다.
- 수집할 지연·오류·업무 결과·비용 신호: request count, p50/p95/p99 latency, clarification·refusal·tool validation·`DISCOVERY_UNAVAILABLE`·fallback rate, tool call count, hard-filter violation, grounding 평가, provider 호출 수·token/추정 비용을 수집한다.
- 금지할 label·로그 필드: query·prompt·응답·게임 설명 원문과 hash, 사용자 ID·이메일·닉네임·세션·CSRF token, ROOM·채팅·provider 응답 원문과 secret을 넣지 않는다.
- 유효 실측과 `INVALID` 판정 조건: release/image/provider/prompt/tool/index/fixture version 중 하나라도 고정되지 않았거나, query cohort·모델·timeout·retry·API contract가 비교마다 다르거나, tool trace·오류·fallback·원자료가 누락되면 `INVALID`다.
- 증거 보존 위치: 도우미 평가 fixture·manifest는 `docs/p2`의 탐색 도우미 평가 경로에, HTTP/tool trace의 비식별 원자료와 hash는 `docs/measurements/p2/game-discovery/`에, 배포·rollback 기록은 운영 증거 저장소에 보존한다. 원문 개인정보와 prompt/응답 원문은 보존하지 않는다.
