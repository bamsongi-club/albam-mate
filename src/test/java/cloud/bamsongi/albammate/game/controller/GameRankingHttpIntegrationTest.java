package cloud.bamsongi.albammate.game.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void 응답_항목은_지정된_9개_필드만_노출한다() throws Exception {
		Instant referenceTime = Instant.now(clock);
		Long hostUserId = insertUser("game-ranking-http-fields@example.com");
		Game game = saveGame(50003L, "필드검증게임");
		saveRoom(hostUserId, RoomType.GAME_FOCUSED, game.getId(), referenceTime.plus(Duration.ofDays(30)));

		String responseBody = mockMvc.perform(get("/api/game-rankings"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		JsonNode overall = objectMapper.readTree(responseBody).path("data").path("overall");
		JsonNode item = findByGameId(overall, game.getId());
		assertNotNull(item, "대상 게임 항목을 찾지 못함: " + responseBody);

		Set<String> expectedFields = Set.of(
			"rank", "gameId", "bggId", "name", "englishName", "releaseYear", "imageUrl", "description", "roomCount");
		Set<String> actualFields = new HashSet<>(item.propertyNames());
		assertEquals(expectedFields, actualFields, "응답 항목 필드 allowlist 불일치: " + item);
	}

	private JsonNode findByGameId(JsonNode items, Long gameId) {
		for (JsonNode candidate : items) {
			if (candidate.path("gameId").asLong() == gameId) {
				return candidate;
			}
		}
		return null;
	}

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
			.andExpect(jsonPath(overallOf(overallOnly) + ".roomCount").value(contains(1)))
			.andExpect(jsonPath(overallOf(overallOnly) + ".rank").value(everyItem(greaterThanOrEqualTo(1))))
			.andExpect(jsonPath(overallOf(overallOnly) + ".bggId").value(contains(50001)))
			.andExpect(jsonPath(overallOf(overallOnly) + ".name").value(contains("오버롤전용게임")))
			.andExpect(jsonPath(overallOf(overallOnly) + ".englishName").value(contains("Catan")))
			.andExpect(jsonPath(overallOf(overallOnly) + ".description").value(contains("게임 설명")))
			// 방·주최자 정보는 어느 항목에도 담기지 않는다.
			.andExpect(jsonPath("$.data.overall[*].title").value(empty()))
			.andExpect(jsonPath("$.data.overall[*].hostUserId").value(empty()))
			.andExpect(jsonPath("$.data.overall[*].startAt").value(empty()))
			.andExpect(jsonPath(upcomingOf(upcomingOnly) + ".roomCount").value(contains(1)))
			// 30일 뒤에 시작하는 방만 가진 게임은 앞으로 7일 랭킹에 들어가지 않는다.
			.andExpect(jsonPath(upcomingOf(overallOnly)).value(empty()));
	}

	@Test
	void 집계_대상_방이_없어도_오류_없이_두_랭킹을_반환한다() throws Exception {
		mockMvc.perform(get("/api/game-rankings"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.overall").isArray())
			.andExpect(jsonPath("$.data.upcomingWeek").isArray());
	}

	/**
	 * 공유 H2에는 커밋된 다른 테스트의 방이 남아 함께 집계되므로 이 테스트가 만든 게임만 골라 단정한다.
	 *
	 * <p>빈 랭킹 응답 자체는 {@code GameRankingQueryServiceTest}가, 테이블 전체의 정렬·상한은
	 * {@code GameRankingPostgresTest}가 확인한다.
	 */
	private String overallOf(Game game) {
		return "$.data.overall[?(@.gameId == " + game.getId() + ")]";
	}

	private String upcomingOf(Game game) {
		return "$.data.upcomingWeek[?(@.gameId == " + game.getId() + ")]";
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
