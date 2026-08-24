package cloud.bamsongi.albammate.room.enums;

import java.util.Arrays;

/** 기존 한국어 wire value를 유지하는 Room 지역의 닫힌 집합이다. */
public enum Region {
	HONGDAE("홍대"), GANGNAM("강남"), KONDAE("건대"), JAMSIL("잠실");

	private final String value;

	Region(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static Region from(String value) {
		return Arrays.stream(values()).filter(region -> region.value.equals(value)).findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unsupported region"));
	}
}
