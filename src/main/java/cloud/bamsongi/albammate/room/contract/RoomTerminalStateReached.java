package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/** ROOM이 CANCELED 또는 FINISHED 최종 상태에 도달한 사실이다. */
public record RoomTerminalStateReached(long roomId, Instant reachedAt) {

	public RoomTerminalStateReached {
		Objects.requireNonNull(reachedAt, "reachedAt");
	}
}
