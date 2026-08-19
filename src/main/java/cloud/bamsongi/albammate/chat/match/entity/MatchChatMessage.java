package cloud.bamsongi.albammate.chat.match.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.chat.match.MatchChatMessageType;
import cloud.bamsongi.albammate.chat.match.MatchChatSystemEventKey;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "match_chat_messages")
public class MatchChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "match_chat_room_id", nullable = false)
	private Long matchChatRoomId;
	@Column(name = "sender_user_id")
	private Long senderUserId;
	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false, length = 20)
	private MatchChatMessageType messageType;
	@Column(name = "client_message_id", length = 100)
	private String clientMessageId;
	@Enumerated(EnumType.STRING)
	@Column(name = "system_event_key", length = 30)
	private MatchChatSystemEventKey systemEventKey;
	@Column(nullable = false, columnDefinition = "TEXT")
	private String content;
	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** Party lifecycle SYSTEM 알림을 만든다. 멱등 저장은 호출자 repository가 소유한다. */
	public static MatchChatMessage createSystemMessage(
		long matchChatRoomId, MatchChatSystemEventKey systemEventKey, String content, Instant createdAt) {
		MatchChatMessage message = new MatchChatMessage();
		message.matchChatRoomId = matchChatRoomId;
		message.messageType = MatchChatMessageType.SYSTEM;
		message.systemEventKey = systemEventKey;
		message.content = content;
		message.createdAt = createdAt;
		return message;
	}

	/** HTTP 전송으로 저장하는 USER 메시지를 만든다. 멱등 조회·저장은 호출자 service가 소유한다. */
	public static MatchChatMessage createUserMessage(
		long matchChatRoomId, long senderUserId, String clientMessageId, String content, Instant createdAt) {
		MatchChatMessage message = new MatchChatMessage();
		message.matchChatRoomId = matchChatRoomId;
		message.senderUserId = senderUserId;
		message.messageType = MatchChatMessageType.USER;
		message.clientMessageId = clientMessageId;
		message.content = content;
		message.createdAt = createdAt;
		return message;
	}
}
