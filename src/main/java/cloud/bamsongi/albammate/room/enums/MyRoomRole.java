package cloud.bamsongi.albammate.room.enums;

/** 내 모임 목록의 조회 범위다. */
public enum MyRoomRole {
	ALL,
	JOINED,
	HOSTED;

	/** 외부 query 계약의 소문자 값을 내부 enum 상수로 변환한다. */
	public static MyRoomRole fromQueryValue(String value) {
		if (value == null) {
			throw new IllegalArgumentException("My room role is required");
		}

		return switch (value) {
			case "all" -> ALL;
			case "joined" -> JOINED;
			case "hosted" -> HOSTED;
			default -> throw new IllegalArgumentException("Invalid my room role");
		};
	}
}
