# jiwon k6 측정 문서

| 문서 | Campaign | 상태 | 관계 |
| --- | --- | --- | --- |
| [ROOM portable bundle 매트릭스 (2026-08-14)](room-portable-bundle-2026-08-14.md) | `room-k6-matrix-2026-08-14` | `INVALID` | 성능 기준선에는 제외하는 현재 invalid campaign 기록 |
| [ROOM portable bundle snapshot 재실행 (2026-08-14)](room-portable-bundle-rerun-2026-08-14.md) | `room-k6-snapshot-rerun-2026-08-14` | `INVALID` | snapshot SQL 계약 오류를 보존하는 재실행 기록 |
| [ROOM portable bundle T1 recovery smoke (2026-08-14)](room-portable-bundle-t1-recovery-smoke-2026-08-14.md) | `room-k6-t1-recovery-smoke-2026-08-14` | `FAIL` | 세션 격리 결함을 보존하며 성능 기준선에서는 제외 |
| [ROOM portable bundle local gate 중단 (2026-08-15)](room-portable-bundle-local-gate-2026-08-15.md) | `room-k6-local-gate-2026-08-15` | `INVALID` | 자동 첨부파일로 인해 불완전해진 중간 campaign 기록 |
| [ROOM portable bundle 최종 매트릭스 (2026-08-15)](room-portable-bundle-final-2026-08-15.md) | `room-k6-final-clean-2026-08-15` | `PASS` | 공식 25개 조합과 T5 comparison gate를 통과한 유효 측정 기록 |

이 문서는 ROOM portable bundle → infra `run.sh room-k6` 경로로 수집한 결과만 보존한다. 원시 bundle과 실행 산출물은 `build/k6/room/`에만 남기며 Git에 포함하지 않는다.
