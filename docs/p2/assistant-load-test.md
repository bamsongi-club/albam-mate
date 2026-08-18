# AI 기능군 계약·부하 검증 설계

> **문서 상태: 설계 초안 · 실행하지 않음**
>
> 기능 ID: `AI-01`~`AI-03` · 기준 이슈: [#794](https://github.com/bamsongi-club/albam-mate/issues/794)

이 문서는 `AI-01`~`AI-03`의 계약·동시성·실패·비용 경계를 재현하기 위한 검증 설계다. `AI-04`의 배포 후 제한 실측은 이 문서가 아니라 `AI-04c`가 소유한다. 실제 provider 호출, AWS 부하, 운영 capacity나 품질 완료를 이 문서가 선언하지 않는다.

## 검증 원칙

- 기본 runner는 결정적 fake provider를 사용한다. 같은 입력·schema version·fixture hash는 같은 구조화 결과와 오류를 반환해야 한다.
- 실제 provider는 [ADR-0074](../adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md)의 실행 권한, model ID 확인, payload allowlist와 비용 상한 확인 뒤 별도 수동 smoke에서만 사용한다.
- 테스트는 `AI-01a`·`AI-01b`·`AI-02a`·`AI-03a`의 계약을 기능별로 분리해 판정하고, setup 실패·관측 누락·generator 포화는 기능 실패가 아닌 `INVALID`로 기록한다.
- prompt·응답·Tool 인자·게임 후보·사용자 ID·세션·비밀값을 결과 파일·metric label·central log에 남기지 않는다.

## 고정 fixture

아래 검증은 응답 문구가 아니라 `missingFields` 집합과 호출 trace를 assertion한다. `recommendationSearchFields`는 추천 검색 조건 집합 `{GAME_STYLE}`이고, `roomCreationFields`는 총 인원·시작 시각·지역·게임 선택에 대응하는 방 생성 필드 집합 `{PLAYER_COUNT, STARTS_AT, REGION, GAME}`이다. 두 집합은 공개 [`AssistantMissingField`](../API.md#ai-기능군-목표-enum)와 같은 값을 쓰며 서로 겹치지 않는다.

실행 fixture는 비식별 synthetic manifest `docs/p2/fixtures/ai-01/manifest.json`으로 고정한다. manifest version은 `AI-01-FIXTURE-V1`이고, 각 case는 `AI01-<COHORT>-<NNN>` 형식의 변경되지 않는 `caseId`를 갖는다. 모든 cohort는 독립 case를 최소 3개 포함하고, 동시성·멱등성·장애·공격·PII/secret cohort는 최소 5개를 포함한다. 각 case에는 cohort, synthetic structured input, 기대 action/status, 정확한 `missingFields` 집합, provider·candidate 조회 trace 기대값과 Room·ChatRoom·draft 부수효과 판정을 담는다.

manifest hash는 `manifestSha256` 필드를 제외한 전체 manifest를 JSON object key 오름차순·배열 caseId 오름차순·UTF-8·LF·공백 없는 canonical JSON bytes로 직렬화한 뒤 SHA-256으로 계산한다. 실행 결과에는 manifest version·path·hash·release SHA·provider/model·schema version을 함께 기록한다. caseId 누락·중복, canonical hash 불일치, 기대 trace·부수효과 자료 누락, fixture version 불일치 또는 결과 manifest와 실행 입력이 다르면 기능 성공이 아니라 `INVALID`다.

| cohort | 최소 시나리오 | 기대 상태 |
| --- | --- | --- |
| 추천 입력 없음 | `RECOMMEND`에 `게임 추천해줘`처럼 검색 조건이 없음 | `NEEDS_INPUT`; `missingFields == recommendationSearchFields`, `missingFields ∩ roomCreationFields == ∅`, 후보 조회 trace 0건, 활성 임시 초안 0개 |
| 추천 | 유효한 `RECOMMEND` 검색 조건 | `missingFields == ∅`, 후보 조회 trace 1건과 후보·적용 조건 반환, 활성 임시 초안·Room 0개 |
| 다회 입력 병합 | 1턴에서 게임 스타일 조건을 확보하고 2턴은 총 인원만 언급하며 직전 `conditions`를 되돌려 보냄 | 2턴 응답의 `categories`·`mechanisms`·`themes`가 1턴 값과 같고, 새로 언급한 필드만 대체되며, `conditions`를 생략한 요청은 이전 조건을 잇지 않음 |
| 방 생성 입력 부족 | `CREATE_ROOM`의 총 인원·시작 시각·지역·게임 선택 중 일부 누락 | `NEEDS_INPUT`; `missingFields`가 확인되지 않은 `roomCreationFields`와 정확히 일치하고 추천 검색 조건을 섞지 않으며 Room·ChatRoom을 만들지 않음 |
| 후보 없음 | 유효한 `RECOMMEND` 조건으로 조회했지만 결과가 없음 | `NO_CANDIDATES`; `missingFields == ∅`, 유효 조건의 후보 조회 trace 정확히 1건과 0건 결과, 활성 임시 초안·Room 0개 |
| 동의 | 동의 전·동의 후·철회 후 | 동의 전/철회 후 provider 호출 0건 |
| 확인 성공 | 유효한 새 초안과 유효한 confirm | 성공 응답, Room 정확히 1개, ChatRoom 정확히 1개 |
| 확인 재시도 | 같은 `currentUserId`·같은 draft/resource·같은 operation의 key·같은 draft version 재시도 | 최초 성공 응답·Room ID·ChatRoom ID를 그대로 반환하고 추가 행 0개, 멱등 record 1개 |
| 확인 충돌 | 다른 사용자·draft/resource·operation의 같은 key, 다른 key·오래된 version·만료 초안 | 새 Room·ChatRoom 0개, 범위 밖 key는 `CONFIRMATION_CONFLICT`, 계약된 오류 상태 |
| 장애 | fake timeout·429·schema 오류·Redis 불능·Room 실패 | `AI_UNAVAILABLE` 또는 fail-closed, 부분 상태 없음 |
| 공격 | prompt injection, 쓰기 tool 요청, 비로그인·잘못된 CSRF | tool allowlist 우회 0건, 공개 거절 |
| PII·secret | 전화번호·주소·연락처·자격증명·token이 포함된 synthetic 입력 | 승인된 마스킹 결과만 provider payload에 포함하거나 fail-closed, 원문 provider 전달·원문 보존 0건 |

구체 fixture 원문과 사용자 입력은 저장소에 원문으로 남기지 않으며, 테스트에서는 식별 가능한 안전한 synthetic data만 사용한다.

## 계약 검증

1. 인증·동의·CSRF·feature flag가 호출 경계에서 판정되는지 확인한다.
2. fake provider에 전달되는 필드가 allowlist와 schema version을 벗어나지 않는지, PII·secret 탐지 후 승인된 마스킹 또는 fail-closed가 적용되는지, provider no-retention·no-training 조건이 확인되는지 검사한다.
3. 모델이 반환한 구조화 조건을 서버가 다시 검증하고, 후보 조회가 모든 조건을 AND로 적용한 뒤 내부 `RANK-01` 순서로 정렬하며, 후보·지역·Room 쓰기 권한을 모델에 위임하지 않는지 확인한다.
4. 확인 전 Room·ChatRoom·참가 관계가 생성되지 않는지 확인한다.
5. 유효한 새 초안 confirm이 성공 응답과 Room 정확히 1개·ChatRoom 정확히 1개를 만들고, 두 ID가 같은 생성 결과를 가리키는지 확인한다.
6. 동일 `(currentUserId, draft/resource, operation)` 범위의 `Idempotency-Key`·draft version 재시도는 최초 성공 응답·Room ID·ChatRoom ID를 반환하고 새 Room·ChatRoom을 만들지 않는지 확인한다.
7. 다른 사용자·draft/resource·operation의 같은 key, 다른 key·오래된 version·만료 초안은 새 Room·ChatRoom을 0개 만들고 계약된 오류를 반환하는지 확인한다.
8. metric·log·provider payload·저장 결과에 허용 label과 승인된 마스킹 값만 남고 사용자 원문·PII·secret·금지 원문이 없는지 검사한다.
9. manifest version·hash·caseId·trace·부수효과 assertion이 실행 결과와 일치하는지 확인하고, 하나라도 누락되면 `INVALID`로 판정한다.

## 부하 시나리오

호출 제한값은 [ADR-0074](../adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md)의 사용자 KST 일 5회·월 150회·동시 1회·timeout 10초·retry 0을 사용한다. 10분 이동 창은 두지 않으며, 앱 전체 월 hard cap은 `$5`, 80% 알림 기준은 `$4`다. 초기 보장 규모는 사용자 40명·월 6,000회 계획으로 둔다. 이 정책값은 목표 SLO나 capacity 증거를 대신하지 않는다.

| 시나리오 | 목적 | 관찰값 |
| --- | --- | --- |
| 단일 정상 요청 | 기본 latency·상태 전이 | provider 호출 수, 구조화 성공, 응답 지연 |
| 사용자 한도 경계 | 5회/초과 요청의 원자 판정 | 허용·거절 수, quota 소비, 상태 부수효과 |
| 동시 요청 | 동시 1회와 초안/confirm 수렴 | 중복 초안·Room·provider call, lock wait |
| timeout·429 반복 | retry 없음·fail-closed | 오류 상태, latency, quota 소비, 부분 저장 |
| Redis 불능 | 비용·quota 저장소 장애 | AI 명령 거절, fallback 여부, 저장 부수효과 |
| 확인 재시도 | 네트워크 재전송 안전성 | Room·ChatRoom 개수, 동일 응답 수렴 |

## 비용·지연 판정

- 측정 경계는 인증·동의·quota 판정이 끝나 AI 명령이 수락된 시점부터 최종 상태 응답을 조합한 시점까지의 end-to-end latency와, provider adapter가 payload를 전송한 시점부터 구조화 응답 검증 또는 timeout까지의 provider latency로 나눈다. fixture 생성·setup·generator warm-up은 latency 표본에서 제외하고 provider 대기·schema 검증은 포함한다.
- 추정 비용은 고정된 provider/model 가격 snapshot의 input token 수·output token 수와 요청별 고정 과금을 각각 곱해 더한다. 가격 snapshot·usage·호출 trace 중 하나라도 없으면 비용 `0`으로 대체하지 않고 `NO_OBSERVATION` 또는 `INVALID`로 판정한다.
- cost cap은 [ADR-0074](../adr/platform/0074-p2-ai-provider-consent-and-operation-boundary.md)의 앱 전체 월 `$5`와 80% 알림 기준 `$4`를 사용한다. latency threshold는 구현 이슈의 테스트 계약에서 별도 승인하며, threshold·cap이 없거나 실제 provider 관측을 실행하지 않은 결과는 `NOT_RUN`이다.
- `PASS`는 manifest/hash·trace·부수효과가 완전하고, 승인된 latency threshold와 cost cap을 모두 만족하며 금지 데이터가 없는 경우에만 부여한다. 실행·관측 계약은 충족했지만 threshold 초과·cost cap 초과·금지 데이터가 확인되면 `FAIL`, manifest/hash·trace·측정 자료가 누락되면 `INVALID`로 판정한다. provider 호출·가격 snapshot·승인값이 없는 경우는 `NOT_RUN` 또는 `NO_OBSERVATION`이며 `AI-02-AC3`의 quota·비용 근거나 `AI-04-AC2`의 배포 후 관측 근거로 세지 않는다.

## 판정 기준

- 기능 판정은 기대 상태·부수효과·권한·금지 데이터 검사에 모두 통과해야 `PASS`다.
- provider 호출이 없던 환경의 비용은 `0`으로 판정하지 않고 `NOT_RUN` 또는 `NO_OBSERVATION`으로 기록한다.
- runner가 fixture를 읽지 못했거나 관측 파일이 없거나 generator가 목표 부하를 만들지 못하면 실행 무결성 실패 `INVALID`다.
- 실제 provider/model·release·fixture hash·schema version·실행 시각·환경을 결과에 기록하되 비밀값과 원문 입력은 기록하지 않는다.
- 결과는 성공·업무 거절·기술 실패·복구·관측 공백을 분리해 `AI-01`~`AI-03` 기능 상태와 `OPS-04` 증거에 연결한다. 배포 후 제한 실측은 `AI-04c`에 별도 연결한다.

## 실행 게이트

이 설계의 정책 전제는 완료된 [#795](https://github.com/bamsongi-club/albam-mate/issues/795)·[#796](https://github.com/bamsongi-club/albam-mate/issues/796)와 승인된 ADR-0074~0076으로 확정됐다. 다만 구현 이슈가 runner 경로·cwd·shell·시간 측정·결과 경로를 고정하기 전에는 실행 계약으로 승격하지 않는다. 이번 전달 범위에서는 자동 부하테스트 runner를 실행하지 않으며, 필요한 경우 별도 승인 뒤 fake provider 계약 검증과 부하 실행을 재개한다.
