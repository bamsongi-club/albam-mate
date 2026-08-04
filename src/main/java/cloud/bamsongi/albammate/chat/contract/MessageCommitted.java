package cloud.bamsongi.albammate.chat.contract;

/** PostgreSQL 커밋 뒤 전달할 최소 메시지 사실이다. */
public record MessageCommitted(String eventType, long roomId, long messageId) {

	private static final String MESSAGE_CREATED = "MESSAGE_CREATED";

	public MessageCommitted {
		if (!MESSAGE_CREATED.equals(eventType)) {
			throw new IllegalArgumentException("eventType must be MESSAGE_CREATED");
		}
		if (roomId <= 0 || messageId <= 0) {
			throw new IllegalArgumentException("roomId and messageId must be positive");
		}
	}

	public static MessageCommitted messageCreated(long roomId, long messageId) {
		return new MessageCommitted(MESSAGE_CREATED, roomId, messageId);
	}
}
