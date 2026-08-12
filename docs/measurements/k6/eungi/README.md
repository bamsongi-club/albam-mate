# Eungi k6 측정 문서

채팅 전송·전달 k6 측정 결과와 검증 증거를 관리한다. 공통 보존 규칙은 [상위 README](../README.md)를 따른다.

| Campaign ID | 상태 | 보고서 | 근거 manifest | 대체 관계 |
| --- | --- | --- | --- | --- |
| `chat-delivery-20260811T172123KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정](chat-delivery-capacity-2026-08-11.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-11.json) | `chat-delivery-20260812T042245KST`가 대체 |
| `chat-delivery-20260812T042245KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 재측정](chat-delivery-capacity-2026-08-12.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12.json) | `chat-delivery-20260812T090111KST`가 대체 |
| `chat-delivery-20260812T090111KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 반복 측정](chat-delivery-capacity-2026-08-12-repeat.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-repeat.json) | `1db046c0` 기준선. `chat-delivery-20260812T213324KST`의 before |
| `chat-delivery-20260812T213324KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정 — #607 수정 후](chat-delivery-capacity-2026-08-12-after-607.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-after-607.json) | 대체 없음. release 변경(`b6c32e22`), before 기준선 `chat-delivery-20260812T090111KST` |
| `chat-delivery-20260813T020049KST` | `completed` | [채팅 전송·전달 AWS 용량 측정 — #607 수정 후 반복](chat-delivery-capacity-2026-08-13-after-607-repeat.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-13-after-607-repeat.json) | 대체 없음. release 변경(`97ba2665`), `chat-delivery-20260812T213324KST`의 반복 |
