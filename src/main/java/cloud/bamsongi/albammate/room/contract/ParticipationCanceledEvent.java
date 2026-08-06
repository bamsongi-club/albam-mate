package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/** 자동 승격 없이 빈자리가 남은 참가 취소를 주최자에게 알릴 원인 사실이다. */
public record ParticipationCanceledEvent(long roomId, Instant occurredAt)
	implements
		RoomChangeEvent {

	public ParticipationCanceledEvent {
		Objects.requireNonNull(occurredAt, "occurredAt");
	}
}
