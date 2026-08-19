# OPS-04 AI 사용량·추정 비용 증거 계약

이 디렉터리는 [OPS-04](../../p2/monitoring.md#ops-04-ai-사용량과-추정-비용)의 공개 입력 경계다. 실제 청구서, prompt·응답·Tool 원문, 사용자·session 식별자와 secret은 저장하지 않는다.

## 가격 snapshot

`openai-gpt-5.6-luna-standard-2026-07-30.json`은 OpenAI의 `gpt-5.6-luna` standard short-context 가격을 USD 기준으로 고정한다. 공식 모델 문서와 2026-07-30 가격 변경 공지를 출처로 사용하며, 계산기는 `rateCard`의 canonical JSON SHA-256을 재검증한다.

- 입력: USD 0.20 / 1M token
- cached 입력: USD 0.02 / 1M token. 현재 usage event가 cached token을 분리하지 않으므로 값이 0임을 증명하지 못하면 계산하지 않는다.
- 출력: USD 1.20 / 1M token
- 입력 token이 272,000을 넘는 long-context 요청은 별도 요율을 임의 적용하지 않고 `NO_OBSERVATION`으로 판정한다.

추정식은 다음과 같다.

```text
estimatedUsd = inputTokens × 0.20 / 1,000,000
             + outputTokens × 1.20 / 1,000,000
```

이 값은 할인·무료 구간·cache write·regional processing·환율·세금과 실제 provider 청구 조정을 포함하지 않는 추정값이다.

## 재현 명령

usage JSON은 `observationStatus`, `provider`, `model`, 요청별 `inputTokens`·`outputTokens`·`totalTokens`만 포함한다. 결과는 입력 순서의 `requestIndex`별 추정값과 전체 `periodEstimate`를 함께 반환하며 외부 request ID나 사용자 식별자를 만들지 않는다.

```powershell
python docs/measurements/ops-04/estimate_ai_cost.py `
  --snapshot docs/measurements/ops-04/openai-gpt-5.6-luna-standard-2026-07-30.json `
  --usage <정제된-usage.json>

python -m unittest docs/measurements/ops-04/test_estimate_ai_cost.py
```

AI 미배포, provider 미호출, usage 수집 공백, provider/model 불일치, 가격 snapshot 불일치, cached token 미분리와 long-context 별도 요율 조건은 비용 `0`이 아니라 `NO_OBSERVATION`이다.

## 배포·실측 경계

정적 snapshot과 계산기 테스트는 `OPS-04-AC3`의 구현·자동 검증 일부만 증명한다. 고정 release의 metric 도착, dashboard 값, `$4` 경고의 CloudWatch alarm·SNS 수신과 복구, 관측 자체 비용, teardown receipt가 모두 연결되기 전에는 배포·실측 완료로 표시하지 않는다.
