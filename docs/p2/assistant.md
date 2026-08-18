# P2 AI 모임 도우미 기능 명세

> **문서 상태: 결정 완료·공개 목표 계약 반영 · 구현 보류**
>
> 담당자: `@silverThunder09` · 기능 ID: `AI-01` · 상태 정본: [P2 기능 상태](README.md#기능별-현재-상태)

## AI-01 AI 모임 도우미

## 문서 책임

- 이 문서가 소유하는 기능 동작과 완료 기준: 로그인 사용자의 자연어 조건을 서버가 구조화하고, 추천과 누락 정보 확인을 거쳐 사용자가 명시적으로 확인한 경우 기존 Room 생성 유스케이스로 연결하는 AI 모임 도우미의 동작과 `AI-01` 완료 기준.
- 이 문서가 소유하지 않는 결정: 외부 AI 처리·동의·provider 운영 경계는 [ADR-0068](../adr/platform/0068-p2-ai-provider-consent-and-operation-boundary.md), Room 초안·확인형 생성은 [ADR-0069](../adr/room/0069-p2-ai-draft-confirmation-and-idempotent-room-command.md), 지역 계약은 [ADR-0070](../adr/room/0070-p2-room-region-closed-set-and-compatibility.md)가 소유한다. 공개 HTTP·저장·모듈 계약은 [API](../API.md), [ERD](../ERD.md), [아키텍처](../ARCHITECTURE.md)에 반영됐다.
- `AI-01`은 [`DISCOVERY-01` 게임 탐색 도우미](game-discovery-assistant.md#ai-01과의-경계)와 별도 기능이다. `DISCOVERY-01`은 `SEARCH-04` 읽기 전용 탐색을 소유하고, `AI-01`은 확인형 Room 생성 흐름을 소유한다.

문서 등록과 AI-D01~D03 결정은 provider 도입·생산 코드·배포·실측 완료를 뜻하지 않는다. [#795](https://github.com/bamsongi-club/albam-mate/issues/795)·[#796](https://github.com/bamsongi-club/albam-mate/issues/796)와 ADR-0068~0070의 결정은 완료됐고, migration·구현 이슈의 대상 경로·테스트 계약·rollback을 별도로 고정하기 전까지 생산 구현은 보류한다.

## 확정된 사항과 소유 정본

`AI-01`의 기능 동작·완료 기준·구현 순서·검증 항목은 이 문서가 소유한다. 되돌리기 어렵거나 여러 모듈에 영향을 주는 기술 결정은 아래 ADR이 소유하고, HTTP·저장·모듈 계약은 각각 [API](../API.md)·[ERD](../ERD.md)·[아키텍처](../ARCHITECTURE.md)가 소유한다. 이 문서는 결정 요약을 제공하지만 ADR의 대체 정본이 아니다.

| 결정 | 확정 내용 | 기술 정본 |
| --- | --- | --- |
| `AI-D01` | 외부 provider는 `assistant.contract.AssistantIntentExtractor` 한 port 뒤에 두고, 동의·PII/secret 차단·no-retention·no-training·fake provider·호출/비용 상한을 적용한다. 모델은 `propose_game_room_intent` 구조화 결과만 제안하며 검색·Room 쓰기·tool loop 권한을 갖지 않는다. | [ADR-0068](../adr/platform/0068-p2-ai-provider-consent-and-operation-boundary.md) |
| `AI-D02` | `RECOMMEND`는 후보만 조회하고 초안을 만들지 않는다. `CREATE_ROOM` 전환 후 15분 초안을 만들며, 상세 장소와 최종 조건을 사용자가 확인한 뒤에만 `room.contract` 확인형 command를 호출한다. `Idempotency-Key`·draft version·Room·ChatRoom 원자성을 적용한다. | [ADR-0069](../adr/room/0069-p2-ai-draft-confirmation-and-idempotent-room-command.md) |
| `AI-D03` | 지역은 `홍대`·`강남`·`건대`·`잠실`의 닫힌 집합이다. 호환 기간의 누락은 `홍대`로 해석하고, 기존 행은 재작성하지 않으며 migration 전 허용값을 검사한다. | [ADR-0070](../adr/room/0070-p2-room-region-closed-set-and-compatibility.md) |

결정 흐름은 `#794`의 AI-01 범위·소유 경계에서 시작해 완료된 `#795`의 AI-D01, `#796`의 AI-D02·D03으로 이어진다. 세 결정은 승인된 목표 계약으로 공개 API·ERD·아키텍처에 반영되며, 생산 코드·자동 검증·배포·실측은 별도 상태 축으로 관리한다.

## 구현 컨텍스트

### 해결할 사용자 문제

사용자는 “초보자와 할 협력 게임으로 주말 저녁 모임을 만들고 싶다”처럼 목적과 조건을 자연어로 말한다. 현재 Room 생성은 구조화된 요청을 즉시 받아야 하므로, 사용자가 원하는 게임·인원·시작 시각·지역을 직접 조합하고 확인하는 과정이 끊긴다. `AI-01`은 자연어를 서버가 검증 가능한 조건으로 바꾸고, 추천·추가 질문·확인 카드를 거쳐 기존 Room 생성 유스케이스로 연결한다.

### 핵심 흐름

~~~text
로그인·동의 확인
  → 자연어 요청 접수
  → 서버가 구조화 조건·누락 필드 검증
  → 게임 후보 추천 또는 추가 질문
  → 사용자가 상세 장소와 최종 조건 확인
  → 명시적 확인 + 멱등성 검증
  → 기존 Room 생성 command 호출
  → 생성된 Room·ChatRoom 결과 반환
~~~

확인 전에는 Room이나 ChatRoom을 만들지 않는다. 추천 결과가 없거나 provider·검증·권한 경계가 실패하면 성공한 Room 생성처럼 표시하지 않고, 사용자가 다음 행동을 알 수 있는 상태로 반환한다.

### 액션별 추천·생성 경계

서버는 자연어 입력을 처리할 때 추천(`RECOMMEND`)과 방 생성(`CREATE_ROOM`)을 구분한다. 아래는 기능 명세의 논리 경계이며, 공개 HTTP 필드와 저장 구조는 승인된 AI-D01~D03 목표 계약을 [API](../API.md), [ERD](../ERD.md), [아키텍처](../ARCHITECTURE.md)에 반영한다. 실제 구현은 구현 이슈의 대상 경로·테스트·rollback을 고정한 뒤 시작한다.

| 액션 | 목적 | `missingFields`와 다음 행동 | 부수효과 |
| --- | --- | --- | --- |
| `RECOMMEND` | 검색 조건에 맞는 게임 후보 추천 | 검색 조건이 하나도 없거나 부족하면 추천에 필요한 검색 조건만 질문한다. 방 생성 전용인 총 인원·시작 시각·지역·게임 선택은 요구하지 않는다. | 검색 조건이 유효할 때만 후보를 조회하며 활성 임시 초안·Room을 만들지 않는다. |
| `CREATE_ROOM` | 선택한 게임과 방 조건으로 확인형 생성 진행 | 총 인원·시작 시각·지역·게임 선택 등 방 생성에 필요한 필드를 확인한다. | 이 액션에서만 임시 초안을 만들 수 있으며, 사용자의 명시적 확인 전에는 Room·ChatRoom·참가 관계를 만들지 않는다. |

`게임 추천해줘`처럼 `RECOMMEND` 검색 조건이 전혀 없으면 후보 조회를 호출하지 않고 `NEEDS_INPUT`으로 필요한 검색 조건만 되묻는다. 조건이 유효하지만 후보가 0건인 경우의 `NO_CANDIDATES`와 구분한다.

## 범위

### 포함 범위

- `AI-01a`: 로그인·외부 처리 동의, 자연어에서 서버가 소비할 구조화 조건 추출, 누락 필드·후보 없음·지원하지 않는 요청의 상태 구분.
- `AI-01b`: 외부 AI port와 adapter, payload allowlist, 호출 한도·timeout·비용 상한·fail-closed·fake provider 기본 검증. 세부 값은 [ADR-0068](../adr/platform/0068-p2-ai-provider-consent-and-operation-boundary.md)을 따른다.
- `AI-01c`: 15분 임시 초안, 상세 장소 입력, 명시적 확인, `Idempotency-Key`와 draft version을 사용하는 Room 생성 연결. 세부 저장·지역·오류 계약은 [ADR-0069](../adr/room/0069-p2-ai-draft-confirmation-and-idempotent-room-command.md)와 [ADR-0070](../adr/room/0070-p2-room-region-closed-set-and-compatibility.md)를 따른다.
- `AI-01d`: `#/assistant` 화면, 추천·추가 질문·확인 카드·실패 상태, `내정보 > AI 설정`의 동의 상태·정책 버전·URL 확인과 철회 화면, 기존 수동 Room 생성 회귀 보호.
- `AI-01` 전체 흐름의 품질·계약·부하 검증 설계는 [검증 설계](assistant-load-test.md)를 따른다.

### 제외 범위와 재검토 조건

- `DISCOVERY-01`의 읽기 전용 `SEARCH-04` 검색 계약을 이 문서에서 재정의하지 않는다.
- 모델이 게임·Room·사용자·참가·채팅 API를 직접 호출하거나 사용자 확인 없이 상태를 변경하는 tool loop를 만들지 않는다.
- 승인된 catalog allowlist 밖의 BGG 원문·개인정보·대화 이력을 provider에 보내거나 앱·중앙 관측에 원문으로 저장하지 않는다.
- 기존 `POST /api/rooms` 계약을 조용히 바꾸지 않는다. 확인형 command·초안·지역 필드는 반영된 API·ERD·아키텍처 계약과 별도 migration 구현 이슈를 따른다.
- 실제 provider·운영 비용·배포 용량·품질 측정은 이 명세 작성으로 완료하지 않는다. 승인된 fixture·fake provider·고정 release로 별도 검증한다.

재검토 조건은 외부 처리 동의·보존 정책·provider 정책, Room 생성의 공개 계약·저장 제약·멱등성, 추천 품질·안전·비용 검증 방법이 변경되거나 현재 계약으로 설명할 수 없는 실패가 발견되는 경우다.

## 구현 순서와 테스트 계약

### 구현 슬라이스

| 슬라이스 | 책임 | 구현 전제 |
| --- | --- | --- |
| `AI-01a` | 동의·철회, 조건 추출, 서버 추천·추가 질문 | ADR-0068, API·아키텍처 계약 |
| `AI-01b` | provider adapter, fake provider, quota·timeout·비용·fail-closed | ADR-0068, 설정·관측 계약 |
| `AI-01c` | 임시 초안, 장소 입력, 지역 검증, 확인형 Room command와 멱등성 | ADR-0069·0070, ERD·API·Room 계약 |
| `AI-01d` | `#/assistant` 화면, 확인 카드, 실패 상태, `내정보 > AI 설정` 동의·철회 화면, 수동 Room 회귀 | `AI-01a`·`AI-01c` 공개 응답 계약 |

각 구현 이슈는 대상 경로, 공개 계약, migration, 테스트 ID, rollback과 제외 범위를 선언한다. 공유 파일을 여러 슬라이스가 수정하면 먼저 소유자를 정하고 계약 변경을 선행한다. 이 문서와 ADR·API·ERD·아키텍처의 계약 및 각 구현 이슈의 테스트 계약이 고정되기 전에는 provider dependency, 공개 endpoint, 테이블, 생산 코드를 추가하지 않는다.

### 결정된 검증 항목

1. 결정적 fake provider에서 같은 입력·schema version·fixture hash가 같은 구조화 결과와 오류를 반환하고, 실제 provider가 기본 테스트에서 호출되지 않는지 확인한다.
2. 인증·동의·CSRF·feature flag·PII/secret 차단·payload allowlist·no-retention/no-training 조건이 provider 호출 전에 적용되는지 확인한다.
3. `RECOMMEND`의 검색 조건이 모두 AND로 적용되고 내부 `RANK-01` 순서로 정렬되며, 추천 조건이 없으면 후보 조회와 초안 생성이 모두 0건인지 확인한다.
4. `CREATE_ROOM`의 누락 필드가 정확히 `NEEDS_INPUT`으로 반환되고, 확인 전 Room·ChatRoom·참가 관계가 생성되지 않는지 확인한다.
5. 유효한 confirm이 Room 정확히 1개와 ChatRoom 정확히 1개를 원자적으로 만들고, Room 생성 실패 시 부분 상태를 남기지 않는지 확인한다.
6. 같은 `(currentUserId, draft/resource, operation)` 범위의 동일 `Idempotency-Key`·draft version 재시도가 최초 결과를 재생하고 새 Room·ChatRoom을 만들지 않는지 확인한다. 범위 밖 key 재사용·다른 version·동시 요청·만료 초안은 계약된 오류로 수렴해야 한다.
7. 동의 철회·timeout·429·schema 오류·Redis 불능·quota 초과·provider 또는 Room 생성 실패가 공개 실패 상태와 fail-closed로 끝나는지 확인한다.
8. prompt·응답·Tool 인자·게임 후보·사용자 ID·세션·비밀값이 provider payload·저장소·metric·central log에 원문으로 남지 않는지 확인한다.
9. 고정 fixture의 manifest version·hash·caseId·trace·부수효과 assertion과 결과 판정이 일치하는지 확인한다. setup 실패·관측 누락·generator 포화는 기능 실패가 아닌 `INVALID`로 기록한다.
10. 고정 release에서 승인된 latency threshold·가격 snapshot·비용 상한·운영 관측 증거가 모두 있을 때만 `PASS`를 부여하고, provider 호출·가격 snapshot·승인값이 없으면 `NOT_RUN` 또는 `NO_OBSERVATION`으로 구분한다.

상세 fixture·cohort·부하 시나리오·비용·지연 판정·runner 실행 게이트는 [AI-01 부하·계약 검증 설계](assistant-load-test.md)가 소유한다. 문서에 검증 항목을 등록한 것만으로 자동 검증·배포 검증·실측 완료로 표시하지 않는다.

## 기능 규칙

### 데이터·권한

- 로그인 사용자만 `AI-01`을 사용할 수 있으며 기존 인증·인가·CSRF·Room 업무 불변식을 그대로 통과한다.
- provider에는 고정 instruction·강제 schema·기준 시각·서버가 최소화한 사용자 문장·서버가 확인한 누락 필드만 전달한다. 호출 전에 전화번호·주소·연락처·자격증명·token 같은 PII·secret을 탐지해 승인된 방식으로 마스킹하고, 안전하게 마스킹할 수 없으면 provider 호출을 fail-closed로 거절한다. provider가 no-retention·no-training 조건을 계약으로 보장하지 못하면 호출하지 않으며, 게임 ID·Room 쓰기 권한·임의 SQL/DSL은 provider에 위임하지 않는다.
- 추천 후보 조회는 `AI-01`이 소유하는 별도 서버 읽기 흐름으로 둔다. 구조화된 추천 조건은 모두 AND로 적용한 뒤 내부 `RANK-01` 순서로 정렬하며, 공개 `RANK-01` API의 상위 10개 결과나 provider가 정한 순서를 사용하지 않는다. [API](../API.md)·[아키텍처](../ARCHITECTURE.md)의 `game.contract` AI-01 후보 조회 port를 통해서만 호출하며, `DISCOVERY-01`의 `SEARCH-04` tool을 호출하거나 `game` repository·catalog를 직접 읽지 않는다.
- 현재 존재하는 `GameQuery` 요약 계약은 서버가 이미 확정한 game ID를 보강하는 공개 계약으로만 취급하며, AI-01 후보 선택·필터·정렬을 대신하지 않는다. 후보 응답 필드는 [API](../API.md)의 `AssistantRecommendationResponse`로 승인했고, AND 필터와 내부 `RANK-01` 정렬은 이 기능의 고정 규칙으로 유지한다.
- 추천 후보와 Room 생성 가능 여부는 서버가 소유한 검증·권한 경계를 따른다. 모델 출력은 신뢰할 수 없는 구조화 입력으로 검증한다.
- `RECOMMEND`의 `missingFields`에는 추천에 필요한 검색 조건만 포함한다. 방 생성 전용 필드를 함께 채우도록 요구하지 않으며, 검색 조건이 전혀 없으면 후보 조회를 하지 않고 `NEEDS_INPUT`으로 끝낸다.
- `CREATE_ROOM`의 `missingFields`는 방 생성에 필요한 총 인원·시작 시각·지역·게임 선택을 기준으로 판정한다. 추천 단계의 누락 질문과 섞지 않는다.
- `missingFields`는 액션에서 허용한 필드와 이미 확인된 필드의 차집합으로 판정한다. 액션이 바뀌지 않는 한 추천 검색 조건과 방 생성 필드를 한 배열에 섞지 않는다.

### 확인과 부수효과

- 자연어 요청과 추천만으로는 Room·ChatRoom·참가 관계를 만들지 않는다.
- 사용자가 확인 카드에서 최종 조건과 상세 장소를 확인한 뒤에만 기존 Room 생성 command를 호출한다.
- 동일 확인 재시도는 같은 결과로 수렴해야 하며, 다른 요청이나 오래된 초안이 중복 Room을 만들지 않아야 한다. 구체 잠금·유일 제약·오류 코드는 [ADR-0069](../adr/room/0069-p2-ai-draft-confirmation-and-idempotent-room-command.md)을 따른다.
- `Idempotency-Key`의 저장·조회·유일성 범위는 최소 현재 인증 사용자(`currentUserId`)·확인 대상 draft/resource·operation으로 묶는다. 같은 범위의 재시도만 최초 Room 결과를 재생하고, 다른 사용자·draft·operation에서 같은 key를 재사용하면 Room을 반환하지 않고 `CONFIRMATION_CONFLICT` 또는 `FORBIDDEN`으로 끝낸다.

### 실패·복구

| 상태 | 의미 | 사용자 결과 |
| --- | --- | --- |
| `NEEDS_INPUT` | 액션에 필요한 조건이 부족함. `RECOMMEND`는 후보 조회 없이 검색 조건만 질문함 | 액션에 필요한 조건만 다시 질문 |
| `NO_CANDIDATES` | 유효한 `RECOMMEND` 검색 조건으로 후보를 조회했지만 결과가 없음 | 후보 없음과 검색 조건 수정 안내 |
| `CONSENT_REQUIRED` | 외부 AI 처리 동의가 없음 또는 철회됨 | 동의 없이는 AI 호출·활성 초안을 만들지 않음 |
| `AI_UNAVAILABLE` | timeout·429·schema 오류·provider 장애 | 성공 결과로 포장하지 않고 수동 흐름 또는 재시도 안내 |
| `CONFIRMATION_CONFLICT` | 만료·타인 접근·오래된 draft version·멱등키 범위 밖 재사용·멱등성 충돌 | Room을 만들지 않고 공개 오류 계약에 맞게 반환 |

실패 상태와 오류 코드는 공개 API 계약에 반영되기 전까지 논리적 기능 상태로 취급하며, 이 문서가 공개 HTTP 계약을 대신하지 않는다.

### 관측·보존

- 성공·추가 질문·거절·provider 실패·Room 생성 최종 결과와 허용된 provider/model/feature/tool label만 집계한다.
- prompt·응답 원문·Tool 인자·게임 후보·사용자 ID·세션·대화 이력·비밀값은 중앙 metric/log에 남기지 않는다.
- AI 사용량·추정 비용은 [OPS-04](monitoring.md#ops-04-ai-사용량과-추정-비용)의 공통 규칙과 승인된 [ADR-0068](../adr/platform/0068-p2-ai-provider-consent-and-operation-boundary.md)의 경계를 함께 따른다. 호출이 없는 기간을 비용 `0`의 증거로 기록하지 않는다.

## 기능 ID별 완료 기준

- `AI-01-AC1`: 로그인 사용자의 자연어 요청이 서버 검증 가능한 구조화 조건과 상태로 변환되고, 지원하지 않는 요청·모호한 조건·prompt injection이 안전하게 거절된다.
- `AI-01-AC2`: 조건이 유효하면 서버가 구조화 조건을 모두 AND로 적용하고 내부 `RANK-01` 순서로 정렬한 후보를 추천하며, 결과에 없는 게임·조건·업무 처리를 성공한 것처럼 주장하지 않는다.
- `AI-01-AC3`: 확인 전 Room·ChatRoom·참가 관계가 0개이며, 필요한 필드가 빠진 경우 `NEEDS_INPUT`으로 필요한 정보만 다시 요청한다.
- `AI-01-AC4`: 사용자의 명시적 확인 뒤 기존 Room 생성 command를 호출하고, 현재 사용자·대상 draft·operation 범위가 같은 동일 확인 재시도·동시 요청·오래된 draft가 정확히 하나의 결과로 수렴하며 범위 밖 key 재사용은 Room을 반환하지 않는다.
- `AI-01-AC5`: 동의 철회·provider 장애·timeout·quota 초과·schema 오류·Room 생성 실패가 각각 공개된 실패 상태로 끝나며 부분적인 Room·ChatRoom을 남기지 않는다.
- `AI-01-AC6`: 외부 처리 payload가 PII·secret 차단·allowlist와 provider no-retention·no-training 조건을 지키고, 보존·호출 한도·비용 상한·fake provider와 feature flag가 승인된 AI-D01 계약과 일치하며 실제 provider가 기본 테스트에서 호출되지 않는다.
- `AI-01-AC7`: 고정 fixture에서 추천·추가 질문·거절·실패·확인형 생성의 성공·실패 결과와 비용·지연·금지 데이터 부재를 [검증 설계의 비용·지연 판정 기준](assistant-load-test.md#비용지연-판정)에 따라 `PASS`·`FAIL`·`NOT_RUN`·`NO_OBSERVATION`·`INVALID`로 재현 가능하게 판정한다.

위 완료 기준의 구현·자동 검증·배포·실측 상태는 [P2 기능 상태](README.md#기능별-현재-상태)에서 각각 기록하며, 이 문서 등록만으로 어느 축도 완료로 표시하지 않는다.

## 정본 변경 지도

| 정본 | 현재 상태 | 다음 반영 |
| --- | --- | --- |
| [P2 기능 상태](README.md#기능별-현재-상태) | 이 문서와 `AI-01` 행 등록, AI-D01~D03·ADR 승인 완료 | API·ERD·아키텍처·구현·검증·배포·실측 축을 단계별 갱신 |
| [P2 공통 명세](../P2-spec.md) | AI-01 범위와 경계 링크 등록 | 공통 규칙과 충돌할 때 소유 문서 조정 |
| [API](../API.md) | AI-01 확인형 command·초안 API 목표 계약 반영 | 생산 Controller·DTO·오류 검증 구현 |
| [ERD](../ERD.md) | `ASSISTANT_CONSENTS`·`ASSISTANT_DRAFTS`·`ASSISTANT_IDEMPOTENCY_RECORDS` 목표 계약 반영 | Flyway·JPA·운영 데이터 사전 검사 구현 |
| [아키텍처](../ARCHITECTURE.md) | assistant module·port·provider adapter 경계 반영 | 모듈·port·transaction 구현 및 구조 검증 |
| [OPS-04](monitoring.md#ops-04-ai-사용량과-추정-비용) | 공통 관측 계약과 승인된 AI ADR | AI-01b 구현·배포 뒤 사용량·비용 관측 연결 |

## 구현·검증 게이트

1. [#794](https://github.com/bamsongi-club/albam-mate/issues/794)의 AI-01 범위·`DISCOVERY-01` 경계 문서화는 완료됐다.
2. [#795](https://github.com/bamsongi-club/albam-mate/issues/795)와 [#796](https://github.com/bamsongi-club/albam-mate/issues/796)의 결정과 [ADR-0068](../adr/platform/0068-p2-ai-provider-consent-and-operation-boundary.md)·[ADR-0069](../adr/room/0069-p2-ai-draft-confirmation-and-idempotent-room-command.md)·[ADR-0070](../adr/room/0070-p2-room-region-closed-set-and-compatibility.md)가 승인됐고, 공개 API·ERD·아키텍처 계약도 반영됐다. 이제 migration·구현 이슈의 대상 경로·테스트 계약·rollback을 고정하기 전에는 `AI-01a`~`AI-01d` 생산 코드를 시작하지 않는다.
3. 공개 계약과 구현 이슈가 고정되면 하위 슬라이스별 테스트 계약을 먼저 승인하고, fake provider·고정 fixture로 자동 검증한다. 확인 전 무부수효과, 확인 후 단일 Room·ChatRoom 원자성, 동일 key·동시 요청 수렴, 기존 수동 Room 생성·참가·채팅·CSRF 회귀를 함께 확인한다.
4. 실제 provider·운영 배포·부하 측정은 실행 권한과 환경·가격 snapshot·결과 보존 경계를 확인한 뒤 별도 증거로 남긴다. 문서·결정·계약·생산 코드·자동 검증·배포·실측은 [P2 기능 상태](README.md#기능별-현재-상태)에 별도 축으로 기록한다.

## 문서 관리

소유자 `알밤메이트 AI 모임 도우미 담당자` · 최종 검증일 `2026-08-18` · 결정 정본 `ADR-0068~0070` · 상세 검증 정본 `assistant-load-test.md`
