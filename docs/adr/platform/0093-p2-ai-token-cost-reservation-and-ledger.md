# ADR-0093: P2 AI 비용 상한을 token 기반 예상 비용으로 정산

- 상태: 승인됨
- 작성일: 2026-09-22
- 결정일: 2026-09-22
- 관련: [#1110](https://github.com/bamsongi-club/albam-mate/issues/1110), [ADR-0085](0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md), [OPS-04](../../p2/monitoring.md#ops-04-ai-사용량과-추정-비용), [AI 검증 설계](../../p2/assistant-load-test.md)
- 대체 대상: [ADR-0085](0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)의 provider 호출당 고정 비용 예약·정산과 월 비용 상한 계산 범위
- 후속 ADR: [ADR-0094](0094-p2-ai-user-quota-and-provider-cost-guardrails.md) (사용자 일 quota·실패 시 quota 반환·cache token 가격 분류·OpenAI Project 상한 경계 보완)

## 맥락

ADR-0085는 사용자 요청 quota와 앱 전체 비용 상한을 함께 두고, 외부 provider 호출마다 USD `0.10`을 예약해 월 `$5` 예산을 계산한다. provider adapter는 응답의 input/output token을 읽지만, 비용은 해당 token과 가격 snapshot으로 계산하지 않는다. Redis ledger도 예약 시 비용을 월 합계에 더한 뒤 completion에서 금액 차이를 조정하지 않는다. 따라서 같은 요청의 token 사용량이 달라도 비용 상한에 반영되는 금액은 같다.

호출량·동시성을 막는 요청 quota와 비용을 제한하는 월 예산은 서로 다른 문제다. 고정 예약은 단순하고 보수적이지만 실제 token 사용량과 무관하게 호출 수로 비용 한도를 소진한다. 반대로 응답 뒤에만 비용을 반영하면 호출 중인 요청의 비용을 상한 판정에서 빠뜨릴 수 있다. provider 응답의 usage가 없는 timeout·연결 실패는 실제 처리 여부도 확정할 수 없다.

이 결정에서 “token 기반 비용”은 설정된 모델 가격 snapshot과 provider 응답 token 수로 계산하는 **앱 내부 예상 비용**이다. OpenAI Usage의 activity와 Cost data는 별도 범위로 집계되며, 화면의 프로젝트·기간 필터를 맞춰도 앱 호출만의 귀속이나 청구 확정액으로 곧바로 등치하지 않는다. 실제 월별 대조에는 OpenAI의 Cost data export와 앱 내부 기록의 프로젝트·기간·모델 범위를 맞춘다.

판단 기준은 다음과 같다.

- 요청 횟수·동시성 제한과 월 비용 판정을 분리한다.
- 비용 상한 판정은 응답 전에도 진행 중 최대 사용량을 포함한다.
- 사용량이 확인된 호출은 token과 적용 가격에 따라 정산하고, 사용량을 확인할 수 없는 호출은 과소 계상하지 않는다.
- Redis 정산은 다중 인스턴스에서도 원자적·멱등적이어야 하며 기존 월별 누적 예약을 초기화하지 않는다.
- 내부 예상 비용, OpenAI Usage activity, OpenAI Cost data의 의미를 구분한다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 호출당 USD `0.10` 고정 예약 유지 | 구현과 상한 판정이 단순하고 예측 가능 | 짧고 저렴한 호출도 같은 금액을 소진하며 token 사용량을 비용 정책에 반영하지 못함 | 제외 |
| provider 응답 뒤 실제 추정액만 합산 | 관측된 token과 예산 값이 가깝다 | 진행 중 호출이 상한에서 빠지고, timeout·usage 누락에서 과소 계상될 수 있음 | 제외 |
| 최대 token 비용을 먼저 예약하고 응답 usage로 정산 | 진행 중 비용을 상한에 포함하면서 실제 usage에 따라 초과 예약분을 되돌림 | 가격 snapshot·정밀도·Redis 원자 정산·불확실 결과 보수 처리가 필요 | 선택 |
| 요청마다 OpenAI Costs API를 조회 | provider의 비용 보고를 직접 참조할 수 있음 | 요청 동기 경로에 외부 관리 API·권한·지연·집계 시차를 결합함 | 제외 |

## 결정

### 요청 quota와 비용 한도

- ADR-0085의 사용자별 KST 일 10회·월 150회·동시 1회, timeout 10초·retry 0은 유지한다. 사용자 요청 quota는 실제 provider 호출 경로에 들어간 요청 수를 제한하며 비용 계산 단위로 사용하지 않는다.
- 앱 전체 KST 월 `$4` warning과 `$5` 비용 상한을 유지하되, 이 값은 provider 청구액이 아닌 내부 예상 비용과 미완료 예약의 합계에 적용한다.
- provider 호출 전 설정된 `maxInputTokens`, `maxOutputTokens`와 해당 release의 input/output token 가격 snapshot으로 최대 예상 비용을 계산해 예약한다. 가격·token 상한이 없거나 유효하지 않으면 외부 provider 경로를 활성화하지 않는다.
- 예약 시점의 합계와 새 최대 비용 예약의 합이 `$5`를 초과하면 provider를 호출하지 않는다. `$4` 이상이면 월별 warning을 한 번 발행한다.

### token 사용량 정산

- provider 응답에서 usage를 확인하면 `inputTokens × inputPrice + outputTokens × outputPrice`를 USD로 계산하고 micro-USD 단위(USD `0.000001`)로 올림한다. Redis Lua는 예약액을 이 예상 사용액으로 원자적으로 대체하고, 이미 완료한 reservation token의 재시도는 금액을 다시 더하거나 빼지 않는다.
- 정산된 예상 사용액이 예약보다 작으면 차액을 풀어 이후 요청이 사용할 수 있게 한다. 실제 usage 비용이 예약액보다 크면 이미 발생한 호출 비용을 전부 기록하고, 그 결과가 월 상한을 넘더라도 취소하지 않는다. 이후 새 provider 호출은 상한 판정에 따라 차단한다.
- usage가 없거나 timeout 등으로 provider 전달·처리 여부를 알 수 없으면 최대 비용 예약을 미확정 비용으로 유지한다. 자동 환불하지 않는다. usage가 포함된 provider 오류 응답은 확인된 token 예상 비용으로 정산한다.
- 동의·입력 검증·feature gate 등 provider 예약 전에 거절된 요청은 비용을 예약하지 않는다. 예약 이후 provider 실패는 요청 quota를 소비하며, usage 미확정이면 예약 비용도 유지한다.
- 새 micro-USD 값과 함께 기존 cent 단위 월 비용을 보수적으로 이어받는다. 새 코드의 cent 단위 호환 mirror는 이전 버전의 quota 코드가 비용을 낮게 읽지 않도록 유지한다. 이전 버전으로 rollback하면 mirror로 인해 새 요청 비용이 실제보다 높게 보일 수 있으나 상한을 초과 이용하는 방향의 손실은 방지한다. 새 버전에서는 호환 mirror를 중복 집계하지 않는다.

### provider 청구 대조

- 내부 추정액은 호출별 즉시 상한을 위한 값이며, provider의 invoice 확정액과 일치한다고 보장하지 않는다. 모델 가격 변경, cached/reasoning token, 할인, 서비스 tier, 반올림과 provider 집계 조건에 따라 차이가 날 수 있다.
- 주기적 대조는 OpenAI Usage의 activity/token 자료와 Cost data를 구분한다. 조직·프로젝트·모델·기간과 timezone을 맞추고, Cost data 집계가 완료된 보고 구간을 비교한다. 이 결정은 OpenAI 계정 데이터나 Cost API를 실시간으로 읽거나 저장하지 않는다.
- 사용량이 없는 관측 기간은 실제 호출이 없다는 근거가 없으면 비용 `0`으로 해석하지 않는다. OpenAI Costs와 내부 예상액 간 차이는 재검토 자료로 기록하고 자동으로 Redis 비용 ledger를 덮어쓰지 않는다.

## 결과

- 얻는 것:
  - 고정 호출 수 대신 관측한 input/output token에 비례해 월 내부 예상 비용을 계산한다.
  - 진행 중 요청의 최대 예상 비용은 예약하고, 확인된 usage로 차액을 다시 사용할 수 있다.
  - 기존 요청 한도, 월 비용 상한, OpenAI의 provider 보고 수치를 서로 다른 의미로 설명한다.
- 감수할 비용·위험:
  - 내부 예상액은 실제 provider 비용과 다를 수 있고, usage 불명확 실패의 보수 예약은 월 예산을 과대 계상할 수 있다.
  - 가격 snapshot 또는 token 상한이 부정확하면 비용 상한을 과소·과대 추정할 수 있다.
  - 이전 버전 rollback 경로에서는 cent 호환 mirror 때문에 비용이 보수적으로 높게 계산될 수 있다.
- 후속 작업:
  - [#1110](https://github.com/bamsongi-club/albam-mate/issues/1110)의 문서·Redis ledger·provider 비용 계산·테스트를 이 결정에 맞춘다.
  - 운영에서 비용을 대조할 때는 OpenAI Cost data와 앱 전용 요청 집계의 범위 일치를 별도 확인한다.

## 보류 및 재검토

- 지금 하지 않는 것: OpenAI Costs API를 요청 경로에서 호출하거나, provider 비용 파일을 앱 DB/Redis에 자동 수집하는 작업.
- 보류 이유: invoice 대조는 비동기 운영 보고이며, 이 기능의 실시간 요청 허용 여부와 분리한다.
- 다시 검토할 조건: 가격 snapshot과 provider usage의 차이가 반복적으로 상한 판정에 영향을 주거나, 미확정 예약이 장기간 누적되거나, OpenAI Cost data를 앱 요청에 정확히 귀속할 안정적인 식별 경계가 생기는 경우.

## 참고 자료

- [OpenAI API 사용량과 비용 확인](https://help.openai.com/en/articles/10478918-reviewing-api-usage-and-costs)
- [OpenAI 월별 사용량·비용 내보내기](https://help.openai.com/en/articles/20001072)
- [#1110 비용 quota 재검토](https://github.com/bamsongi-club/albam-mate/issues/1110)

## 검증

- 상태: 부분 검증
- 근거:
  - `#1111` T1~T5 자동 검증에서 최대비용 예약, Redis 원자 정산, 기존 cent 누적 호환, provider usage 및 cache 가격 계산을 통과했다.
  - H2 및 Redis/PostgreSQL 통합 테스트 기준의 로컬 검증이며, 배포·실서비스 비용 대조를 의미하지 않는다.
- 미검증:
  - OpenAI Cost data와 앱 전용 운영 호출의 대조 및 실제 청구액과 내부 추정액의 차이
  - 배포 환경의 운영 측정

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
