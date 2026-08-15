# jiwon k6 측정 문서

ROOM portable bundle k6 측정 결과와 invalid campaign 이력을 관리한다. 공통 보존 규칙은 [상위 README](../README.md)를 따른다.

Campaign ID 앞 01~05는 실행·복구·재측정의 시간순이다. 01~04는 문제 원인과 제외 근거를 보존하는 이력이며, 성능 기준선에는 05 최종 유효 campaign만 사용한다.

| Campaign ID | 상태 | 보고서 | 판단서·근거 | 대체 관계 |
| --- | --- | --- | --- | --- |
| 01. `room-k6-matrix-2026-08-14` | `INVALID` | [초기 매트릭스 — INVALID (2026-08-14)](room-portable-bundle-01-initial-invalid-2026-08-14.md) | `prepare=3`: users timestamp SQL 오류 | 02 재실행 전 오류 이력. 성능 기준선에서 제외 |
| 02. `room-k6-snapshot-rerun-2026-08-14` | `INVALID` | [snapshot 재실행 — INVALID (2026-08-14)](room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.md) | `beforeSnapshot=3`: snapshot alias SQL 오류 | 03 recovery smoke 전 오류 이력. 성능 기준선에서 제외 |
| 03. `room-k6-t1-recovery-smoke-2026-08-14` | `FAIL` | [T1 recovery smoke — FAIL (2026-08-14)](room-portable-bundle-03-t1-recovery-fail-2026-08-14.md) | 예상 밖 4xx 5건: shared CookieJar 세션 격리 오류 | 05 최종 유효 campaign 전 recovery 이력. 성능 기준선에서 제외 |
| 04. `room-k6-local-gate-2026-08-15` | `INVALID` | [local gate 중단 — INVALID (2026-08-15)](room-portable-bundle-04-local-gate-invalid-2026-08-15.md) | clean-source gate: 자동 첨부파일 untracked | 05 최종 유효 campaign으로 대체 |
| 05. `room-k6-final-clean-2026-08-15` | `PASS` | [최종 유효 매트릭스 — PASS (2026-08-15)](room-portable-bundle-05-final-valid-2026-08-15.md) | final result 25/25 `PASS` · T5 comparison 6/6 `PASS` | 현재 유효 성능 기준선 |

이 문서는 ROOM portable bundle → infra `run.sh room-k6` 경로로 수집한 결과만 보존한다. 원시 bundle과 실행 산출물은 `build/k6/room/`에만 남기며 Git에 포함하지 않는다.
