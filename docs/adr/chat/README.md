# Chat ADR

방별 채팅 이력, 실시간 전달, 저장 트랜잭션과 보관 정책처럼 채팅 도메인의 되돌리기 어려운 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0031](0031-chat-history-cursor-pagination.md) | 메시지 ID 커서로 채팅 이력과 재연결 구간을 조회 | 제안됨 | 미정 | 미검증 |
| [0032](0032-http-send-websocket-receive.md) | HTTP로 저장하고 방별 WebSocket으로 실시간 수신 | 제안됨 | 미정 | 미검증 |
| [0033](0033-postgresql-source-after-commit-delivery.md) | PostgreSQL을 정본으로 두고 커밋 뒤 인메모리 전달 | 제안됨 | 미정 | 미검증 |
| [0034](0034-chat-message-retention-and-deletion.md) | 최종 상태 채팅 메시지를 30일 보관한 뒤 일괄 삭제 | 제안됨 | 미정 | 미검증 |
