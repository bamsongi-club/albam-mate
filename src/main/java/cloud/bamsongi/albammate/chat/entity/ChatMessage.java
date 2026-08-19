package cloud.bamsongi.albammate.chat.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "chat_messages", uniqueConstraints = @UniqueConstraint(name = "uq_chat_messages_room_sender_client_message", columnNames = {
	"chat_room_id", "sender_user_id", "client_message_id"}))
public class ChatMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "chat_room_id", nullable = false)
	private Long chatRoomId;

	@Column(name = "sender_user_id")
	private Long senderUserId;

	@Column(name = "client_message_id", length = 100)
	private String clientMessageId;

	@Column(name = "content", columnDefinition = "TEXT")
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false, length = 20)
	private ChatMessageType messageType;

	@Enumerated(EnumType.STRING)
	@Column(name = "system_event_key", length = 40)
	private ChatSystemEventKey systemEventKey;

	@Column(name = "subject_user_id")
	private Long subjectUserId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 메시지 전송 유스케이스가 검증·정규화한 값을 USER 저장 모델로 만든다. */
	public static ChatMessage create(
		Long chatRoomId, Long senderUserId, String clientMessageId, String content, Instant createdAt) {
		ChatMessage chatMessage = new ChatMessage();
		chatMessage.chatRoomId = Objects.requireNonNull(chatRoomId, "chatRoomId");
		chatMessage.senderUserId = Objects.requireNonNull(senderUserId, "senderUserId");
		chatMessage.clientMessageId = Objects.requireNonNull(clientMessageId, "clientMessageId");
		chatMessage.content = Objects.requireNonNull(content, "content");
		chatMessage.messageType = ChatMessageType.USER;
		chatMessage.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		return chatMessage;
	}

	/** 참가·참가 취소 확정 사실 한 건에 대응하는 SYSTEM 안내 행을 만든다. 문장은 저장하지 않고 읽기 시점에 조립한다. */
	public static ChatMessage createSystemMessage(
		Long chatRoomId, ChatSystemEventKey systemEventKey, Long subjectUserId, Instant createdAt) {
		ChatMessage chatMessage = new ChatMessage();
		chatMessage.chatRoomId = Objects.requireNonNull(chatRoomId, "chatRoomId");
		chatMessage.messageType = ChatMessageType.SYSTEM;
		chatMessage.systemEventKey = Objects.requireNonNull(systemEventKey, "systemEventKey");
		chatMessage.subjectUserId = Objects.requireNonNull(subjectUserId, "subjectUserId");
		chatMessage.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		return chatMessage;
	}
}
