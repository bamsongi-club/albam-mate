package cloud.bamsongi.albammate.room.enums;

public enum RoomStatus {
	RECRUITING,
	CLOSED,
	CANCELED,
	FINISHED;

	public boolean isChatAvailable() {
		return this == RECRUITING || this == CLOSED;
	}
}
