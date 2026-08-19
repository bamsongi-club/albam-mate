package cloud.bamsongi.albammate.chat.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatMessageType;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;

/** 채팅 메시지 Entity를 노출하지 않는 전송·이력 공통 응답이다. */
public record ChatMessageResponse(
	long messageId,
	long roomId,
	ChatMessageType messageType,
	String clientMessageId,
	ChatMessageSender sender,
	boolean isMine,
	ChatSystemEventKey systemEvent,
	ChatMessageSender subject,
	String content,
	Instant createdAt) {

	public static ChatMessageResponse forUser(
		ChatMessage message, long roomId, String nickname, String profileImageUrl, boolean isMine) {
		return new ChatMessageResponse(
			message.getId(),
			roomId,
			ChatMessageType.USER,
			message.getClientMessageId(),
			new ChatMessageSender(nickname, profileImageUrl),
			isMine,
			null,
			null,
			message.getContent(),
			message.getCreatedAt());
	}

	public static ChatMessageResponse forSystem(
		ChatMessage message,
		long roomId,
		ChatSystemEventKey systemEvent,
		String subjectNickname,
		String subjectProfileImageUrl,
		String content) {
		return new ChatMessageResponse(
			message.getId(),
			roomId,
			ChatMessageType.SYSTEM,
			null,
			null,
			false,
			systemEvent,
			new ChatMessageSender(subjectNickname, subjectProfileImageUrl),
			content,
			message.getCreatedAt());
	}
}
