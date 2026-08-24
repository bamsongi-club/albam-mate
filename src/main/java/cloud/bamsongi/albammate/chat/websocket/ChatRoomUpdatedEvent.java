package cloud.bamsongi.albammate.chat.websocket;

/**
 * {@code GET /api/users/me/chat/ws}가 보내는 서버 발신 최소 신호다.
 *
 * <p>{@code roomId}·{@code messageId}만 담고 메시지 본문·발신자 식별 정보는 싣지 않는다. 클라이언트는 이 값을
 * 직접 화면에 반영하지 않고 CHAT-07 배치 조회로 최신 값을 다시 가져온다.
 */
record ChatRoomUpdatedEvent(long roomId, long messageId) {
}
