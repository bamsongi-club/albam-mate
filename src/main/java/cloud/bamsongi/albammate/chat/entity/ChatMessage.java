package cloud.bamsongi.albammate.chat.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

	@Column(name = "sender_user_id", nullable = false)
	private Long senderUserId;

	@Column(name = "client_message_id", nullable = false, length = 100)
	private String clientMessageId;

	@Column(name = "content", nullable = false, columnDefinition = "TEXT")
	private String content;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	/** 메시지 전송 유스케이스가 검증·정규화한 값을 저장 모델로 만든다. */
	public static ChatMessage create(
		Long chatRoomId, Long senderUserId, String clientMessageId, String content, Instant createdAt) {
		ChatMessage chatMessage = new ChatMessage();
		chatMessage.chatRoomId = Objects.requireNonNull(chatRoomId, "chatRoomId");
		chatMessage.senderUserId = Objects.requireNonNull(senderUserId, "senderUserId");
		chatMessage.clientMessageId = Objects.requireNonNull(clientMessageId, "clientMessageId");
		chatMessage.content = Objects.requireNonNull(content, "content");
		chatMessage.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		return chatMessage;
	}
}
