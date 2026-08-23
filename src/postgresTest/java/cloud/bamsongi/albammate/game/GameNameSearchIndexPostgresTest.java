package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
@Transactional
class GameNameSearchIndexPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String GAME_NAME_TRIGRAM_INDEX = "ix_games_name_lower_trgm";

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private GameQueryService gameQueryService;

	@Autowired
	private GameRepository gameRepository;

	@Test
	void T1_정확_일치와_부분_일치는_오타_유사_결과보다_먼저_반환한다() {
		insertGame(101L, "Ticket to Ride", 2, 4);
		insertGame(102L, "Ticket to Ride Europe", 2, 4);
		insertGame(103L, "Ticke to Ride", 2, 4);

		assertEquals(List.of(101L, 102L, 103L), searchedIds("Ticket to Ride", 10, 0));
	}

	@Test
	void T2_세글자_이상_한글과_영문_오타는_threshold_이상이면_반환하고_무관한_이름은_제외한다() {
		insertGame(201L, "카탄보드게임", 2, 4);
		insertGame(202L, "카단보드게임", 2, 4);
		insertGame(203L, "Ticket to Ride", 2, 4);
		insertGame(204L, "Ticke to Ride", 2, 4);
		insertGame(205L, "Completely Unrelated", 2, 4);

		assertEquals(List.of(201L, 202L), searchedIds("카탄보드게임", 10, 0));
		assertEquals(List.of(203L, 204L), searchedIds("Ticket to Ride", 10, 0));
	}

	@Test
	void T3_정확과_부분_일치_이후_유사도와_name_ID로_Slice를_결정한다() {
		insertGame(301L, "Ticket to Ride", 2, 4);
		insertGame(302L, "Ticket to Ride Europe", 2, 4);
		insertGame(303L, "Ticke to Ride", 2, 4);
		insertGame(304L, "Ticet to Ride", 2, 4);
		insertGame(305L, "Ticet to Ride", 2, 4);

		GameListRequest firstRequest = searchRequest("Ticket to Ride", 4, 0);
		var firstPage = gameQueryService.findPage(firstRequest, null);
		GameListRequest secondRequest = searchRequest("Ticket to Ride", 4, 1);
		var secondPage = gameQueryService.findPage(secondRequest, null);

		assertEquals(List.of(301L, 302L, 303L, 304L), firstPage.getContent().stream().map(game -> game.id()).toList());
		assertTrue(firstPage.hasNext());
		assertEquals(List.of(305L), secondPage.getContent().stream().map(game -> game.id()).toList());
		assertFalse(secondPage.hasNext());
	}

	@Test
	void T4_오타_유사_검색은_기존_필터와_AND로_결합하고_playedFilter_권한을_유지한다() {
		insertGame(401L, "Ticket to Ride", 5, 6);
		insertGame(402L, "Ticke to Ride", 2, 4);
		long privateMechanismId = insertMechanism(4_001L, "PRIVATE_MECHANISM", false);
		long publicMechanismId = insertMechanism(4_002L, "PUBLIC_MECHANISM", true);
		linkMechanism(401L, privateMechanismId);
		linkMechanism(402L, publicMechanismId);
		long userId = insertUser();
		jdbc.update(
			"insert into user_played_games (user_id, game_id, created_at) values (?, ?, current_timestamp)",
			userId,
			402L);

		GameListRequest request = searchRequest("Ticket to Ride", 10, 0);
		request.setPlayerCount(2);
		request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY));
		request.setMechanism(List.of("PUBLIC_MECHANISM"));

		assertEquals(List.of(402L),
			gameQueryService.findPage(request, userId).getContent().stream().map(game -> game.id()).toList());
		assertThrows(UnauthenticatedException.class, () -> gameQueryService.findPage(request, null));

		GameListRequest privateMechanismRequest = searchRequest("Ticket to Ride", 10, 0);
		privateMechanismRequest.setMechanism(List.of("PRIVATE_MECHANISM"));
		GameListSearchCriteria privateMechanismCriteria = GameListSearchCriteria.from(privateMechanismRequest);
		assertEquals(
			List.of(),
			gameRepository.findAll(GameListSpecification.from(privateMechanismCriteria)).stream()
				.map(game -> game.getId()).toList());
	}

	@Test
	void T5_PostgreSQL_pg_trgm_유사도_SQL과_회귀_fixture로_오타_검색을_검증한다() {
		insertGame(501L, "Ticket to Ride", 2, 4);
		insertGame(502L, "Ticke to Ride", 2, 4);
		insertGame(503L, "Completely Unrelated", 2, 4);
		for (long id = 504L; id <= 20_503L; id++) {
			insertGame(id, "샘플 이름 " + id);
		}
		jdbc.execute("analyze games");

		jdbc.execute("select set_limit(0.47::real)");
		assertTrue(jdbc.queryForObject(
			"select similarity(lower(name), lower(?)) >= 0.3 from games where id = ?",
			Boolean.class,
			"Ticket to Ride",
			502L));
		assertEquals(List.of(501L, 502L), searchedIds("Ticket to Ride", 10, 0));
		assertEquals(0.3d, jdbc.queryForObject("select show_limit()", Double.class), 0.000001d);

		String fuzzyPlan = String.join(
			"\n",
			jdbc.queryForList(
				"explain (format text) select id from games "
					+ "where lower(name) like ? "
					+ "or (lower(name) % ? and similarity(lower(name), ?) >= 0.3)",
				String.class,
				"%ticket to ride%",
				"ticket to ride",
				"ticket to ride"));
		assertTrue(fuzzyPlan.contains(GAME_NAME_TRIGRAM_INDEX));
	}

	@Test
	void PostgreSQL_Flyway가_pg_trgm_GIN_인덱스를_생성한다() {
		assertEquals(
			1,
			jdbc.queryForObject("select count(*) from pg_extension where extname = 'pg_trgm'", Integer.class));

		String indexDefinition = jdbc.queryForObject(
			"select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
			String.class,
			GAME_NAME_TRIGRAM_INDEX);

		assertNotNull(indexDefinition);
		assertTrue(indexDefinition.contains("USING gin"));
		assertTrue(indexDefinition.contains("lower"));
		assertTrue(indexDefinition.contains("gin_trgm_ops"));
	}

	@Test
	void 한글자_검색_결과와_세글자_부분일치_GIN_경로를_보존한다() {
		insertGame(1L, "가나다");
		insertGame(2L, "가방");
		insertGame(3L, "나가자");
		for (long number = 4L; number <= 20_003L; number++) {
			insertGame(number, "샘플 이름 " + number);
		}
		for (long number = 20_004L; number <= 20_006L; number++) {
			insertGame(number, "보드게임 후보 " + number);
		}
		jdbc.execute("analyze games");

		assertEquals(
			List.of(1L, 2L, 3L),
			jdbc.queryForList(
				"select id from games where lower(name) like lower(?) order by id",
				Long.class,
				"%가%"));
		assertEquals(
			3L,
			jdbc.queryForObject("select count(*) from games where lower(name) like lower(?)", Long.class, "%가%"));

		assertEquals(
			List.of(20_004L, 20_005L, 20_006L),
			jdbc.queryForList(
				"select id from games where lower(name) like lower(?) order by id",
				Long.class,
				"%보드게%"));
		assertEquals(
			3L,
			jdbc.queryForObject(
				"select count(*) from games where lower(name) like lower(?)", Long.class, "%보드게%"));

		String plan = String.join(
			"\n",
			jdbc.queryForList(
				"explain (format text) select id from games where lower(name) like lower(?)",
				String.class,
				"%보드게%"));
		String countPlan = String.join(
			"\n",
			jdbc.queryForList(
				"explain (format text) select count(*) from games where lower(name) like lower(?)",
				String.class,
				"%보드게%"));

		assertNotNull(plan);
		assertTrue(plan.contains(GAME_NAME_TRIGRAM_INDEX));
		assertTrue(countPlan.contains(GAME_NAME_TRIGRAM_INDEX));
	}

	@Test
	void ERD와_운영문서가_하이브리드_경계와_재측정_조건을_기록한다() throws Exception {
		String erd = Files.readString(Path.of("docs/ERD.md"));
		String guide = Files.readString(Path.of("docs/guides/P1_SEARCH_PERFORMANCE.md"));

		assertTrue(erd.contains(GAME_NAME_TRIGRAM_INDEX));
		assertTrue(guide.contains("1·2글자"));
		assertTrue(guide.contains("3글자 이상"));
		assertTrue(guide.contains("재측정"));
	}

	private List<Long> searchedIds(String keyword, int size, int page) {
		GameListRequest request = searchRequest(keyword, size, page);
		return gameQueryService.findPage(request, null).getContent().stream().map(game -> game.id()).toList();
	}

	private GameListRequest searchRequest(String keyword, int size, int page) {
		GameListRequest request = new GameListRequest();
		request.setKeyword(keyword);
		request.setSize(size);
		request.setPage(page);
		return request;
	}

	private long insertUser() {
		return jdbc.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values ('game-name-search@example.com', 'hash', '게임명 검색', current_timestamp, current_timestamp) returning id",
			Long.class);
	}

	private void insertGame(long id, String name) {
		insertGame(id, name, 2, 4);
	}

	private void insertGame(long id, String name, int minPlayers, int maxPlayers) {
		jdbc.update(
			"""
				insert into games(
					id, bgg_id, name, english_name, supported_player_count, tag, estimated_play_time,
					min_players, max_players, description, detail_description, created_at, updated_at
				) values (?, ?, ?, ?, '2~4명', '전략', '30분', ?, ?, '설명', '상세 설명', current_timestamp, current_timestamp)
				""",
			id,
			1_000_000L + id,
			name,
			"Game " + id,
			minPlayers,
			maxPlayers);
	}

	private long insertMechanism(long bggId, String code, boolean isPublic) {
		return jdbc.queryForObject(
			"""
					insert into game_mechanisms(
						bgg_mechanism_id, code, name_ko, name_en, description_ko, is_public,
						source_reference, reviewed_by, reviewed_at, created_at, updated_at
					) values (?, ?, ?, ?, ?, ?, 'Issue #1046', ?, case when ? then current_timestamp else null end,
						current_timestamp, current_timestamp)
					returning id
				""",
			Long.class,
			bggId,
			code,
			code,
			code,
			isPublic ? code + " 방식을 활용해요." : null,
			isPublic,
			isPublic ? "postgresTest" : null,
			isPublic);
	}

	private void linkMechanism(long gameId, long mechanismId) {
		jdbc.update(
			"insert into game_mechanism_relations (game_id, mechanism_id) values (?, ?)",
			gameId,
			mechanismId);
	}
}
