# semantic-30 평가 요약

## 평가 범위

- fixture: `semantic-30-v1`
- query: 30개
- evaluation Top-K: 20
- blind candidate pool: 1,369개 후보
- fixture SHA-256: `84522f97b196d12db33b082fc26529218555b9408a973e6b6da3577587387142`

## 현재 결과

현재 결과는 AI C adjudication을 사용한 provisional 참고값입니다.

| 방식 | Recall@10 | MRR@10 | nDCG@10 | hard-filter 위반율 |
| --- | ---: | ---: | ---: | ---: |
| Dense BGE-M3 | 0.4557 | 0.5764 | 0.5518 | 0% |
| Hybrid/RRF | 0.2199 | 0.2551 | 0.2726 | - |

## 판정 상태

- A/B 판정 불일치: 585건
- A/B/C 3-way 충돌: 117건
- C 판정: AI 초안이며 독립 human 판정이 아님
- 상태: `provisional-ai-adjudication`
- 최종 방식 선택: `pending-human-decision`, `selectedMethod: null`

따라서 이 결과는 개발 방향을 확인하기 위한 참고 지표이며, Final Quality Evaluation·최종 검색 방식·production 전환을 승인하지 않습니다. 독립 제3 human 판정이 완료되면 원본 packet에서 qrels와 metrics를 다시 생성해야 합니다.

## 보관 경계

이번 PR에는 평가 요약과 입력 manifest만 보관합니다. A/B/C 판정 packet, provisional qrels, 재생성 metrics는 실행 환경 또는 승인된 외부 artifact 저장소에 보관하며, 이후 승인된 원본 위치와 SHA-256을 manifest에 반영합니다.
