package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/** 참가 또는 재참가가 확정됐음을 주최자에게 알릴 원인 사실이다. */
public record ParticipationJoinedEvent(long roomId, Instant occurredAt)
	implements
		RoomChangeEvent {

	public ParticipationJoinedEvent {
		Objects.requireNonNull(occurredAt, "occurredAt");
	}
}
