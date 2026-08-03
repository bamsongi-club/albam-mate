package cloud.bamsongi.albammate.chat.entity;

import java.time.Instant;
import java.util.Objects;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "chat_rooms")
public class ChatRoom extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;

	@Column(name = "room_id", nullable = false, unique = true)
	private Long roomId;

	@Column(name = "purge_after")
	private Instant purgeAfter;

	@Column(name = "messages_purged_at")
	private Instant messagesPurgedAt;

	/** 방 생성 트랜잭션에서 ROOM 식별자와 함께 저장할 채팅방을 만든다. */
	public static ChatRoom create(Long roomId) {
		ChatRoom chatRoom = new ChatRoom();
		chatRoom.roomId = Objects.requireNonNull(roomId, "roomId");
		return chatRoom;
	}
}
