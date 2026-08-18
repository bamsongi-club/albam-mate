# jiwon k6 측정 문서

ROOM portable bundle k6 campaign의 목록과 상태를 관리한다. 공통 승격·보존·증거 규칙은 [상위 README](../README.md)를 정본으로 따르고, 시나리오와 실행 계약은 [ROOM k6 README](../../../../load-tests/k6/jiwon/README.md)에서 확인한다.

Campaign ID 앞 01~05는 실행·복구·재측정의 시간순이다. 상세 결론·원인·한계는 각 보고서가, machine ledger·수치·gate는 각 campaign manifest가 소유한다. 캠페인 종료 상태와 측정 증거 판정은 서로 다른 축이며, 값의 의미는 [공통 상태 어휘](../README.md#상태-어휘)를 따른다.

| Campaign ID | 캠페인 종료 상태 | 보고서 | 측정 증거 판정·근거 | 문서 상태·대체 관계 |
| --- | --- | --- | --- | --- |
| 01. `room-k6-matrix-2026-08-14` | `completed-with-limitations` | [초기 매트릭스 — INVALID (2026-08-14)](room-portable-bundle-01-initial-invalid-2026-08-14.md) | `INVALID` · `prepare=3`: users timestamp SQL 오류 · [campaign manifest](evidence/room-portable-bundle-01-initial-invalid-2026-08-14.json) | `superseded` · 02 재실행 전 오류 이력 · 기준선 제외 |
| 02. `room-k6-snapshot-rerun-2026-08-14` | `completed-with-limitations` | [snapshot 재실행 — INVALID (2026-08-14)](room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.md) | `INVALID` · `beforeSnapshot=3`: snapshot alias SQL 오류 · [campaign manifest](evidence/room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.json) | `superseded` · 03 recovery smoke 전 오류 이력 · 기준선 제외 |
| 03. `room-k6-t1-recovery-smoke-2026-08-14` | `completed-with-limitations` | [T1 recovery smoke — FAIL (2026-08-14)](room-portable-bundle-03-t1-recovery-fail-2026-08-14.md) | `FAIL` · 예상 밖 4xx 5건: shared CookieJar 세션 격리 오류 · [campaign manifest](evidence/room-portable-bundle-03-t1-recovery-fail-2026-08-14.json) | `superseded` · 05 최종 유효 campaign 전 recovery 이력 · 기준선 제외 |
| 04. `room-k6-local-gate-2026-08-15` | `completed-with-limitations` | [local gate 중단 — INVALID (2026-08-15)](room-portable-bundle-04-local-gate-invalid-2026-08-15.md) | `INVALID` · clean-source gate: 자동 첨부파일 untracked · [campaign manifest](evidence/room-portable-bundle-04-local-gate-invalid-2026-08-15.json) | `superseded` · 05 최종 유효 campaign으로 대체 · 기준선 제외 |
| 05. `room-k6-final-clean-2026-08-15` | `completed-with-limitations` | [최종 유효 매트릭스 — PASS (2026-08-15)](room-portable-bundle-05-final-valid-2026-08-15.md) | 25/25 `PASS` · T5 comparison 6/6 `PASS` · [campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json) | `current` · 현재 correctness 기준선 |
| 06. `room-t1-t2-repeated-baseline-2026-08-18` | `completed-with-limitations` | [T1·T2 반복 기준선 — INVALID (2026-08-18)](room-t1-t2-repeated-baseline.md) | `INVALID` · 24/24 transport PASS, 유효 PASS 0; T2 outcome별 latency/p99와 T3 retrier·DB·connection 계측 누락 · [campaign evidence](evidence/room-t1-t2-repeated-baseline.json) | `current` · 실행 기록; 기존 correctness 기준선 대체 안 함 |
