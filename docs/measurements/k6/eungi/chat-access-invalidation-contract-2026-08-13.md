# CHAT-03 접근 회수 AWS 계약 검증 보고서 (2026-08-13 15:06 KST)

## 결론

PR [#679](https://github.com/bamsongi-club/albam-mate/pull/679)의 접근 회수 계약을 임시 AWS 성능 스택에서 통과했다. 제어 HTTP는 `app-a`, WebSocket은 `app-b`에 고정해 서로 다른 인스턴스를 지나는 회수 경로를 검증했다.

- Campaign ID: `chat-access-invalidation-20260813T150606KST`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 문서 인덱스: [Eungi 채팅 k6 검증·측정 문서](README.md)
- 시나리오: [`room-access-invalidation-contract.js`](https://github.com/bamsongi-club/albam-mate/blob/afa6cdd7a8436aebdd2b1f2145522fa622a16de8/load-tests/k6/eungi/room-access-invalidation-contract.js)
- 증거: [검증 evidence](evidence/chat-access-invalidation-contract-2026-08-13.json)

| 검증 항목 | 관찰 결과 | 판정 |
| --- | --- | --- |
| 교차 인스턴스 route 고정 | 제어 HTTP `app-a`, WebSocket `app-b` 고정 확인 | `PASS` |
| WebSocket 연결 수립 | `2/2` 연결 수립 확인 | `PASS` |
| 참가 취소 뒤 연결 회수 | `POLICY_VIOLATION` 종료 확인 | `PASS` |
| 방 취소 뒤 연결 회수 | `POLICY_VIOLATION` 종료 확인 | `PASS` |
| 무효화 뒤 메시지 차단 | 참가 취소·방 취소 뒤 모두 미수신 | `PASS` |
| 방 간 연결 격리 | 다른 방 연결에 영향 없음 | `PASS` |
| 참가 취소 뒤 호스트 전송 | 메시지 생성 확인 | `PASS` |
| 종료 방 메시지 | 전송 거절 확인 | `PASS` |
| k6 전체 판정 | exit `0`, checks `14/14`, `dropped_iterations` `0` | `PASS` |

이 결과는 용량 또는 운영 SLO가 아니다. 분리된 두 앱 경로에서 접근 회수 계약이 실제 런타임에 성립함을 확인한 단일 계약 Run의 결과다.

## 검증 환경

| 항목 | 고정 값 |
| --- | --- |
| 실행 시각 | 2026-08-13 15:06:06 KST (UTC 06:06:06) |
| stack ID | `perf-eungi-20260810` |
| 앱 runtime release SHA | `69438fd3a30150623e5801ff6bff5f4705b6a795` |
| 시나리오 source SHA | `2019764ebc4c759181d2b7a850988ef5c5c15a92` |
| PR #679 merge SHA | `afa6cdd7a8436aebdd2b1f2145522fa622a16de8` |
| 임시 인프라 SHA | `1d0ddaea32de9e15dd114f6f8a1cb9767f964d0c` (`codex/route-pinning-pr679`) |
| route pinning | 제어 HTTP `app-a`, WebSocket `app-b`; 명시 route 헤더가 없으면 기존 round-robin 유지 |
| k6 | `1.5.0`, arm64 archive SHA-256 `c8166d774ce2de960605552e2115442f4b7a9521914dc421916b4b5c1c2abb54` |
| fixture | 방 2개, 방당 계정 9개, 방당 메시지 2개 |
| 로그인 한도 | 실행 창 동안 300회/10분 |

시나리오 source SHA는 검증한 스크립트 버전이고 runtime release SHA는 실제 배포된 앱 이미지의 식별자다. 둘은 같은 값을 기대하는 필드가 아니다.

참고 관찰값은 WebSocket 연결 p95 `49.7ms`, 세션 duration p95 `1.60s`, 시나리오 전용 HTTP p95 `56.85ms`다. 이 값은 단일 계약 Run의 관찰값이며 용량 판단에 사용하지 않는다.

## 재현과 근거 보존

시나리오·fixture 정본은 `albam-mate`에, AWS 실행기는 `albam-mate-infra`에 둔다. credential fixture와 원시 k6 산출물은 Git에 커밋하지 않았다.

seed와 cleanup은 같은 `run_id`를 한 사이클로 사용했고 cleanup 성공을 확인했다. 재실행은 PR #679의 [실행 안내](https://github.com/bamsongi-club/albam-mate/blob/afa6cdd7a8436aebdd2b1f2145522fa622a16de8/load-tests/k6/eungi/README.md#실행)를 따른다.

1. 임시 인프라 commit `1d0ddae`의 route pinning과 로그인 한도 전달을 적용한다.
2. k6 `1.5.0`과 아래 archive SHA-256을 사용한다.
3. `K6_LOGIN_LIMIT=300`과 서버 로그인 한도 300을 일치시킨다.
4. `room-access-invalidation-contract.js`만 seed·실행·cleanup한다.

원시 결과는 `albam-mate-infra/.run/results/`에 local-only로 보존한다. 아래 SHA-256은 원시 파일이 남아 있는 동안 동일 바이트인지 식별하는 값이다.

| 파일 | SHA-256 |
| --- | --- |
| `summary-20260813T060606Z.json` | `f38f93b574fcf1205f98839a7ed6294665012a95ab80d3a866322ddeecd917e0` |
| `console-20260813T060606Z.log` | `0f9471487207c140724c256ec061f51973fdba8ed4fbc875cd544a3f64f3523b` |

## 해석 범위와 한계

- route pinning과 k6 `1.5.0`은 이 검증을 위해 임시 성능 스택에만 적용했다. 인프라 변경을 되돌리면 이 결과는 **임시 설정에서의 검증**으로만 읽어야 한다.
- 기존 6개 채팅 시나리오는 실행하지 않았다. route 헤더가 없는 요청의 기존 round-robin 유지는 구성했지만, 이번 Campaign이 그 6개 시나리오의 회귀를 증명하지는 않는다.
- 이 저장소의 SHA-256만으로 local-only 원시 결과를 독립 재검증할 수는 없다.
