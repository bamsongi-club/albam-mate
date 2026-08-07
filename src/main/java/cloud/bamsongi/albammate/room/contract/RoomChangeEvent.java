package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;

/** ROOM 변경 트랜잭션이 확정한 알림 원인 사실의 공개 계약이다. */
public sealed interface RoomChangeEvent
	permits ParticipationJoinedEvent, ParticipationCanceledEvent, RoomCanceledEvent, WaitlistPromotedEvent {

	long roomId();

	Instant occurredAt();
}
