# Chat ADR

방별 채팅 이력, 실시간 전달, 저장 트랜잭션과 보관 정책처럼 채팅 도메인의 되돌리기 어려운 결정을 찾는 인덱스다. 작성·상태·전역 번호 규칙은 [루트 ADR README](../README.md)를 따른다.

## ADR 목록

| 번호 | 제목 | 상태 | 결정일 | 검증 |
| --- | --- | --- | --- | --- |
| [0031](0031-chat-history-cursor-pagination.md) | 메시지 ID 커서로 채팅 이력과 재연결 구간을 조회 | 승인됨 | 2026-08-02 | 검증됨 |
| [0032](0032-http-send-websocket-receive.md) | HTTP로 저장하고 방별 WebSocket으로 실시간 수신 | 승인됨 | 2026-08-01 | 검증됨 |
| [0033](0033-postgresql-source-after-commit-delivery.md) | PostgreSQL을 정본으로 두고 커밋 뒤 Redis로 fan-out | 승인됨 | 2026-08-01 | 검증됨 |
| [0034](0034-chat-message-retention-and-deletion.md) | 최종 상태 채팅 메시지를 30일 보관한 뒤 일괄 삭제 | 대체됨 | 2026-08-01 | 미검증 |
| [0045](0045-chat-room-schema-and-backfill-boundary.md) | 채팅방 스키마 생성과 기존 ROOM backfill 실행 경계 분리 | 승인됨 | 2026-08-04 | 미검증 |
| [0049](0049-chat-message-retention-lock-section-boundary.md) | 최종 상태 채팅 메시지 보관·삭제와 잠금 구간 실행 경계 | 승인됨 | 2026-08-04 | 검증됨 |
| [0078](0078-chat-system-message-storage-and-read-time-composition.md) | 입장·퇴장 시스템 메시지를 CHAT_MESSAGES 확장에 저장하고 문구는 읽기 시점에 조립 | 승인됨 | 2026-08-19 | 미검증 |
| [0079](0079-chat-room-read-cursor-and-derived-unread-count.md) | 방별 읽음 커서를 저장하고 미읽음 개수는 조회 시점에 파생 계산 | 승인됨 | 2026-08-19 | 미검증 |
| [0082](0082-chat-list-per-user-realtime-channel.md) | 채팅 목록 갱신을 위한 사용자 단위 실시간 채널 신설 | 승인됨 | 2026-08-20 | 미검증 |

`0034`는 `0049`로 대체된 역사적 ADR이므로 현재 보관·삭제 계약과 검증 상태는 `0049`를 따른다.
