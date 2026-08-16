# [02/05] ROOM portable bundle snapshot 재실행 — INVALID (2026-08-14)

| 항목 | 값 |
| --- | --- |
| Campaign 상태 정본 | [campaign 상태 인덱스](README.md) |
| 기록 분류 | `invalid-measurement-campaign` — 성능 기준선·용량 판단에서 제외 |
| 실행 경로 | ROOM portable bundle → `run.sh room-k6` |
| 시간 범위 | UTC 2026-08-14 13:58:28–14:33:37 / KST 2026-08-14 22:58:28–23:33:37 |
| 근거 식별자 | [비식별 canonical campaign manifest](evidence/room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.json) |
| 비밀정보 경계 | 비밀번호·credential-derived hash·토큰·세션·CSRF·URL·실제 fixture/resource 식별자는 기록하지 않음. source/artifact 무결성 식별값은 linked manifest에만 보존 |

## 결론

공식 25개 조합(T1 6, T2 7, T3 3, T4 3, T5 6)은 bundle 생성·검증, fixture `prepare`, resource query까지 진행했지만 모두 `INVALID`가 됐다. `beforeSnapshot` phase가 모두 exit `3`으로 끝났고 k6는 시작되지 않았다. 따라서 지연시간, RPS, HTTP 오류율과 T5 비교 결과는 성능 근거로 사용할 수 없다.

## 공통 원인과 수정

snapshot SQL의 파생 테이블은 quoted camelCase alias를 내보내는데, outer `ORDER BY`가 snake_case 이름을 참조했다. PostgreSQL이 그 열을 찾지 못해 before snapshot을 거절했다.

- 참여자 정렬은 quoted `roomId`, `userId` alias를 사용하도록 수정했다.
- 대기열 정렬도 quoted `roomId`, `queueOrder`, `userId` alias를 사용하도록 수정했다.
- snapshot SQL의 alias 계약을 확인하는 회귀 테스트를 추가했다.

이 수정은 과거 25개 결과를 유효한 성능 결과로 바꾸지 않는다. 새 clean bundle과 새 release 정렬로 별도 campaign을 다시 실행해야 한다.

## 해석과 한계

- T5 comparison의 `INVALID`는 유효 fixture가 하나도 없었던 후속 결과이며, 성능 비교 결과가 아니다.
- linked manifest의 local-only artifact digest는 이후 로컬 원자료의 변경 여부만 확인하며, 이 Git 저장소만으로 원자료 bundle 내용을 독립 재구성할 수는 없다.

## 판정 근거

| 범위 | bundle / prepare / resource query | before snapshot | k6·after | 최종 판정 |
| --- | --- | --- | --- | --- |
| T1 6개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T2 7개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T3 3개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T4 3개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T5 6개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |

## 결과 처리

이 실패 시점에는 원인 수정과 재측정을 위해 전용 stack을 유지했다. 이 문서는 teardown 완료를 주장하지 않으며, 실제 teardown 결과는 최종 유효 campaign 문서에서만 기록한다.
