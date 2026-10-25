# ADR-0094: P2 AI 사용자 quota와 월 비용 안전장치를 실제 사용량에 맞춤

- 상태: 승인됨
- 작성일: 2026-09-26
- 결정일: 2026-09-26
- 관련: [#1111](https://github.com/bamsongi-club/albam-mate/issues/1111), [ADR-0085](0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md), [ADR-0093](0093-p2-ai-token-cost-reservation-and-ledger.md), [AI 기능 명세](../../p2/assistant.md), [API](../../API.md), [AI 검증 설계](../../p2/assistant-load-test.md), [OPS-04](../../p2/monitoring.md#ops-04-ai-사용량과-추정-비용)
- 대체 대상: [ADR-0085](0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)의 사용자별 일일 provider 호출 한도 10회, [ADR-0093](0093-p2-ai-token-cost-reservation-and-ledger.md)의 일반 실패 시 호출 quota 소비 규칙
- 후속 ADR: 없음

## 맥락

현재 로그인된 앱 사용자는 서버가 확인한 인증 정보로 식별할 수 있다. 그런데 사용자별 일일 provider 호출 한도가 10회이고, 호출 실패도 모두 소비로 처리하면 사용자가 실제로 처리한 호출량과 허용량이 맞지 않을 수 있다. 응답 연결이 끊겨 provider가 요청을 처리했는지 알 수 없는 경우에는 반대로 한도를 즉시 돌려주면 반복 재시도로 비용 상한을 우회할 수 있다.

ADR-0093은 provider 응답 token과 release 가격표로 계산한 앱 내부 예상 비용을 예약·정산한다. 이는 요청 시점의 상한 통제이고 provider 청구액 자체는 아니다. 캐시 입력 token을 일반 입력 단가로만 계산하면 캐시 적중에 따른 가격 차이를 반영하지 못한다. 또한 OpenAI Project 지출 상한은 그 project의 전체 사용량을 막는 별도 안전장치이며 앱의 사용자 quota나 Redis 예상액을 대체하지 않는다.

판단 기준은 사용자별 호출 수와 앱 내부 비용을 분리하고, 인증 주체·provider usage·가격 적용을 보수적으로 처리하며, 앱 내부 상한과 provider project 상한의 의미를 혼동하지 않는 것이다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 현재 일 10회와 실패 시 quota 소비 유지 | 코드 변경이 작음 | 사용자가 확정적으로 사용하지 않은 실패까지 quota를 소모하고, 원래 합의한 일 5회와 다름 | 제외 |
| OpenAI Project 상한만 사용 | provider가 project 지출을 직접 제한 | 사용자별 요청 통제는 없고, project 내 다른 사용량과 합쳐지며 전파 시차로 설정액을 조금 넘을 수 있음 | 제외 |
| Redis 사용자 호출 quota·token 예상액과 전용 OpenAI Project 상한을 함께 적용 | 사용자 호출량과 앱 요청 비용을 분리 통제하고 provider 측 안전장치도 둠 | 두 월 경계·계산값이 완전히 일치하지 않으며 운영 대조가 필요 | 선택 |
| 사용자마다 월 `$5` 비용 상한 또는 매 요청마다 OpenAI Costs 조회 | 개인별 예산 또는 provider 보고액에 가까운 조회 가능 | 사용자별 비용 상한은 합의 범위가 아니며, 관리 API를 요청 경로에 넣으면 지연·권한·집계 시차 의존성이 생김 | 제외 |

## 결정

### 사용자 식별과 호출 quota

- quota 주체는 요청을 인증한 서버 측 앱 사용자다. 인증되지 않았거나 사용자 식별이 유효하지 않으면 provider 호출을 시작하지 않는다. 요청 본문이나 헤더가 전달한 사용자 ID는 quota 주체로 신뢰하지 않는다.
- 실제 외부 provider 호출 경로에 한해 사용자별 KST 일 최대 5회, KST 월 최대 150회, 동시 호출 1회를 적용한다. 일 quota는 매일 00:00 KST, 월 quota와 앱 비용 ledger는 매월 1일 00:00 KST에 초기화한다. timeout 10초와 retry 0은 유지한다.
- provider 호출 전에 사용자 quota slot과 앱 비용 예약을 함께 확보한다. provider 응답에서 usage가 확인된 성공 또는 실패는 확인된 호출로 quota를 소비하고 비용을 usage로 정산한다. provider가 처리하지 않았고 usage가 없다는 것이 확정된 일반 실패는 호출 slot과 비용 예약을 반환한다.
- 연결 중단·timeout·응답 누락 등 provider 처리 여부나 token usage가 불명확한 요청은 최대 비용 예약을 KST 월말까지 앱 비용 합계에 포함한다. 동시 호출 slot은 별도로 2분 후 만료되며, 확정된 미처리 실패로 확인되지 않는 한 예약액을 0으로 처리하거나 비용 한도에서 반환하지 않는다.
- 유일한 정규화 정식 게임명에 대한 `game.contract` 직접 조회는 계속 provider·quota·비용 예약 없이 처리한다.

### 앱 내부 예상 비용

- Redis ledger는 전역 앱 기준 KST 월 예상 비용 상한 `$5`, warning `$4`를 유지한다. 상한에는 확정 정산액과 진행 중인 최대 비용 예약을 함께 포함한다.
- provider 호출 전에 설정된 최대 input/output token과 호출 시점에 적용되는 유효 가격 snapshot으로 최대 비용을 예약한다. usage가 확인되면 provider token 분류와 그 시점 가격 snapshot으로 USD micro 단위 예상액을 정산한다. 가격 snapshot에 필요한 token 가격이 없거나 유효하지 않으면 provider 경로를 활성화하지 않는다.
- provider가 cache-hit input token을 구분해 제공하면 해당 model의 cached-input 단가를 적용한다. cache 상세가 없거나 제공되지 않으면 전체 input을 일반 input 단가로 계산한다. provider가 별도 가격의 cache-write token을 보고하는 경우에는 snapshot의 해당 단가를 사용하며, 필요한 단가가 없으면 임의로 낮은 가격을 적용하지 않는다.
- usage가 포함된 실패 응답도 확인된 usage로 정산한다. 예약보다 실제 추정액이 크면 이미 수행된 호출의 비용을 모두 기록하고, 이후 호출을 새 합계에 따라 차단한다.

### OpenAI Project 지출 상한과 대조

- 운영 선행 조건은 assistant 전용 OpenAI Project에 월 `$5` hard spend limit을 설정하는 것이다. 이 한도는 해당 project의 전체 provider 사용량에 적용되는 안전장치이며 사용자별 한도가 아니다. 같은 project에서 발생한 다른 요청도 지출에 포함된다.
- OpenAI Project 상한과 앱 Redis 상한은 서로 독립적이다. Redis의 `$5`는 앱 호출의 token 가격 기반 예상액이고, OpenAI Project의 `$5`는 provider가 추적하는 project 지출이다. 서로 다른 집계 기준과 월 경계를 가질 수 있으며 provider 한도 적용은 즉시가 아니어서 설정액을 소폭 초과할 수 있다.
- 요청 경로에서 OpenAI Usage/Costs 관리 API를 동기 조회하지 않는다. OpenAI Usage activity와 Cost data는 사후 대조 자료로만 사용하고, provider 청구액을 Redis ledger에 자동 덮어쓰지 않는다.

## 결과

- 얻는 것:
  - 사용자별 일 5회·월 150회 요청 제한과 앱 전체 월 예상 비용 상한을 각 목적에 맞게 적용한다.
  - cache usage가 확인되면 적용 가능한 token 가격으로 비용 추정 정확도를 높인다.
  - provider Project 한도까지 두어 앱 외부의 project 사용을 포함하는 추가 안전장치를 둔다.
- 감수할 비용·위험:
  - usage가 불명확한 호출의 최대 예약액은 KST 월말까지 앱 비용 상한을 점유할 수 있어, 실제 청구보다 보수적으로 계산되어 월중 다른 호출을 차단할 수 있다. 동시 호출 slot은 2분 뒤 풀린다.
  - 내부 예상액과 OpenAI Project 지출은 집계 경계·가격·시점 차이로 일치하지 않을 수 있으며 Project 상한도 소폭 초과될 수 있다.
  - Project를 다른 용도로 공유하면 앱이 아닌 사용량이 provider 상한을 먼저 소진할 수 있다.
- 후속 작업:
  - #1111에서 Redis quota·예약·usage 정산·가격 분류 구현과 승인된 T-ID 검증을 마친다.
  - 배포 전에 전용 OpenAI Project의 월 `$5` limit 설정을 확인하고 운영 절차에 기록한다.
  - OPS-04에서 앱 ledger, OpenAI Usage activity, Cost data, project spend limit을 구분해 관측·대조한다.

## 보류 및 재검토

- 지금 하지 않는 것: 사용자별 금액 `$5` 한도, 요청 경로의 OpenAI Usage/Costs 조회, provider 비용과 Redis 예상액의 자동 동기화, UI 변경.
- 보류 이유: 결정한 `$5` 금액 상한은 앱 전체와 provider 전용 Project에 각각 적용한다. provider 관리 API는 요청 quota 판정의 동기 의존성으로 추가하지 않는다.
- 다시 검토할 조건: 실제 사용량 대조에서 지속적 과소 추정이 확인되거나, provider usage token 분류·가격 모델 또는 Project spend-limit 동작이 변경되는 경우.

## 참고 자료

- [OpenAI API spend limits](https://developers.openai.com/api/docs/guides/spend-limits)
- [OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)

## 검증

- 상태: 부분 검증
- 근거: 계약 및 `#1111` T1~T5 로컬 자동 검증
  - 정책 결정과 후속 구현 범위를 [#1111](https://github.com/bamsongi-club/albam-mate/issues/1111)에 연결했다.
- 자동 검증:
  - 인증 사용자 기준 일 5회·월 150회, 동시성, 앱 공통 월 비용 상한, 실패별 slot 처리, usage 가격 분류를 H2 및 Redis/PostgreSQL 통합 테스트로 확인했다.
  - 사용자 결정: usage가 불명확한 호출의 비용 예약은 KST 월말까지 유지하고, 동시 호출 slot은 2분 뒤 만료한다.
  - `AiQuotaRedisPostgresTest.T4_미확정_비용예약은_동시성_TTL_후에도_당월한도에_남고_다음_KST_월에_초기화된다`로 동시 호출 TTL 만료 후 당월 예약 유지와 다음 KST 월 한도 전환을 확인했다.
- 미검증:
  - OpenAI 전용 Project `$5` limit의 실제 설정·동작 확인
  - 배포 환경의 OpenAI 사용량·Costs 대조 및 운영 측정

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
