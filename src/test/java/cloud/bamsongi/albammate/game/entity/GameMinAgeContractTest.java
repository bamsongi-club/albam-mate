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

import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TimeConfig.class})
class GameMinAgeContractTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private GameRepository gameRepository;

	@Test
	void 최소_연령은_nullable_INTEGER로_저장되고_목록과_상세에_같이_매핑된다() throws Exception {
		Game knownAge = GameFixture.valid(42401L, "최소 연령 있음");
		ReflectionTestUtils.setField(knownAge, "minAge", 8);
		Game unknownAge = GameFixture.valid(42402L, "최소 연령 미상");
		gameRepository.saveAndFlush(knownAge);
		gameRepository.saveAndFlush(unknownAge);

		Game known = gameRepository.findById(knownAge.getId()).orElseThrow();
		Game unknown = gameRepository.findById(unknownAge.getId()).orElseThrow();
		assertEquals(8, known.getMinAge());
		assertNull(unknown.getMinAge());
		assertEquals(8, GameListItem.from(GameListRow.from(known), 0L).minAge());
		assertNull(GameListItem.from(GameListRow.from(unknown), 0L).minAge());
		assertEquals(8, GameDetail.from(known, 0L).minAge());
		assertNull(GameDetail.from(unknown, 0L).minAge());

		try (Connection connection = dataSource.getConnection();
			ResultSet columns = connection.getMetaData().getColumns(null, null, "games", "min_age")) {
			assertTrue(columns.next());
			assertEquals(java.sql.Types.INTEGER, columns.getInt("DATA_TYPE"));
			assertEquals(DatabaseMetaData.columnNullable, columns.getInt("NULLABLE"));
		}
	}
}
