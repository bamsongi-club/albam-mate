package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/** 방 취소가 확정됐음을 나타내는 원인 사실이다. */
public record RoomCanceledEvent(long roomId, Instant occurredAt)
	implements
		RoomChangeEvent {

	public RoomCanceledEvent {
		Objects.requireNonNull(occurredAt, "occurredAt");
	}
}
