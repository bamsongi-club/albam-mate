# [02/05] ROOM portable bundle snapshot 재실행 — INVALID (2026-08-14)

## 결론

공식 25개 조합(T1 6, T2 7, T3 3, T4 3, T5 6)은 bundle 생성·검증, fixture `prepare`, resource query까지 진행했지만 모두 `INVALID`가 됐다. `beforeSnapshot` phase가 모두 exit `3`으로 끝났고 k6는 시작되지 않았다. 따라서 지연시간, RPS, HTTP 오류율과 T5 비교 결과는 성능 근거로 사용할 수 없다.

- Campaign ID: `room-k6-snapshot-rerun-2026-08-14`
- 캠페인 종료 상태: [`completed-with-limitations`](README.md)
- 측정 증거 판정: `INVALID`
- 문서 상태: [`superseded`](README.md)
- 기록 분류: `invalid-measurement-campaign` — 성능 기준선·용량 판단에서 제외
- 문서 인덱스: [Jiwon k6 측정 문서](README.md)
- 근거 식별자: [campaign manifest](evidence/room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.json)
- 대체 관계: 03 T1 recovery smoke 전 오류 이력

## 측정 조건

| 항목 | 고정 값 |
| --- | --- |
| 실행 구간 | UTC 2026-08-14 13:58:28~14:33:37 / KST 2026-08-14 22:58:28~23:33:37 |
| source / 배포 release | `188607b3905122c101411a29b780a863e6377c31` / 동일 revision, 정렬 gate 통과 |
| 실행 범위 | T1 6개·T2 7개·T3 3개·T4 3개·T5 6개, 총 25개를 각각 한 번 실행 |
| runner | ROOM portable bundle → infra `run.sh room-k6`; generic `loadtest` 제외 |
| 실행 도달점 | bundle 검증·prepare·resource query 통과, before snapshot에서 중단 |
| 원자료 | 로컬 `build/k6/room/`만 보존; 비밀값·실환경 URL·실제 fixture/resource 식별자는 Git에 기록하지 않음 |

## 실행 이력과 판정

캠페인은 필요한 원인 기록을 남기고 종료되어 `completed-with-limitations`이며, 각 Run은 성능 측정 전에 중단되어 `INVALID`다.

| 범위 | bundle / prepare / resource query | before snapshot | k6·after | 최종 판정 |
| --- | --- | --- | --- | --- |
| T1 6개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T2 7개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T3 3개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T4 3개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |
| T5 6개 | 통과 / `0` / `0` | `3` | 시작되지 않음 | `INVALID` |

## 원인과 후속 수정

snapshot SQL의 파생 테이블은 quoted camelCase alias를 내보내는데, outer `ORDER BY`가 snake_case 이름을 참조했다. PostgreSQL이 그 열을 찾지 못해 before snapshot을 거절했다.

- 참여자 정렬은 quoted `roomId`, `userId` alias를 사용하도록 수정했다.
- 대기열 정렬도 quoted `roomId`, `queueOrder`, `userId` alias를 사용하도록 수정했다.
- snapshot SQL의 alias 계약을 확인하는 회귀 테스트를 추가했다.

이 수정은 과거 25개 결과를 유효한 성능 결과로 바꾸지 않는다. 새 clean bundle과 새 release 정렬로 별도 campaign을 다시 실행해야 한다.

## 해석과 한계

- 25개 Run 모두 성능 경계 계산에서 제외한다.
- T5 comparison의 `INVALID`는 유효 fixture가 하나도 없었던 후속 결과이며, 성능 비교 결과가 아니다.
- linked manifest의 local-only artifact digest는 이후 로컬 원자료의 변경 여부만 확인하며, 이 Git 저장소만으로 원자료 bundle 내용을 독립 재구성할 수는 없다.

## 다음 측정 조건

- snapshot alias 수정이 반영된 새 source/release 정렬과 clean bundle로 별도 campaign을 시작한다.
- prepare부터 after diagnosis까지 모든 phase와 k6 summary가 존재하는 Run만 성능 근거에 포함한다.

## 재현

현재 실행 절차는 [ROOM k6 실행](../../../../load-tests/k6/jiwon/README.md#실행)과 [Terraform 원격 실행 bundle](../../../../load-tests/k6/jiwon/README.md#terraform-원격-실행-bundle)을 따른다. 이 문서는 수정 전 snapshot 오류 이력이므로 현재 source에서 동일 실패를 재현하는 명령을 별도로 제공하지 않는다.

## 원자료와 teardown

Run ledger와 artifact 무결성 식별값은 [campaign manifest](evidence/room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.json)에 있다. 원시 bundle과 실행 산출물은 로컬 `build/k6/room/`에만 보존한다.

이 실패 시점에는 원인 수정과 재측정을 위해 전용 stack을 유지했다. 이 문서는 teardown 완료를 주장하지 않으며, 실제 teardown 결과는 최종 유효 campaign 문서에서만 기록한다.
