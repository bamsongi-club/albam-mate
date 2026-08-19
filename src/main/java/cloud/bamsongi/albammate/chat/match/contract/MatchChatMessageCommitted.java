package cloud.bamsongi.albammate.chat.match.contract;

/** PostgreSQL 커밋 뒤 전달할 최소 MATCH 채팅 메시지 사실이다. */
public record MatchChatMessageCommitted(String eventType, long partyId, long messageId) {

	private static final String MESSAGE_CREATED = "MESSAGE_CREATED";

	public MatchChatMessageCommitted {
		if (!MESSAGE_CREATED.equals(eventType)) {
			throw new IllegalArgumentException("eventType must be MESSAGE_CREATED");
		}
		if (partyId <= 0 || messageId <= 0) {
			throw new IllegalArgumentException("partyId and messageId must be positive");
		}
	}

	public static MatchChatMessageCommitted messageCreated(long partyId, long messageId) {
		return new MatchChatMessageCommitted(MESSAGE_CREATED, partyId, messageId);
	}
}
