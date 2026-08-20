package cloud.bamsongi.albammate.assistant.entity;

import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;

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

/** 원문 키 없이 확인 결과만 재생하는 AI-03 멱등성 기록이다. */
@Getter
@Entity
@Table(name = "assistant_idempotency_records")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssistantIdempotencyRecord extends BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false)
	private Long userId;
	@Column(name = "draft_id", nullable = false)
	private Long draftId;
	@Column(nullable = false)
	private String operation;
	@JdbcTypeCode(java.sql.Types.CHAR)
	@Column(name = "key_hash", nullable = false, length = 64)
	private String keyHash;
	@Column(name = "draft_version", nullable = false)
	private long draftVersion;
	@Column(nullable = false)
	private String status;
	@Column(name = "room_id")
	private Long roomId;
	@Column(name = "chat_room_id")
	private Long chatRoomId;
	@Column(name = "confirmed_at")
	private Instant confirmedAt;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	public static AssistantIdempotencyRecord pending(long userId, long draftId, String keyHash, long draftVersion,
		Instant expiresAt) {
		AssistantIdempotencyRecord record = new AssistantIdempotencyRecord();
		record.userId = userId;
		record.draftId = draftId;
		record.operation = "DRAFT_CONFIRM";
		record.keyHash = keyHash;
		record.draftVersion = draftVersion;
		record.status = "PENDING";
		record.expiresAt = expiresAt;
		return record;
	}

	public void confirm(long roomId, long chatRoomId, Instant now) {
		status = "CONFIRMED";
		this.roomId = roomId;
		this.chatRoomId = chatRoomId;
		confirmedAt = now;
		expiresAt = now.plusSeconds(86400);
	}
}
