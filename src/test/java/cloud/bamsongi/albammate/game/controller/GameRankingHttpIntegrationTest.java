package cloud.bamsongi.albammate.game.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.fixture.RoomFixture;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GameRankingHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private GameRepository gameRepository;

	@Autowired
	private RoomRepository roomRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@Test
	void 비로그인_요청은_실제_보안_필터를_통과해_전체와_앞으로_7일_랭킹을_반환한다() throws Exception {
		Instant referenceTime = Instant.now(clock);
		Long hostUserId = insertUser("game-ranking-http-basic@example.com");
		Game overallOnly = saveGame(50001L, "오버롤전용게임");
		Game upcomingOnly = saveGame(50002L, "예정전용게임");
		saveRoom(hostUserId, RoomType.GAME_FOCUSED, overallOnly.getId(), referenceTime.plus(Duration.ofDays(30)));
		saveRoom(hostUserId, RoomType.GAME_FOCUSED, upcomingOnly.getId(), referenceTime.plus(Duration.ofHours(1)));

		mockMvc.perform(get("/api/game-rankings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.overall.length()").value(2))
			.andExpect(jsonPath("$.data.overall[0].rank").value(1))
			.andExpect(jsonPath("$.data.overall[0].gameId").exists())
			.andExpect(jsonPath("$.data.overall[0].bggId").exists())
			.andExpect(jsonPath("$.data.overall[0].name").exists())
			.andExpect(jsonPath("$.data.overall[0].englishName").exists())
			.andExpect(jsonPath("$.data.overall[0].description").exists())
			.andExpect(jsonPath("$.data.overall[0].roomCount").value(1))
			.andExpect(jsonPath("$.data.overall[0].title").doesNotExist())
			.andExpect(jsonPath("$.data.overall[0].hostUserId").doesNotExist())
			.andExpect(jsonPath("$.data.overall[0].startAt").doesNotExist())
			.andExpect(jsonPath("$.data.upcomingWeek.length()").value(1))
			.andExpect(jsonPath("$.data.upcomingWeek[0].gameId").value(upcomingOnly.getId()))
			.andExpect(jsonPath("$.data.upcomingWeek[0].roomCount").value(1));
	}

	@Test
	void 집계_대상_방이_없으면_빈_배열_두_개를_반환한다() throws Exception {
		mockMvc.perform(get("/api/game-rankings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall").isArray())
			.andExpect(jsonPath("$.data.overall").isEmpty())
			.andExpect(jsonPath("$.data.upcomingWeek").isArray())
			.andExpect(jsonPath("$.data.upcomingWeek").isEmpty());
	}

	private Long insertUser(String email) {
		Instant now = Instant.now(clock);
		jdbcTemplate.update(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values (?, 'hash', '방장', ?, ?)
				""",
			email,
			now,
			now);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private Game saveGame(long bggId, String name) {
		return gameRepository.saveAndFlush(GameFixture.valid(bggId, name));
	}

	private Room saveRoom(Long hostUserId, RoomType roomType, Long gameId, Instant startAt) {
		return roomRepository.saveAndFlush(RoomFixture.create(hostUserId, roomType, gameId, startAt));
	}
}
