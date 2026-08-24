package cloud.bamsongi.albammate.chat.dto;

/** {@code GET /api/rooms/{roomId}/chat/ws}가 보내는 서버 발신 텍스트 이벤트다. */
public record ChatMessageEvent(long eventId, String type, ChatMessageResponse message) {

	private static final String MESSAGE_CREATED = "MESSAGE_CREATED";

	/** 커밋된 메시지의 {@code messageId}를 {@code eventId}로 그대로 사용하는 이벤트를 만든다. */
	public static ChatMessageEvent messageCreated(ChatMessageResponse message) {
		return new ChatMessageEvent(message.messageId(), MESSAGE_CREATED, message);
	}
}
