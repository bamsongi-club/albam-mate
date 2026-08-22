# ADR-0089: P2 OpenAI 기본 보존 수용과 실제 smoke gate

- 상태: 승인됨
- 작성일: 2026-08-22
- 결정일: 2026-08-22
- 관련: [#822](https://github.com/bamsongi-club/albam-mate/issues/822), [#823](https://github.com/bamsongi-club/albam-mate/issues/823), [#824](https://github.com/bamsongi-club/albam-mate/issues/824), [ADR-0074](0074-p2-ai-provider-consent-and-operation-boundary.md), [ADR-0085](0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)
- 대체 대상: [ADR-0074](0074-p2-ai-provider-consent-and-operation-boundary.md)의 provider 보존 확인 gate만 부분 대체
- 후속 ADR: 없음

## 맥락

기존 provider gate는 OpenAI 외부 계정 확인, provider-only egress 증명, ZDR/no-retention 확인을 모두 실제 호출의 필수 조건으로 취급했다. 현재 P1은 public subnet/IGW 구조라 FQDN 단위 provider-only egress를 증명하지 못하며, 실측 목적의 기본 OpenAI API 사용은 ZDR 승인을 전제로 하지 않는다.

OpenAI API의 기본 운영 정책에서 입력·출력은 모델 학습에 사용하지 않지만, abuse-monitoring 목적의 로그가 기본적으로 최대 30일 보존될 수 있다. 따라서 실측 profile은 이 보존을 숨기지 않고 명시적으로 수용해야 하며, 사용자 동의 화면에도 현재 보존 mode를 보여줘야 한다.

## 결정

### 명시적 retention mode

- `app.assistant.retention-mode`는 `default-30d`, `zero-data-retention`, `unverified` 중 하나다. 기본값은 `unverified`이며 유효한 mode가 없으면 AI는 fail-closed한다.
- `default-30d`는 OpenAI 기본 abuse-monitoring 로그 보존(최대 30일)을 수용하는 실측 mode다. 이 mode에서는 `no-training=true`, `store=false`, 현재 policy version/URL, 가격 snapshot이 필요하지만 ZDR/no-retention 확인은 필요하지 않다.
- `zero-data-retention`은 ZDR 또는 Modified Abuse Monitoring 승인을 실제로 확인한 경우에만 사용할 수 있고 `no-retention=true`를 요구한다.
- 동의 응답과 화면은 `retentionMode`를 공개한다. `default-30d`는 사용자가 기본 보존 가능성을 인지한 뒤 동의하도록 한다.

### provider 활성화 gate

- 활성화에 필요한 런타임 증거는 정확한 SSM `ALBAM_MATE_ASSISTANT_OPENAI_API_KEY`의 존재·비공백 값과 허용된 OpenAI endpoint, `no-training`, retention mode, policy metadata, pricing metadata다.
- provider account 검증과 provider-only egress 검증은 관찰·기록용 값으로 남기되 필수 gate에서 제거한다. 두 값이 `false`여도 default-30d profile은 활성화 후보가 될 수 있다.
- 인프라의 `assistant-smoke` 명령은 SSM key를 읽어 OpenAI Responses API에 저장하지 않는 최소 1회 요청(`store=false`)을 보내고, HTTP 성공·response id·완료 상태를 확인한다. API key와 응답 원문은 출력·receipt에 남기지 않는다.
- smoke가 실패하면 실제 provider 사용을 완료로 판정하지 않고 원인을 수정한 뒤 다시 확인한다. fake provider로 성공을 가장하지 않는다.

`store=false`, 사용자 동의·철회, PII/secret 차단, timeout/retry·quota·비용 상한, policy version/URL과 no-training 확인은 계속 필수다. 이 ADR은 외부 provider 호출을 자동으로 상시화하거나 ZDR을 사용할 권한을 부여하지 않는다.

## 결과

- 얻는 것:
  - ZDR 승인과 provider-only egress topology가 없어도 기본 OpenAI 보존을 공개적으로 수용한 실측을 진행할 수 있다.
  - account/egress 증거와 실제 API 접근성을 분리하고, key 존재와 smoke 결과로 provider 접근을 검증한다.
  - consent 응답·UI·앱·Terraform/Ansible gate가 같은 retention mode를 사용한다.
- 감수할 비용·위험:
  - `default-30d` 호출은 OpenAI 기본 abuse-monitoring 보존 가능성을 가진다.
  - public HTTPS egress만으로 provider가 OpenAI로 한정되었다고 주장할 수 없다.
  - smoke는 provider/model/key 접근을 증명하지만 배포된 사용자 동의·추천·quota·비용·품질 실측 전체를 대체하지 않는다.

## 보류 및 재검토

- 지금 하지 않는 것: ZDR 계약·provider-only egress topology 구축, account verification API 자동화, 실제 provider 응답 원문 저장.
- 다시 검토할 조건: OpenAI 보존 정책·model ID·가격이 변경되거나, 개인정보 처리 동의의 허용 범위가 바뀌거나, provider-only egress 또는 ZDR 승인을 실제 운영 요구사항으로 채택하는 경우.

## 참고 자료

- [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data)
- [ADR-0074](0074-p2-ai-provider-consent-and-operation-boundary.md)
- [ADR-0085](0085-p2-ai-quota-fixed-reservation-and-exact-game-lookup.md)
- [P2 AI 기능 명세](../../p2/assistant.md)
- [AI 검증 설계](../../p2/assistant-load-test.md)

## 검증

- 상태: 미검증
- 확인할 것:
  - 앱의 default-30d provider 선택·동의 저장·retentionMode 응답 테스트
  - infra gate에서 account/egress=false와 no-retention=false가 통과하는지, key 부재와 invalid mode가 fail-closed인지
  - 실제 배포 환경에서 `./run.sh assistant-smoke` 성공 여부
  - 배포 후 사용자 동의·추천 요청의 token·latency·error·cost와 보존 고지 증거

> 상태 값과 번호·대체 규칙은 [README](../README.md)를 따른다.
