package cloud.bamsongi.albammate.room.entity;

import java.time.Instant;
import java.util.Objects;

import org.springframework.data.domain.Persistable;

import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** ROOM과 사용자의 최신 대기 상태를 한 행으로 저장한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "room_waitlists")
public class RoomWaitlist implements Persistable<RoomWaitlistId> {

	@EmbeddedId
	private RoomWaitlistId id;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private RoomWaitlistStatus status;

	@Column(name = "queue_order", nullable = false)
	private long queueOrder;

	@Column(name = "queued_at", nullable = false)
	private Instant queuedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** 복합 PK를 새 Entity로 저장할 때 merge가 아닌 INSERT를 사용하도록 구분한다. */
	@Transient
	private boolean isNew = true;

	public static RoomWaitlist create(Long roomId, Long userId, long queueOrder, Instant requestTime) {
		RoomWaitlist waitlist = new RoomWaitlist();
		waitlist.id = new RoomWaitlistId(
			Objects.requireNonNull(roomId, "roomId"),
			Objects.requireNonNull(userId, "userId"));
		waitlist.status = RoomWaitlistStatus.WAITING;
		waitlist.queueOrder = queueOrder;
		waitlist.queuedAt = Objects.requireNonNull(requestTime, "requestTime");
		waitlist.createdAt = requestTime;
		waitlist.updatedAt = requestTime;
		return waitlist;
	}

	@Override
	public RoomWaitlistId getId() {
		return id;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostPersist
	@PostLoad
	private void markNotNew() {
		isNew = false;
	}
}
