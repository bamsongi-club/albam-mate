package cloud.bamsongi.albammate.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.zaxxer.hikari.HikariDataSource;

@Testcontainers
@SpringBootTest
class SharedPostgresDatabaseCleanerPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 사용자_테이블과_sequence를_초기화하고_Flyway_이력과_기준_행을_복원한다() throws Exception {
		assertEquals(0, dataSource.unwrap(HikariDataSource.class).getMinimumIdle());
		int flywayHistoryCount = rowCount("flyway_schema_history");
		jdbcTemplate.execute("create table shared_cleaner_probe (id bigserial primary key, marker text not null)");
		try {
			jdbcTemplate.update("insert into shared_cleaner_probe(marker) values (?)", "first");
			jdbcTemplate.update("insert into shared_cleaner_probe(marker) values (?)", "second");
			jdbcTemplate.queryForObject("select nextval('room_waitlist_queue_order_seq')", Long.class);
			jdbcTemplate.queryForObject("select nextval('room_waitlist_queue_order_seq')", Long.class);
			jdbcTemplate.update("delete from room_status_correction_progress");
			jdbcTemplate.update("delete from chat_system_message_activation");

			PostgresDatabaseCleaner.clean(dataSource);

			assertEquals(0, rowCount("shared_cleaner_probe"));
			assertEquals(flywayHistoryCount, rowCount("flyway_schema_history"));
			assertEquals(1, rowCount("room_status_correction_progress"));
			assertEquals(1, rowCount("chat_system_message_activation"));
			assertEquals(1L,
				jdbcTemplate.queryForObject("select nextval('room_waitlist_queue_order_seq')", Long.class));
			Long restartedId = jdbcTemplate.queryForObject(
				"insert into shared_cleaner_probe(marker) values ('after-clean') returning id", Long.class);
			assertEquals(1L, restartedId);
		} finally {
			jdbcTemplate.execute("drop table if exists shared_cleaner_probe");
		}
	}

	private int rowCount(String tableName) {
		Integer count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
		return count == null ? 0 : count;
	}
}
