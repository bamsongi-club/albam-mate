package cloud.bamsongi.albammate.room.service.query;

import java.time.Instant;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;

final class RoomEffectiveStatus {

	private RoomEffectiveStatus() {}

	static RoomStatus resolve(Room room, Instant requestTime) {
		RoomStatus storedStatus = room.getStatus();
		if (storedStatus == RoomStatus.CANCELED || storedStatus == RoomStatus.FINISHED) {
			return storedStatus;
		}
		if (!requestTime.isBefore(room.getStartAt().plus(Room.AUTOMATIC_FINISH_AFTER_START))) {
			return RoomStatus.FINISHED;
		}
		if (storedStatus == RoomStatus.RECRUITING && !requestTime.isBefore(room.getStartAt())) {
			return RoomStatus.CLOSED;
		}
		return storedStatus;
	}
}
