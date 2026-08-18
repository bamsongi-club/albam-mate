package cloud.bamsongi.albammate.chat.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;

/** 채팅 메시지 Entity를 노출하지 않는 전송·이력 공통 응답이다. */
public record ChatMessageResponse(
	long messageId,
	long roomId,
	String clientMessageId,
	ChatMessageSender sender,
	boolean isMine,
	String content,
	Instant createdAt) {

	public static ChatMessageResponse from(
		ChatMessage message, long roomId, String nickname, String profileImageUrl, boolean isMine) {
		return new ChatMessageResponse(
			message.getId(),
			roomId,
			message.getClientMessageId(),
			new ChatMessageSender(nickname, profileImageUrl),
			isMine,
			message.getContent(),
			message.getCreatedAt());
	}
}
