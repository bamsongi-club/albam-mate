package cloud.bamsongi.albammate.room.service;

import java.time.Instant;
import java.util.Objects;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;

/** 행동 가능성 판정에 필요한 요청 시점과 현재 관계 사실을 한곳에 모은다. */
public record RoomActionAvailabilityFacts(
	Room room,
	RoomStatus effectiveStatus,
	Instant requestTime,
	boolean authenticated,
	boolean host,
	boolean activeParticipant,
	boolean waiting) {

	public RoomActionAvailabilityFacts {
		Objects.requireNonNull(room, "room");
		Objects.requireNonNull(effectiveStatus, "effectiveStatus");
		Objects.requireNonNull(requestTime, "requestTime");
	}

	public RoomActionAvailabilityFacts(
		Room room,
		Instant requestTime,
		boolean authenticated,
		boolean host,
		boolean activeParticipant,
		boolean waiting) {
		this(room, room.getStatus(), requestTime, authenticated, host, activeParticipant, waiting);
	}
}
