package cloud.bamsongi.albammate.global.time;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

class UtcTimeZoneTest {

	@Test
	void configure은_사용자_시간대와_JVM_기본_시간대를_UTC로_변경한다() {
		String originalUserTimeZone = System.getProperty("user.timezone");
		TimeZone originalDefaultTimeZone = TimeZone.getDefault();

		try {
			System.setProperty("user.timezone", "Asia/Seoul");
			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));

			UtcTimeZone.configure();

			assertEquals("UTC", System.getProperty("user.timezone"));
			assertEquals(ZoneId.of("UTC"), TimeZone.getDefault().toZoneId());
		} finally {
			if (originalUserTimeZone == null) {
				System.clearProperty("user.timezone");
			} else {
				System.setProperty("user.timezone", originalUserTimeZone);
			}
			TimeZone.setDefault(originalDefaultTimeZone);
		}
	}
}
