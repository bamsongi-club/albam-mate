# CHAT-03 접근 회수 AWS 계약 검증 보고서 (2026-08-13)

## 결론

`room-access-invalidation-contract.js` 전용 계약 시나리오가 임시 AWS 성능 스택에서 통과했다. 제어 HTTP는 `app-a`, WebSocket은 `app-b`로 고정해 두 인스턴스를 교차하는 접근 회수 경로를 검증했다.

- Campaign ID: `chat-access-invalidation-20260813T150606KST`
- 캠페인 상태: `completed-with-limitations`
- 문서 상태: `current`
- 시나리오: [`room-access-invalidation-contract.js`](../../../../load-tests/k6/eungi/room-access-invalidation-contract.js)
- 근거 식별자: [검증 evidence](evidence/chat-access-invalidation-contract-2026-08-13.json)
- PR: [#679](https://github.com/bamsongi-club/albam-mate/pull/679), source merge SHA `afa6cdd7a8436aebdd2b1f2145522fa622a16de8`

`k6`는 exit `0`으로 끝났고 check `14/14`와 모든 즉시 중단 threshold를 통과했다. 이 결과는 용량 또는 운영 SLO가 아니라, 분리된 두 앱 경로에서 접근 회수 계약이 실제 런타임에서 성립함을 확인한 결과다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| 실행 시각 | 2026-08-13 15:06:06 KST (UTC 06:06:06) |
| stack ID | `perf-eungi-20260810` |
| 앱 runtime release SHA | `69438fd3a30150623e5801ff6bff5f4705b6a795` |
| 시나리오 source SHA | `2019764ebc4c759181d2b7a850988ef5c5c15a92` |
| 임시 인프라 SHA | `1d0ddaea32de9e15dd114f6f8a1cb9767f964d0c` (`codex/route-pinning-pr679`) |
| k6 | `1.5.0`, arm64 archive SHA-256 `c8166d774ce2de960605552e2115442f4b7a9521914dc421916b4b5c1c2abb54` |
| fixture | 방 2개, 방당 계정 9개, 방당 메시지 2개 |
| 로그인 한도 | 실행 창 동안 300회/10분 |
| route pinning | 제어 HTTP `app-a`, WebSocket `app-b`; 명시 헤더가 없으면 기존 round-robin 유지 |

시나리오 source SHA는 PR #679의 검증 대상이며, runtime release SHA는 실제 배포된 앱 이미지의 식별자다. 둘은 역할이 다르므로 같은 SHA라고 가정하지 않는다.

## 검증 결과

| 계약 | 결과 |
| --- | --- |
| performance proxy가 교차 인스턴스 route를 고정 | `1/1` 통과 |
| WebSocket 연결 수립 | `2/2` 통과 |
| 참가 취소 뒤 `POLICY_VIOLATION` 종료 | `1/1` 통과 |
| 방 취소 뒤 `POLICY_VIOLATION` 종료 | `1/1` 통과 |
| 무효화 뒤 메시지 미수신 | `2/2` 통과 |
| 다른 방 연결 격리 | `1/1` 통과 |
| 참가 취소 뒤 호스트 메시지 생성 | `1/1` 통과 |
| 종료 방 메시지 거절 | `1/1` 통과 |
| 전체 k6 checks | `14/14` 통과 |
| `dropped_iterations` | `0` |

참고 지표는 WebSocket 연결 p95 `49.7ms`, 세션 duration p95 `1.60s`, 시나리오 전용 HTTP 지표 p95 `56.85ms`다. 이 수치는 단일 계약 Run의 관찰값으로만 읽으며 용량 근거로 사용하지 않는다.

## 실행·정리 경계

시나리오와 fixture 소스는 `albam-mate`가 소유하고, AWS 실행기만 `albam-mate-infra`가 소유한다. 실제 credential fixture와 원시 k6 산출물은 Git에 커밋하지 않았다. seed와 cleanup은 같은 `run_id`를 사용해 한 사이클로 실행됐고, cleanup 성공을 확인했다.

재실행에는 PR #679의 [실행 안내](../../../../load-tests/k6/eungi/README.md#실행)를 사용한다. 이 검증과 같은 조건을 만들려면 다음을 함께 만족해야 한다.

1. 임시 인프라 commit `1d0ddae`의 route pinning과 로그인 한도 전달을 적용한다.
2. k6 `1.5.0`과 위 archive SHA-256을 사용한다.
3. `K6_LOGIN_LIMIT=300`과 서버 로그인 한도 300을 일치시킨다.
4. `room-access-invalidation-contract.js`만 seed·실행·cleanup한다.

## 한계와 해석 범위

- route pinning과 k6 1.5.0은 이 검증을 위해 임시 성능 스택에만 적용했다. 인프라 변경을 되돌리면 이 결과는 **임시 설정에서의 검증**으로만 읽어야 하며, 일반 환경에서 자동 재현된다는 근거가 아니다.
- 기존 6개 채팅 시나리오는 실행하지 않았다. 명시 route 헤더가 없는 요청은 기존 round-robin을 유지하도록 배포 설정을 구성했지만, 이번 Campaign이 그 6개 시나리오의 회귀를 증명하지는 않는다.
- 원시 결과는 local-only다. 아래 SHA-256은 원시 파일이 보존된 동안 동일 바이트인지 확인하는 용도이며, 이 저장소만으로 원시 결과를 독립 재검증할 수 있다는 뜻은 아니다.

## 원시 결과 식별자

원시 결과 위치는 `albam-mate-infra/.run/results/`이며 자격 증명 fixture는 이미 제거됐다.

| 파일 | SHA-256 |
| --- | --- |
| `summary-20260813T060606Z.json` | `f38f93b574fcf1205f98839a7ed6294665012a95ab80d3a866322ddeecd917e0` |
| `console-20260813T060606Z.log` | `0f9471487207c140724c256ec061f51973fdba8ed4fbc875cd544a3f64f3523b` |
