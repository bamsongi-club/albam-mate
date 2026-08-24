# jiwon k6 측정 문서

ROOM portable bundle k6 campaign의 목록과 상태를 관리한다. 공통 승격·보존·증거 규칙은 [상위 README](../README.md)를 정본으로 따르고, 시나리오와 실행 계약은 [ROOM k6 README](../../../../load-tests/k6/jiwon/README.md)에서 확인한다.

Campaign ID 앞 숫자는 실행·복구·재측정 항목의 시간순을 나타낸다. 상세 결론·원인·한계는 각 보고서가, machine ledger·수치·gate는 각 campaign manifest가 소유한다. 캠페인 종료 상태와 측정 증거 판정은 서로 다른 축이며, 값의 의미는 [공통 상태 어휘](../README.md#상태-어휘)를 따른다.

ROOM-PERF는 P2 사용자 기능이 아니라, P2 기능이 의존하는 P1 ROOM·참가·대기열 기반을 출시 품질 관점에서 확인하는 cross-cutting gate다. 이 문서는 해당 gate의 campaign 탐색 인덱스이며, 측정 계약·evidence·상태 어휘는 상위 문서가, 상세 결론과 수치는 각 report와 campaign manifest가 소유한다.

관련 작업·검증 흐름은 다음 Issue를 기준으로 연결한다.

| 단계 | 근거 |
| --- | --- |
| 원격 실행 bundle 계약 | [#709](https://github.com/bamsongi-club/albam-mate/issues/709) |
| p99·outcome별 지연 증거 보완 | [#777](https://github.com/bamsongi-club/albam-mate/issues/777) |
| T1·T2 반복 기준선 | [#778](https://github.com/bamsongi-club/albam-mate/issues/778) |
| T5 반복 조회 기준선 | [#779](https://github.com/bamsongi-club/albam-mate/issues/779) |
| ROOM 충돌 재시도 bounded jitter 전후 검증 | [#780](https://github.com/bamsongi-club/albam-mate/issues/780) |
| T1 critical section 축소 실험 | [#781](https://github.com/bamsongi-club/albam-mate/issues/781) |
| T2 version claim 경합 방식 결정 | [#782](https://github.com/bamsongi-club/albam-mate/issues/782) |
| mixed ROOM constant-arrival profile | [#783](https://github.com/bamsongi-club/albam-mate/issues/783) |
| T1 hot c10·T1 spread/T2 hot·spread c16 경합 한계 측정 | [#788](https://github.com/bamsongi-club/albam-mate/issues/788) |
| 개선 후 공식 matrix·mixed soak 최종 gate | [#784](https://github.com/bamsongi-club/albam-mate/issues/784) |

| Campaign ID | 캠페인 종료 상태 | 보고서 | 측정 증거 판정·근거 | 문서 상태·대체 관계 |
| --- | --- | --- | --- | --- |
| 01. `room-k6-matrix-2026-08-14` | `completed-with-limitations` | [초기 매트릭스 — INVALID (2026-08-14)](room-portable-bundle-01-initial-invalid-2026-08-14.md) | `INVALID` · `prepare=3`: users timestamp SQL 오류 · [campaign manifest](evidence/room-portable-bundle-01-initial-invalid-2026-08-14.json) | `superseded` · 02 재실행 전 오류 이력 · 기준선 제외 |
| 02. `room-k6-snapshot-rerun-2026-08-14` | `completed-with-limitations` | [snapshot 재실행 — INVALID (2026-08-14)](room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.md) | `INVALID` · `beforeSnapshot=3`: snapshot alias SQL 오류 · [campaign manifest](evidence/room-portable-bundle-02-snapshot-rerun-invalid-2026-08-14.json) | `superseded` · 03 recovery smoke 전 오류 이력 · 기준선 제외 |
| 03. `room-k6-t1-recovery-smoke-2026-08-14` | `completed-with-limitations` | [T1 recovery smoke — FAIL (2026-08-14)](room-portable-bundle-03-t1-recovery-fail-2026-08-14.md) | `FAIL` · 예상 밖 4xx 5건: shared CookieJar 세션 격리 오류 · [campaign manifest](evidence/room-portable-bundle-03-t1-recovery-fail-2026-08-14.json) | `superseded` · 05 최종 유효 campaign 전 recovery 이력 · 기준선 제외 |
| 04. `room-k6-local-gate-2026-08-15` | `completed-with-limitations` | [local gate 중단 — INVALID (2026-08-15)](room-portable-bundle-04-local-gate-invalid-2026-08-15.md) | `INVALID` · clean-source gate: 자동 첨부파일 untracked · [campaign manifest](evidence/room-portable-bundle-04-local-gate-invalid-2026-08-15.json) | `superseded` · 05 최종 유효 campaign으로 대체 · 기준선 제외 |
| 05. `room-k6-final-clean-2026-08-15` | `completed-with-limitations` | [최종 유효 매트릭스 — PASS (2026-08-15)](room-portable-bundle-05-final-valid-2026-08-15.md) | 25/25 `PASS` · T5 comparison 6/6 `PASS` · [campaign manifest](evidence/room-portable-bundle-final-valid-2026-08-15.json) | `current` · 현재 correctness 기준선 |
| 06. `room-t1-t2-repeated-baseline-2026-08-18` | `completed-with-limitations` | [T1·T2 반복 기준선 — INVALID (2026-08-18)](room-t1-t2-repeated-baseline.md) | `INVALID` · 24/24 transport PASS, 유효 PASS 0; T2 outcome별 latency/p99와 T3 retrier·DB·connection 계측 누락 · [campaign evidence](evidence/room-t1-t2-repeated-baseline.json) | `superseded` · 08 재실행 전 근거 이력 |
| 07. `room-t5-repeated-baseline-2026-08-18` | `completed-with-limitations` | [T5 반복 기준선 — INVALID (2026-08-18)](room-t5-repeated-baseline.md) | `INVALID` · 6개 canonical fixture 1회씩, 조건별 3회 요구 중 12회 미실행; run-manifest 0·comparison accepted 0 · [campaign evidence](evidence/room-t5-repeated-baseline.json) | `superseded` · 09 재실행 전 근거 이력 |
| 08. `room-t1-t2-repeated-baseline-2026-08-20` | `completed-with-limitations` | [T1·T2 반복 기준선 — PASS (2026-08-20)](room-t1-t2-repeated-baseline-2026-08-20.md) | `PASS` · 8조건×3회 24/24, T2 outcome별 latency/p99와 T3 resource signal/raw artifact 24/24 · [campaign evidence](evidence/room-t1-t2-repeated-baseline-2026-08-20.json) | `current` · 06 재실행을 대체; correctness 기준선과 별도 |
| 09. `room-t5-repeated-baseline-2026-08-20` | `completed-with-limitations` | [T5 역할·ACTIVE 규모별 반복 조회 기준선 — PASS (2026-08-20)](room-t5-repeated-baseline-2026-08-20.md) | `PASS` · 6조건×3회 18/18, comparison 3/3, T3 resource signal/raw artifact 18/18 · [campaign evidence](evidence/room-t5-repeated-baseline-2026-08-20.json) | `current` · 07 재실행을 대체; correctness 기준선과 별도 |
| 10. `room-t1-critical-section-comparison-2026-08-21` | `completed` | [T1 critical section V0·V1·V2 비교 — PASS (2026-08-21)](room-t1-critical-section-comparison-2026-08-21.md) | `PASS` · hot c8 9/9 valid, T5 결과 `RETAIN_V0` · [campaign evidence](evidence/room-t1-critical-section-comparison-2026-08-21.json) | `current` · ADR-0087 V0 선택 근거 |
| 11. `room-t1-t2-contention-limit-2026-08-21` | `completed-with-limitations` | [T1·T2 경합 한계 — PASS (2026-08-21)](room-t1-t2-contention-limit-2026-08-21.md) | `PASS` · 승인 매트릭스 4조건×3회, 유효 12/12; 발생기·dropped iteration·DB·ROOM 불변식 gate 모두 통과 · [campaign evidence](evidence/room-t1-t2-contention-limit-2026-08-21.json) | `current` · #788 전용; #778 기준선과 별도 |
| 12. `room-improvement-final-campaign-2026-08-21` | `completed-with-limitations` | [최종 Mixed·Soak campaign — Matrix PASS, Mixed INVALID, Soak 미실행 (2026-08-21)](room-improvement-final-campaign-2026-08-21.md) | `INCONSISTENT` · T1–T5 matrix 25/25 `PASS`; Mixed 0/3 included; Soak 미실행 · [campaign manifest](evidence/room-improvement-final-campaign-2026-08-21.json) | `current` · #784 최종 gate 기록; matrix 근거와 Mixed·Soak 제한을 함께 보존 |
