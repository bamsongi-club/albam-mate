package cloud.bamsongi.albammate.game.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TimeConfig.class})
class GameReleaseYearContractTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private GameRepository gameRepository;

	@Test
	void 출시_연도는_nullable_INTEGER로_저장되고_미상값은_NULL을_보존한다() throws Exception {
		Game knownYear = GameFixture.valid(40601L, "출시 연도 있음");
		ReflectionTestUtils.setField(knownYear, "releaseYear", 1995);
		Game unknownYear = GameFixture.valid(40602L, "출시 연도 미상");
		gameRepository.saveAndFlush(knownYear);
		gameRepository.saveAndFlush(unknownYear);

		assertEquals(1995, ReflectionTestUtils.getField(
			gameRepository.findById(knownYear.getId()).orElseThrow(), "releaseYear"));
		assertNull(ReflectionTestUtils.getField(
			gameRepository.findById(unknownYear.getId()).orElseThrow(), "releaseYear"));

		try (Connection connection = dataSource.getConnection();
			ResultSet columns = connection.getMetaData().getColumns(null, null, "games", "release_year")) {
			assertTrue(columns.next());
			assertEquals(java.sql.Types.INTEGER, columns.getInt("DATA_TYPE"));
			assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"));
		}
	}
}
