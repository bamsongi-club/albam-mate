# ADR-0066: P2 AI provider·동의·운영 경계 고정

- 상태: 승인됨
- 작성일: 2026-08-17
- 결정일: 2026-08-17
- 관련: [#794](https://github.com/bamsongi-club/albam-mate/issues/794), [#795](https://github.com/bamsongi-club/albam-mate/issues/795), [AI-01 명세](../../p2/assistant.md), [OPS-04](../../p2/monitoring.md#ops-04-ai-사용량과-추정-비용), [ADR-0019](../game/0019-bgg-full-catalog-staged-enrichment.md)
- 대체 대상: 없음
- 후속 ADR: 없음

## 맥락

`AI-01`은 로그인 사용자의 자연어를 서버가 검증 가능한 게임·방 조건으로 구조화한다. 외부 모델은 후보 조회나 Room 쓰기 권한을 가져서는 안 되며, provider로 전송되는 데이터와 보존·비용·장애 경계도 구현 전에 고정해야 한다.

현재 저장소에는 AI provider dependency, `assistant` 모듈과 `infra.ai` adapter가 없다. 또한 [ADR-0019](../game/0019-bgg-full-catalog-staged-enrichment.md)는 BGG 원문과 승인되지 않은 catalog 데이터를 외부 LLM으로 보내지 않는 경계를 둔다.

판단 기준은 다음과 같다.

- 외부 provider 의존성을 `game`·`room` 도메인과 격리한다.
- 모델이 업무 권한·게임 식별자·Room 상태를 소유하지 않게 한다.
- PII·secret·BGG 원문·대화 원문을 최소화하고 철회 가능하게 한다.
- 기본 테스트가 실제 provider·비용·운영 장애에 의존하지 않게 한다.
- 호출량·비용·provider 장애를 fail-closed로 제어하고 `OPS-04`와 소유 경계를 분리한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| provider SDK를 `game`·`room` 서비스에 직접 주입 | 초기 연결이 단순함 | 모듈 경계와 fake test가 무너지고 provider가 업무 권한에 접근하기 쉬움 | 제외 |
| 모델에 게임·방 API를 여러 tool로 직접 호출하게 함 | 대화 흐름을 모델이 조합할 수 있음 | 반복 호출·권한 위임·비용·감사 경계가 커짐 | 제외 |
| BGG catalog/RAG 원문을 provider에 전달 | 모델의 검색 문맥이 풍부함 | ADR-0019의 외부 전송 금지 및 provenance 경계와 충돌 | 제외 |
| 단일 port와 제한된 구조화 tool을 fake 기본값으로 사용 | 경계·검증·교체가 명확하고 비용을 통제할 수 있음 | 서버가 후보 조회와 Room command를 별도로 연결해야 함 | 선택 |

## 결정

### provider와 호출 경계

- provider는 OpenAI로 하고 Java 통합 버전은 Spring AI `2.0.0-M7`로 고정한다.
- 애플리케이션과 provider 사이에는 `assistant.contract.AssistantIntentExtractor` 하나의 port만 둔다. provider SDK adapter는 `infra.ai`에 둔다.
- 실제 model ID는 ADR에 고정하지 않는다. `local-openai`를 활성화하기 전에 배포 계정으로 `GET /v1/models/{modelId}`를 확인하고, 확인에 실패하면 다른 모델로 조용히 대체하지 않고 이 결정을 재승인한다.
- provider에는 버전이 지정된 instruction, 강제 schema, 기준 시각 `Asia/Seoul`, 현재 사용자 문장과 서버가 식별한 누락 필드만 allowlist로 전달한다. PII·secret은 호출 전에 탐지해 승인된 방식으로 마스킹하고 안전하게 처리할 수 없으면 호출을 거절한다.
- 모델은 `propose_game_room_intent` 하나만 강제 호출한다. 병렬 tool 호출·자동 tool loop·게임 검색·Room 쓰기·임의 SQL/DSL 실행 권한은 제공하지 않는다.
- 게임 후보는 서버가 `game.contract`를 통해 조회하고, AI가 반환한 식별자·조건은 신뢰하지 않는 구조화 입력으로 다시 검증한다. BGG 원문·게임 후보·prompt hash를 provider에 보내지 않는다.

### 동의·보존·관측

- 첫 AI 사용 전에 외부 처리 동의를 받고 동의·철회 상태를 저장한다. 철회 뒤에는 새 AI 호출과 활성 초안 생성을 막는다.
- OpenAI 요청은 `store=false`를 사용한다. raw prompt, 모델 원문 응답, 대화 이력, prompt hash, BGG 텍스트, 게임 후보, 사용자 ID, 세션과 secret을 앱 저장소·metric·central log에 보존하지 않는다.
- 동의문은 외부 처리자, 전송 데이터 범주, `store=false`가 provider 보존 정책을 대신하지 않는다는 점, 검토한 provider 정책의 버전·URL을 포함한다. 정책 버전·URL을 확인할 수 없거나 no-retention·no-training을 보장하지 못하는 provider는 호출하지 않는다.
- AI 호출·비용 정책의 소유자는 이 ADR이며 `OPS-04`는 허용된 label·가격 snapshot·알림·운영 증거를 참조한다. 비용 알림의 실제 수신자와 경로는 `OPS-04` 운영 경로를 사용하며 AI 기능이 별도 비밀값이나 채널을 저장하지 않는다.

### 기본 실행·제한·비용

- 기본 profile과 CI는 결정적 fake provider를 사용한다. 실제 provider는 `local-openai` 수동 smoke에서만 호출한다.
- `app.assistant.enabled=false`를 기본값으로 둔다. 외부 provider 상태는 readiness/liveness 판정에 넣지 않는다.
- 호출 한도는 사용자당 10분 5회, KST 기준 일 30회, 동시 1회, timeout 8초, retry 0으로 한다. timeout·429·schema 오류도 한도에 포함한다.
- Redis 원자 예약으로 앱 월 비용 hard cap `$10`과 80% 알림을 관리한다. Redis가 불능이면 AI 명령은 fail-closed 한다.

## 결과

- 얻는 것:
  - `assistant → infra.ai`와 `assistant → game.contract`·`room.contract` 경계가 분리된다.
  - 실제 provider가 기본 테스트·기본 profile·health 판정에 끼어들지 않는다.
  - 동의 철회, payload 최소화, 보존 금지, quota와 비용 상한을 구현·검증 계약으로 연결할 수 있다.
- 감수할 비용·위험:
  - provider 장애와 quota 초과는 AI 성공 대신 fail-closed 또는 명시적 실패 상태가 된다.
  - 모델 ID와 provider 정책 변경은 enablement 전 재검증이 필요하다.
  - 비용 알림 전달의 운영 증거는 `OPS-04` 구현·배포에서 별도로 남겨야 한다.
- 후속 작업:
  - `AI-01b`에서 dependency·설정·Redis key·fake provider·quota·오류 계약을 구현한다.
  - `OPS-04`에 provider/model/feature label, 가격 snapshot, cap 알림과 보존 금지 검증을 연결한다.

## 보류 및 재검토

- 지금 하지 않는 것: 특정 model ID의 영구 고정, provider 원문 저장, RAG/BGG 원문 전송, 실제 provider 상시 호출, 운영 비용·용량 완료 판정.
- 보류 이유: 배포 계정의 model 가용성, provider 정책과 운영 증거는 구현·배포 시점에 확인해야 하며 현재 ADR은 경계와 fail-closed 규칙만 소유한다.
- 다시 검토할 조건: provider·정책·가격표가 바뀌거나, no-retention/no-training 보장이 사라지거나, 승인된 catalog 범위가 바뀌는 경우.

## 참고 자료

- [AI-01 명세](../../p2/assistant.md)
- [AI-D01 결정 이슈 #795](https://github.com/bamsongi-club/albam-mate/issues/795)
- [OPS-04 AI 사용량·추정 비용](../../p2/monitoring.md#ops-04-ai-사용량과-추정-비용)
- [ADR-0019 BGG full catalog 경계](../game/0019-bgg-full-catalog-staged-enrichment.md)

## 검증

- 상태: 미검증
- 근거: 계약 — [#795](https://github.com/bamsongi-club/albam-mate/issues/795)의 승인된 우선안과 `AI-01` 명세의 provider·보존·비용 경계를 반영함.
- 미검증:
  - provider model ID·정책 URL·실제 비용 알림 경로 확인
  - dependency·설정·fake provider·quota·Redis fail-closed 구현과 테스트
