package cloud.bamsongi.albammate.chat.entity;

import java.time.Duration;
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

	private static final Duration RETENTION_PERIOD = Duration.ofDays(30);

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

	/** 최종 상태 전환 시점부터 메시지 보관 기한을 한 번만 정한다. */
	public void schedulePurgeAfter(Instant terminalStateReachedAt) {
		if (purgeAfter == null) {
			purgeAfter = Objects.requireNonNull(terminalStateReachedAt, "terminalStateReachedAt")
				.plus(RETENTION_PERIOD);
		}
	}
}
