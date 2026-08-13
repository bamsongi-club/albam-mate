# Participation ADR

참가·대기 불변식, 정원과 동시성에 관한 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0005](0005-room-participation-optimistic-locking.md) | 방 참가 동시성 제어에 낙관 락을 사용 | 승인됨 | 2026-07-24 | 검증됨 |
| [0037](0037-room-waitlist-latest-state-atomic-promotion.md) | ROOM 대기열을 단일 최신 상태로 저장하고 자동 승격을 원자적으로 처리 | 대체됨 | 2026-08-01 | 미검증 |
| [0043](0043-room-waitlist-persistence-conditional-transition-retry.md) | ROOM 대기열을 단일 최신 상태로 저장하고 조건부 전이·등록 재시도를 조정 | 대체됨 | 2026-08-03 | 미검증 |
| [0046](0046-room-waitlist-persistence-conditional-transition-retry.md) | ROOM 대기열을 단일 최신 상태로 저장하고 조건부 전이·등록 재시도를 조정 | 승인됨 | 2026-08-04 | 미검증 |
