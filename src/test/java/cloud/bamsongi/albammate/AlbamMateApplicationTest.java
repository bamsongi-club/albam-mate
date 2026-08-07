package cloud.bamsongi.albammate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AlbamMateApplicationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {}

	@Test
	void 외부_데이터소스_환경변수가_있어도_H2로_기동한다() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertEquals("H2", connection.getMetaData().getDatabaseProductName());
		}
	}

	@Test
	void H2는_공통_V4만_적용하고_PostgreSQL_전용_V5는_발견하지_않는다() {
		var appliedVersions = jdbcTemplate.query(
			"select version from flyway_schema_history where success = true",
			(resultSet, rowNumber) -> resultSet.getString("version"));

		assertTrue(appliedVersions.contains("4"));
		assertFalse(appliedVersions.contains("5"));
	}

	@Test
	void H2는_clock_timestamp_호출에서_시각을_반환한다() {
		assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"select clock_timestamp() is not null", Boolean.class)));
	}
}
