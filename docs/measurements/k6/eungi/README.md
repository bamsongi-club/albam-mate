# Eungi k6 측정 문서

채팅 전송·전달 k6 측정 결과와 검증 증거를 관리한다. 공통 보존 규칙은 [상위 README](../README.md)를 따른다.

| Campaign ID | 상태 | 보고서 | 근거 manifest | 대체 관계 |
| --- | --- | --- | --- | --- |
| `chat-delivery-20260811T172123KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정](chat-delivery-capacity-2026-08-11.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-11.json) | `chat-delivery-20260812T042245KST`가 대체 |
| `chat-delivery-20260812T042245KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 재측정](chat-delivery-capacity-2026-08-12.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12.json) | `chat-delivery-20260812T090111KST`가 대체 |
| `chat-delivery-20260812T090111KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 반복 측정](chat-delivery-capacity-2026-08-12-repeat.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-repeat.json) | 현재 결과 |
| `chat-access-invalidation-20260813T150606KST` | `completed-with-limitations` | [CHAT-03 접근 회수 AWS 계약 검증](chat-access-invalidation-contract-2026-08-13.md) | [검증 evidence](evidence/chat-access-invalidation-contract-2026-08-13.json) | 현재 계약 검증 결과 |
