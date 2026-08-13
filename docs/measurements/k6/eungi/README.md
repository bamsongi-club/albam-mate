# Eungi 채팅 k6 검증·측정 문서

이 디렉터리는 채팅 계약 검증과 용량 측정의 결과 문서·evidence를 보존한다. 공통 보존 규칙은 [상위 README](../README.md)를 따른다.

## 계약 검증

계약 검증은 작은 입력에서 기능 정확성을 판정한다. 용량 측정의 지연·처리량 결과와 합쳐 해석하지 않는다.

| Campaign ID | 상태 | 보고서 | evidence | 검증 범위 |
| --- | --- | --- | --- | --- |
| `chat-access-invalidation-20260813T150606KST` | `completed-with-limitations` | [CHAT-03 접근 회수 AWS 계약 검증](chat-access-invalidation-contract-2026-08-13.md) | [검증 evidence](evidence/chat-access-invalidation-contract-2026-08-13.json) | 제어 HTTP `app-a`와 WebSocket `app-b`를 교차 고정한 접근 회수 계약 |

## 용량 측정

용량 측정은 입력 조건과 결과 곡선을 보존한다. release가 바뀐 후속 Campaign은 이전 결과를 대체하지 않고 before 기준선으로 남긴다.

| Campaign ID | 상태 | 보고서 | campaign manifest | 대체 관계 |
| --- | --- | --- | --- | --- |
| `chat-delivery-20260811T172123KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정](chat-delivery-capacity-2026-08-11.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-11.json) | `chat-delivery-20260812T042245KST`가 대체 |
| `chat-delivery-20260812T042245KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 재측정](chat-delivery-capacity-2026-08-12.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12.json) | `chat-delivery-20260812T090111KST`가 대체 |
| `chat-delivery-20260812T090111KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 반복 측정](chat-delivery-capacity-2026-08-12-repeat.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-repeat.json) | `1db046c0` 기준선. `chat-delivery-20260812T213324KST`의 before |
| `chat-delivery-20260812T213324KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정 — #607 수정 후](chat-delivery-capacity-2026-08-12-after-607.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-12-after-607.json) | 대체 없음. release 변경(`b6c32e22`), before 기준선 `chat-delivery-20260812T090111KST` |
| `chat-delivery-20260813T020049KST` | `completed-with-limitations` | [채팅 전송·전달 AWS 용량 측정 — #607 수정 후 반복](chat-delivery-capacity-2026-08-13-after-607-repeat.md) | [campaign manifest](evidence/chat-delivery-capacity-2026-08-13-after-607-repeat.json) | 대체 없음. release 변경(`97ba2665`), `chat-delivery-20260812T213324KST`의 반복 |
