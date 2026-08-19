package cloud.bamsongi.albammate.chat.match;

import java.time.Instant;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;

/** MATCH 채팅 메시지 Entity를 노출하지 않는 전송·이력 공통 응답이다. */
public record MatchChatMessageResponse(
	long messageId,
	long partyId,
	MatchChatMessageType type,
	String clientMessageId,
	MatchChatSender sender,
	boolean isMine,
	String content,
	Instant createdAt) {

	public static MatchChatMessageResponse from(
		MatchChatMessage message, long partyId, MatchChatSender sender, boolean isMine) {
		return new MatchChatMessageResponse(
			message.getId(),
			partyId,
			message.getMessageType(),
			message.getClientMessageId(),
			sender,
			isMine,
			message.getContent(),
			message.getCreatedAt());
	}
}
