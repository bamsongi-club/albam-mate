# Matching ADR

실시간 파티 매칭의 후보 선점, 요청 재전송, MATCH 전용 채팅 handoff, 실패 복구, 개인정보 최소 보관과 성능 측정 경계를 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

이 인덱스의 ADR은 MATCH-01의 승인된 기술 선택과 그 근거를 기록한다. 계약·구현·검증·배포·실측의 현재 상태는 이 인덱스에서 판정하지 않으며 [P2 기능 상태](../../p2/README.md#기능별-현재-상태)만 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0061](0061-postgresql-candidate-reservation-idempotency.md) | PostgreSQL 후보 선점과 매칭 요청 멱등성 | 승인됨 | 2026-08-14 | 미검증 |
| [0062](0062-match-chat-handoff-recovery-retention.md) | MATCH 전용 채팅 handoff·복구와 최소 보관 | 승인됨 | 2026-08-14 | 미검증 |
| [0063](0063-match-baseline-measurement-gate.md) | MATCH 후보 탐색 성능 baseline 측정 gate | 승인됨 | 2026-08-14 | 미검증 |
| [0064](0064-match-chat-url-text-storage.md) | MATCH 채팅 URL 텍스트를 메시지 본문에만 저장 | 승인됨 | 2026-08-15 | 미검증 |
| [0065](0065-match-candidate-claim-baseline-scope.md) | MATCH candidate claim baseline 범위와 종합 정합성 gate | 승인됨 | 2026-08-15 | 미검증 |
| [0067](0067-match-shared-contract-boundary.md) | MATCH 공통 모듈 공개 계약 경계와 chat 접근 오류 매핑 | 승인됨 | 2026-08-18 | 미검증 |
| [0077](0077-match-no-game-player-range.md) | MATCH 게임·플랫폼 없는 인원 범위 매칭 | 승인됨 | 2026-08-18 | 미검증 |
| [0080](0080-match-chat-p1-technical-reuse-boundary.md) | MATCH 채팅 실시간 전달의 P1 채팅 기술 기반 재사용 경계 | 승인됨 | 2026-08-19 | 미검증 |
