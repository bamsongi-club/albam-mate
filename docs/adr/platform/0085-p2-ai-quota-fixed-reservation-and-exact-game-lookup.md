# ADR-0085: P2 AI 호출 quota·고정 예약 비용과 정확 게임명 직접 조회

- 상태: 승인됨
- 작성일: 2026-08-21
- 결정일: 2026-08-21
- 관련: [#944](https://github.com/bamsongi-club/albam-mate/issues/944), [ADR-0074](0074-p2-ai-provider-consent-and-operation-boundary.md), [AI 기능 명세](../../p2/assistant.md), [API](../../API.md), [AI 검증 설계](../../p2/assistant-load-test.md), [OPS-04](../../p2/monitoring.md#ops-04-ai-사용량과-추정-비용)
- 대체 대상: [ADR-0074](0074-p2-ai-provider-consent-and-operation-boundary.md)의 `기본 실행·제한·비용` 중 사용자별 호출 한도와 앱 월 비용 예약·상한 정책
- 후속 ADR: 없음

## 맥락

ADR-0074는 사용자별 KST 일 5회·월 150회, 앱 전체 월 `$5` hard cap을 정했다. 로컬 사용 흐름에서 일 5회는 부족했고, 현재 provider 경로의 비용 예약은 token 가격 계산이 아니라 호출당 고정 USD `0.10`이다. 따라서 앱 전체 월 `$5`는 실제 provider 청구액이나 사용자별 월 150회 보장이 아니라 공유 예약 예산이다.

또한 정규화한 요청 문장 안에 유일한 카탈로그 정식명 매치가 있을 때에도 provider로 보내면 비용·quota를 소비하고, 모델에게 게임 검색·식별 권한을 주지 않는 ADR-0074의 경계와도 맞지 않는다. 이 결정은 provider를 확장하지 않고 서버의 `game.contract` 조회로 그 경우를 처리한다. 사용자가 `카탄 모임 만들어줘`처럼 게임명과 모임 의도를 함께 말하는 흐름도 같은 providerless 후보·확인 경계에 포함한다.

판단 기준은 다음과 같다.

- 앱 전체 월 `$5` 예약 상한과 fail-closed 정책을 유지한다.
- 사용자별 일 제한만 완화하되, 전역 예산이 먼저 소진될 수 있음을 숨기지 않는다.
- token 관측과 내부 예약 예산을 혼동하지 않는다.
- 유일한 정식 게임명 언급은 provider·AI quota·비용 예약을 거치지 않되, 모델에 게임 검색 권한을 주지 않는다.

## 검토한 대안

| 대안 | 장점 | 비용·위험 | 판단 |
| --- | --- | --- | --- |
| 일 5회와 기존 문구 유지 | 구현 변경이 없음 | 로컬 사용자 흐름의 제한이 과도하고 문서의 비용 계산과 실제 예약이 충돌 | 제외 |
| 일 10회와 호출당 USD `0.10` 고정 예약 유지 | 현재 Redis 금액 단위·월 `$5` fail-closed 경계를 유지 | 빈 월의 실제 provider 호출은 최대 50회이며, 사용자 월 quota가 남아도 전역 예산이 먼저 소진될 수 있음 | 선택 |
| token 가격 snapshot으로 월 `$5`를 실시간 재계산 | 사용량과 가까운 비용 추정 가능 | 가격·반올림·completion·provider 청구 차이를 새 정책과 구현으로 해결해야 함 | 보류 |
| 정확 게임명을 provider가 식별·검색 | 대화 처리 경로가 하나로 보임 | provider 호출·quota·비용을 낭비하고 게임 검색 권한 경계가 흐려짐 | 제외 |

## 결정

### provider 호출 quota와 고정 예약 예산

- 실제 외부 provider 경로의 사용자별 quota는 KST 일 10회, 월 150회, 동시 1회로 한다. 일일 quota는 매일 00:00 KST, 월 quota와 앱 예산은 매월 1일 00:00 KST에 reset한다. timeout은 10초, retry는 0이다.
- 외부 provider 호출 경로에 진입한 요청마다 USD `0.10`을 고정 예약·정산한다. 성공·timeout·provider 429·schema 오류는 quota와 USD `0.10` 예약을 소비한다. 인증·동의·입력 검증·feature gate 거절과 provider 호출 전 Redis 실패는 소비하지 않는다.
- 앱 전체 KST 월간 외부 provider 호출 예약 예산은 USD `$5` hard cap, USD `$4` warning이다. 빈 월에는 40번째 예약에서 warning을 발행하고 50번째 예약까지 허용하며 51번째 예약부터 `ASSISTANT_COST_LIMIT_EXCEEDED`로 차단한다. 이는 실제 provider 청구 호출 수나 청구 확정액이 아니라 앱 내부 예약 예산의 상한이다.
- 사용자별 월 150회는 개인 상한일 뿐 공유 예산의 이용 보장이 아니다. 초기 40명은 대상 사용자 수이며 `40명 × 150회 = 6,000회`는 사용자 quota를 산술 합산한 값일 뿐 실제 provider 호출 계획이 아니다. 공유 예산이 먼저 소진되면 개인 quota가 남아도 차단한다.
- input/output/total token은 사용량 관측용으로 기록할 수 있으나, 이 ADR의 quota·hard cap 계산에는 사용하지 않는다. 가격 snapshot과 최대 token 설정은 USD `0.10` 예약값의 적정성을 재검토하는 근거로만 사용한다.
- 결정적 fake provider는 격리된 테스트 경로이므로 quota·비용 예약을 소비하지 않는다. 실제 외부 provider 경로와 같은 quota·예약 경계를 검증해야 하는 테스트는 fake가 아닌 명시적인 ledger fixture로 수행한다.

### 정확 게임명 providerless 조회

- `POST /api/assistant/recommendations`는 기존 인증·CSRF·feature gate·외부 처리 동의 gate를 통과한 뒤, provider 호출 전에 요청 문장 안의 정확 게임명 조회를 수행한다.
- 정확 매치는 입력과 `Game.name`에 Unicode NFKC, 앞뒤 공백 제거, 연속 공백 하나로 축약, `Locale.ROOT` 대소문자 정규화를 적용한 뒤 공백 경계로 정식 게임명이 완전히 일치하고, 언급된 게임 ID가 유일한 경우다. 입력 전체가 게임명인 경우도 포함한다. 문장 부호 제거, 부분 이름, 별칭·영문명·BGG ID, 기본판과 확장판의 자동 통합은 하지 않는다.
- 유일 매치면 서버가 `game.contract`를 통해 후보 한 건과 내부 `gameId`를 반환한다. provider 호출·AI quota·비용 예약은 0건이며, Room·ChatRoom·초안도 만들지 않는다.
- 0건 또는 복수 매치면 정확 게임명 직접 조회를 성공으로 처리하지 않고 기존 provider 기반 추천 흐름으로 계속한다. provider는 여전히 게임 검색·식별자·Room 쓰기 권한을 갖지 않는다.

## 결과

- 얻는 것:
  - 일일 provider 호출 여유를 10회로 늘리면서 앱 전체 예약 예산을 유지한다.
  - 예약 예산·token 관측·실제 청구액의 의미를 분리한다.
  - 유일한 정식 게임명 또는 게임명이 포함된 단일 요청을 빠르게 카탈로그 후보로 연결하고 외부 provider 호출을 피한다.
- 감수할 비용·위험:
  - 앱 전체 월 최대 50회의 실제 provider 호출 제한은 40명·월 150회 사용자 quota보다 먼저 작동할 수 있다.
  - 정확 매치는 별칭·영문명·문장부호 변형과 복수 게임 언급을 해결하지 않으며, 0건·복수건은 일반 추천 흐름으로 돌아간다.
- 후속 작업:
  - quota ledger의 일 10회·고정 USD `0.10` completion, 40번째 warning·51번째 거절을 구현·검증한다.
  - assistant 전용 후보 DTO·정확 게임명 `game.contract` resolver·후보 상세/초안 선택 UI를 구현한다.

## 보류 및 재검토

- 지금 하지 않는 것: token 가격 기반 hard cap, 별칭·영문명·문장부호 변형·복수 게임 자동 선택, provider 검색 권한, 추천 이력·부분 초안의 서버 영속화.
- 보류 이유: 각각 가격 정산·오탐·동의/권한 또는 초안 저장 경계를 별도로 바꾼다.
- 다시 검토할 조건: 예약 USD `0.10`이 실제 provider 사용량을 지속적으로 과대 또는 과소하게 제한하거나, 유일 정식명 범위가 실제 사용자의 게임 탐색 요구를 충족하지 못하는 경우.

## 참고 자료

- [#944](https://github.com/bamsongi-club/albam-mate/issues/944)
- [ADR-0074](0074-p2-ai-provider-consent-and-operation-boundary.md)
- [AI 기능 명세](../../p2/assistant.md)
- [AI 검증 설계](../../p2/assistant-load-test.md)

## 검증

- 상태: 미검증
- 근거:
  - 계약: 이 ADR, API·AI 기능 명세·검증 설계의 목표 계약을 같은 변경에서 정렬한다.
- 미검증:
  - 일 10회/11번째 거절, 40번째 warning·50번째 허용·51번째 거절, fake provider 분리 검증
  - 유일·0·복수 정확 게임명과 문장 내 유일 게임명, provider·quota·비용 예약 0건 경로
  - 실제 provider 호출·가격 snapshot·운영 비용·배포 후 관측

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
