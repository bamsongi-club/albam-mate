package cloud.bamsongi.albammate.assistant.entity;

import java.time.Instant;

import cloud.bamsongi.albammate.global.entity.BaseEntity;
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
@Entity
@Table(name = "assistant_drafts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssistantDraft extends BaseEntity {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Column(name = "user_id", nullable = false)
	private Long userId;
	@Column(name = "draft_version", nullable = false)
	private long draftVersion;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AssistantDraftStatus status;
	@Column(name = "room_type", nullable = false)
	private String roomType;
	@Column(nullable = false)
	private String title;
	@Column
	private String description;
	@Column(name = "game_id")
	private Long gameId;
	@Column(name = "experience_level", nullable = false)
	private String experienceLevel;
	@Column(name = "is_rulemaster_led", nullable = false)
	private boolean rulemasterLed;
	@Column(nullable = false)
	private String region;
	@Column(name = "capacity", nullable = false)
	private int capacity;
	@Column(name = "start_at", nullable = false)
	private Instant startAt;
	@Column
	private String place;
	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;
	@Column(name = "confirmed_at")
	private Instant confirmedAt;
	@Column(name = "room_id")
	private Long roomId;
	@Column(name = "chat_room_id")
	private Long chatRoomId;

	public static AssistantDraft create(long userId, String roomType, String title, String description,
		Long gameId, String experienceLevel, boolean rulemasterLed, String region, int capacity,
		Instant startAt, String place, Instant now) {
		AssistantDraft draft = new AssistantDraft();
		draft.userId = userId;
		draft.roomType = roomType;
		draft.title = title;
		draft.description = description;
		draft.gameId = gameId;
		draft.experienceLevel = experienceLevel;
		draft.rulemasterLed = rulemasterLed;
		draft.region = region;
		draft.capacity = capacity;
		draft.startAt = startAt;
		draft.place = place;
		draft.status = AssistantDraftStatus.ACTIVE;
		draft.expiresAt = now.plusSeconds(900);
		return draft;
	}

	public boolean isExpiredAt(Instant now) {
		return status == AssistantDraftStatus.ACTIVE && !expiresAt.isAfter(now);
	}

	public void discard() {
		if (status == AssistantDraftStatus.ACTIVE) {
			status = AssistantDraftStatus.DISCARDED;
		}
	}

	public void update(String roomType, String title, String description, Long gameId, String experienceLevel,
		boolean rulemasterLed, Instant startAt, String region, String place, int capacity) {
		this.roomType = roomType;
		this.title = title;
		this.description = description;
		this.gameId = gameId;
		this.experienceLevel = experienceLevel;
		this.rulemasterLed = rulemasterLed;
		this.startAt = startAt;
		this.region = region;
		this.place = place;
		this.capacity = capacity;
		draftVersion++;
	}

	public void confirm(long roomId, long chatRoomId, Instant now) {
		status = AssistantDraftStatus.CONFIRMED;
		this.roomId = roomId;
		this.chatRoomId = chatRoomId;
		confirmedAt = now;
	}
}
