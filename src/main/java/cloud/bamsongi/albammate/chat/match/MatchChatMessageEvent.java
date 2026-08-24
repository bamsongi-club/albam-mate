package cloud.bamsongi.albammate.chat.match;

/** {@code GET /api/matches/parties/{partyId}/chat/ws}가 보내는 서버 발신 텍스트 이벤트다. */
public record MatchChatMessageEvent(long eventId, String type, MatchChatMessageResponse message) {

	private static final String MESSAGE_CREATED = "MESSAGE_CREATED";

	/** 커밋된 메시지의 {@code messageId}를 {@code eventId}로 그대로 사용하는 이벤트를 만든다. */
	public static MatchChatMessageEvent messageCreated(MatchChatMessageResponse message) {
		return new MatchChatMessageEvent(message.messageId(), MESSAGE_CREATED, message);
	}
}
