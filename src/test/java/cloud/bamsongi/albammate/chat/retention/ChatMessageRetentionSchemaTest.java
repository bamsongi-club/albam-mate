package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "app.chat.retention.enabled=false")
class ChatMessageRetentionSchemaTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void H2_Flyway와_JPA_validate가_ShedLock과_채팅_보관_구조를_검증한다() {
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from shedlock", Integer.class));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from chat_rooms", Integer.class));
	}
}
