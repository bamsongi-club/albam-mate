# Notification ADR

서비스 내 웹 알림의 생성, 전달 신뢰성과 실패 복구에 관한 결정을 찾는 인덱스다. 현재 결정이 확정한 테이블·제약·인덱스는 [ERD의 P1 알림 저장 계약](../../ERD.md#p1-알림-저장-계약)이 소유하며, 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0029](0029-room-integration-event-transactional-outbox.md) | 방 변경 통합 이벤트와 Transactional Outbox 기록 경계를 확정 | 승인됨 | 2026-07-31 | 검증됨 |
| [0030](0030-postgresql-notification-relay-processing-recovery.md) | PostgreSQL polling relay의 처리와 복구 정책을 확정 | 대체됨 | 2026-07-31 | 미검증 |
| [0039](0039-notification-presentation-and-bulk-read-snapshot.md) | 알림 표시 투영과 PostgreSQL 조회·읽음 시각을 확정 | 승인됨 | 2026-08-01 | 검증됨 |
| [0040](0040-postgresql-notification-relay-recovery-retention.md) | PostgreSQL 알림 relay·복구·보존 정책을 대체 확정 | 승인됨 | 2026-08-01 | 미검증 |
