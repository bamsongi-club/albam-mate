# jiwon k6 측정 문서

ROOM portable bundle k6 campaign의 목록과 상태를 관리한다. 공통 승격·보존·증거 규칙은 [상위 README](../README.md)를 정본으로 따르고, 시나리오와 실행 계약은 [ROOM k6 README](../../../../load-tests/k6/jiwon/README.md)에서 확인한다.

Campaign ID 앞 01~05는 실행·복구·재측정의 시간순이다. 상세 결론·원인·한계는 각 보고서가, machine ledger·수치·gate는 각 campaign manifest가 소유한다.

| Campaign ID | 캠페인 상태 | 보고서 | 증거 | 대체 관계 |
| --- | --- | --- | --- | --- |
| 01. `room-k6-matrix-2026-08-14` | `completed-with-limitations` | [보고서](room-portable-bundle-01-initial-invalid-2026-08-14.md) | [campaign manifest](evidence/room-portable-bundle-01-initial-invalid-2026-08-14.json) | 02 재실행 전 이력 · 기준선 제외 |
| 02. `room-k6-snapshot-rerun-2026-08-14` | `completed-with-limitations` | [보고서](room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.md) | [campaign manifest](evidence/room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.json) | 03 recovery smoke 전 이력 · 기준선 제외 |
| 03. `room-k6-t1-recovery-smoke-2026-08-14` | `completed-with-limitations` | [보고서](room-portable-bundle-03-t1-recovery-fail-2026-08-14.md) | [campaign manifest](evidence/room-portable-bundle-03-t1-recovery-fail-2026-08-14.json) | 05 최종 campaign 전 이력 · 기준선 제외 |
| 04. `room-k6-local-gate-2026-08-15` | `completed-with-limitations` | [보고서](room-portable-bundle-04-local-gate-invalid-2026-08-15.md) | [campaign manifest](evidence/room-portable-bundle-04-local-gate-invalid-2026-08-15.json) | 05로 대체 · 기준선 제외 |
| 05. `room-k6-final-clean-2026-08-15` | `completed-with-limitations` | [보고서](room-portable-bundle-05-final-valid-2026-08-15.md) | [campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json) | 현재 correctness 기준선 |
