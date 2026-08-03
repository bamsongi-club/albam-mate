# Room ADR

방 생명주기, 요청자별 행동 가능성과 상태 전이에 관한 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0004](0004-room-state-transition-scheduler.md) | 방의 시간 기반 상태 전이에 내장 스케줄러를 사용 | 대체됨 | 2026-07-24 | 검증됨 |
| [0012](0012-room-request-boundary-state-reconciliation.md) | API 요청 경계에서도 방의 시간 기반 상태를 보정 | 승인됨 | 2026-07-24 | 검증됨 |
| [0035](0035-room-status-action-eligibility-separation.md) | 방 생명주기 상태와 요청자별 행동 가능성을 분리 | 승인됨 | 2026-08-03 | 미검증 |
| [0036](0036-bounded-room-state-transition-processing.md) | 시간 기반 ROOM 자동 전환을 제한된 ID와 ROOM별 독립 처리로 수행 | 승인됨 | 2026-08-01 | 미검증 |
| [0041](0041-postgresql-room-query-consistent-snapshot.md) | 상태 보정 뒤 ROOM 조회를 PostgreSQL 일관 스냅샷으로 구성 | 승인됨 | 2026-08-03 | 미검증 |
