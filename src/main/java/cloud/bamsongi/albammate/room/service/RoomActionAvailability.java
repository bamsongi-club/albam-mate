package cloud.bamsongi.albammate.room.service;

/** 요청자가 현재 방에 직접 참가하거나 대기 신청할 수 있는지 나타낸다. */
public record RoomActionAvailability(boolean joinable, boolean waitlistable) {

	public static final RoomActionAvailability UNAVAILABLE = new RoomActionAvailability(false, false);
}
