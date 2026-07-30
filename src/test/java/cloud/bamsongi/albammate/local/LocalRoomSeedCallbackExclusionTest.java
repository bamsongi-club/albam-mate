package cloud.bamsongi.albammate.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class LocalRoomSeedCallbackExclusionTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 비로컬_프로필에서는_로컬_Flyway_콜백을_실행하지_않는다() {
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from users where email = 'local.seed.host@albammate.local'",
				Integer.class));
	}
}
