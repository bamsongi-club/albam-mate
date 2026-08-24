package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * 참가 확정 또는 참가 취소 확정 한 건의 사실이다.
 *
 * <p>상태를 전이시키지 못한 요청(중복 참가, 대상 없는 취소 등)에서는 발행하지 않는다. {@link RoomChangeEventRecorder}와
 * 달리 알림 수신자 유무와 무관하게 상태가 실제로 전이될 때마다 발행한다.
 */
public record RoomParticipantChanged(long roomId, long subjectUserId, Kind kind, Instant occurredAt) {

	public RoomParticipantChanged {
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(occurredAt, "occurredAt");
	}

	public enum Kind {
		ENTERED, LEFT
	}
}
