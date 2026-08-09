package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/** 참가 취소 뒤 실제 대기 관계가 승격돼 활성 참가가 된 사실을 나타낸다. */
public record WaitlistPromotedEvent(long roomId, Instant occurredAt)
	implements
		RoomChangeEvent {

	public WaitlistPromotedEvent {
		Objects.requireNonNull(occurredAt, "occurredAt");
	}
}
