# AI-01 부하·계약 검증 설계

> **문서 상태: 설계 초안 · 실행하지 않음**
>
> 기능 ID: `AI-01` · 기준 이슈: [#794](https://github.com/bamsongi-club/albam-mate/issues/794)

이 문서는 AI-01의 계약·동시성·실패·비용 경계를 재현하기 위한 검증 설계다. 실제 provider 호출, AWS 부하, 운영 capacity나 품질 완료를 이 문서가 선언하지 않는다.

## 검증 원칙

- 기본 runner는 결정적 fake provider를 사용한다. 같은 입력·schema version·fixture hash는 같은 구조화 결과와 오류를 반환해야 한다.
- 실제 provider는 [AI-D01](assistant-decision-draft.md#ai-d01-외부-ai-처리동의provider-운영-경계) 승인, 실행 권한, model ID 확인, payload allowlist와 비용 상한 확인 뒤 별도 수동 smoke에서만 사용한다.
- 테스트는 `AI-01a`~`AI-01d`의 계약을 분리해 판정하고, setup 실패·관측 누락·generator 포화는 기능 실패가 아닌 `INVALID`로 기록한다.
- prompt·응답·Tool 인자·게임 후보·사용자 ID·세션·비밀값을 결과 파일·metric label·central log에 남기지 않는다.

## 고정 fixture

| cohort | 최소 시나리오 | 기대 상태 |
| --- | --- | --- |
| 추천 | 유효한 게임·인원·시간 조건 | 후보와 적용 조건만 반환 |
| 입력 부족 | 게임·인원·시간·지역 중 일부 누락 | `NEEDS_INPUT`, 필요한 필드만 질문 |
| 후보 없음 | 유효하지만 결과가 없는 조건 | `NO_CANDIDATES`, 성공 결과를 만들지 않음 |
| 동의 | 동의 전·동의 후·철회 후 | 동의 전/철회 후 provider 호출 0건 |
| 확인 성공 | 유효한 새 초안과 유효한 confirm | 성공 응답, Room 정확히 1개, ChatRoom 정확히 1개 |
| 확인 재시도 | 같은 key·같은 draft version 재시도 | 최초 성공 응답·Room ID·ChatRoom ID를 그대로 반환하고 추가 행 0개 |
| 확인 충돌 | 다른 key·오래된 version·만료 초안 | 새 Room·ChatRoom 0개, 계약된 오류 상태 |
| 장애 | fake timeout·429·schema 오류·Redis 불능·Room 실패 | `AI_UNAVAILABLE` 또는 fail-closed, 부분 상태 없음 |
| 공격 | prompt injection, 쓰기 tool 요청, 비로그인·잘못된 CSRF | tool allowlist 우회 0건, 공개 거절 |

구체 fixture 원문과 사용자 입력은 저장소에 원문으로 남기지 않으며, 테스트에서는 식별 가능한 안전한 synthetic data만 사용한다.

## 계약 검증

1. 인증·동의·CSRF·feature flag가 호출 경계에서 판정되는지 확인한다.
2. fake provider에 전달되는 필드가 allowlist와 schema version을 벗어나지 않는지 확인한다.
3. 모델이 반환한 구조화 조건을 서버가 다시 검증하고, 후보·지역·Room 쓰기 권한을 모델에 위임하지 않는지 확인한다.
4. 확인 전 Room·ChatRoom·참가 관계가 생성되지 않는지 확인한다.
5. 유효한 새 초안 confirm이 성공 응답과 Room 정확히 1개·ChatRoom 정확히 1개를 만들고, 두 ID가 같은 생성 결과를 가리키는지 확인한다.
6. 동일 `Idempotency-Key`·draft version 재시도는 최초 성공 응답·Room ID·ChatRoom ID를 반환하고 새 Room·ChatRoom을 만들지 않는지 확인한다.
7. 다른 key·오래된 version·만료 초안은 새 Room·ChatRoom을 0개 만들고 계약된 오류를 반환하는지 확인한다.
8. metric·log에 허용 label만 남고 금지 원문이 없는지 검사한다.

## 부하 시나리오

부하값은 AI-D01 승인 뒤 확정한다. 초안에서 제안한 경계는 사용자 10분 5회·KST 일 30회·동시 1회·timeout 8초·retry 0이며, 승인 전에는 목표 SLO나 capacity 증거로 사용하지 않는다.

| 시나리오 | 목적 | 관찰값 |
| --- | --- | --- |
| 단일 정상 요청 | 기본 latency·상태 전이 | provider 호출 수, 구조화 성공, 응답 지연 |
| 사용자 한도 경계 | 5회/초과 요청의 원자 판정 | 허용·거절 수, quota 소비, 상태 부수효과 |
| 동시 요청 | 동시 1회와 초안/confirm 수렴 | 중복 초안·Room·provider call, lock wait |
| timeout·429 반복 | retry 없음·fail-closed | 오류 상태, latency, quota 소비, 부분 저장 |
| Redis 불능 | 비용·quota 저장소 장애 | AI 명령 거절, fallback 여부, 저장 부수효과 |
| 확인 재시도 | 네트워크 재전송 안전성 | Room·ChatRoom 개수, 동일 응답 수렴 |

## 판정 기준

- 기능 판정은 기대 상태·부수효과·권한·금지 데이터 검사에 모두 통과해야 `PASS`다.
- provider 호출이 없던 환경의 비용은 `0`으로 판정하지 않고 `NOT_RUN` 또는 `NO_OBSERVATION`으로 기록한다.
- runner가 fixture를 읽지 못했거나 관측 파일이 없거나 generator가 목표 부하를 만들지 못하면 실행 무결성 실패 `INVALID`다.
- 실제 provider/model·release·fixture hash·schema version·실행 시각·환경을 결과에 기록하되 비밀값과 원문 입력은 기록하지 않는다.
- 결과는 성공·업무 거절·기술 실패·복구·관측 공백을 분리해 `AI-01` 기능 상태와 `OPS-04` 증거에 연결한다.

## 실행 게이트

이 설계는 [#795](https://github.com/bamsongi-club/albam-mate/issues/795)와 [#796](https://github.com/bamsongi-club/albam-mate/issues/796)의 승인 전에는 실행 계약으로 승격하지 않는다. 승인 뒤 구현 이슈가 runner 경로·cwd·shell·시간 측정·결과 경로를 고정하고, 먼저 fake provider 계약 검증을 통과한 뒤 필요한 환경에서 부하를 실행한다.
