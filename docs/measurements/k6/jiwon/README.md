# jiwon k6 측정 문서

## 측정 이력

아래 순서는 실행·복구·재측정의 시간순이다. 01~04는 문제 원인과 제외 근거를 보존하는 이력이며, 성능 기준선에는 05 최종 유효 campaign만 사용한다.

| 순서 | 문서 | Campaign | 상태 | 관계 |
| --- | --- | --- | --- | --- |
| 01 | [초기 매트릭스 — INVALID (2026-08-14)](room-portable-bundle-01-initial-invalid-2026-08-14.md) | `room-k6-matrix-2026-08-14` | `INVALID` | fixture SQL 준비 오류를 보존하는 최초 campaign |
| 02 | [snapshot 재실행 — INVALID (2026-08-14)](room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.md) | `room-k6-snapshot-rerun-2026-08-14` | `INVALID` | snapshot SQL 계약 오류를 보존하는 재실행 |
| 03 | [T1 recovery smoke — FAIL (2026-08-14)](room-portable-bundle-03-t1-recovery-fail-2026-08-14.md) | `room-k6-t1-recovery-smoke-2026-08-14` | `FAIL` | 세션 격리 결함을 보존하며 성능 기준선에서는 제외 |
| 04 | [local gate 중단 — INVALID (2026-08-15)](room-portable-bundle-04-local-gate-invalid-2026-08-15.md) | `room-k6-local-gate-2026-08-15` | `INVALID` | 자동 첨부파일로 불완전해진 중간 campaign |
| 05 | [최종 유효 매트릭스 — PASS (2026-08-15)](room-portable-bundle-05-final-valid-2026-08-15.md) | `room-k6-final-clean-2026-08-15` | `PASS` | 공식 25개 조합과 T5 comparison gate를 통과한 유효 측정 기록 |

공통 배치·보존 규칙은 [상위 k6 결과 문서](../README.md)를 따른다. 이 문서는 ROOM portable bundle → infra `run.sh room-k6` 경로로 수집한 결과만 보존한다. 원시 bundle과 실행 산출물은 `build/k6/room/`에만 남기며 Git에 포함하지 않는다.
