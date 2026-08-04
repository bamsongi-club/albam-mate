package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.chat.retention.enabled=false")
class ChatMessageRetentionSchemaTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ChatMessageRetentionProperties properties;

	@Test
	void H2_Flyway와_JPA_validate가_ShedLock과_채팅_보관_구조를_검증한다() {
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from shedlock", Integer.class));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from chat_rooms", Integer.class));
	}

	@Test
	void 기본_잠금_시간은_최소_시간이_최대_시간을_초과하지_않는다() {
		assertEquals(Duration.ofSeconds(5), properties.getLockAtMostFor());
		assertEquals(Duration.ofSeconds(5), properties.getLockAtLeastFor());
	}
}
