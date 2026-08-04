package cloud.bamsongi.albammate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.GamePlayTimeFilter;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.service.GameQueryService;
import cloud.bamsongi.albammate.global.response.PageResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomListRequest;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.query.RoomListQueryService;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.notification.relay.enabled=false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SearchPerformancePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final long USER_ID = 9_000_001L;
	private static final long GAME_ID_BASE = 1_000_000L;
	private static final long ROOM_ID_BASE = 2_000_000L;
	private static final Instant ROOM_START = Instant.parse("2099-01-01T00:00:00Z");
	private static final String GAME_INDEX = "idx_games_max_play_time_name_id";
	private static final String ROOM_INDEX = "idx_rooms_public_start_at_id";
	private static final String ROOM_INDEX_PREDICATE = "((status)::text = ANY ((ARRAY['RECRUITING'::character varying, "
		+ "'CLOSED'::character varying])::text[]))";
	private static final int MEASUREMENT_REPETITIONS = 5;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("search_performance_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private GameQueryService gameQueryService;
	@Autowired
	private RoomListQueryService roomListQueryService;
	@LocalServerPort
	private int port;

	@BeforeEach
	void setUp() {
		assertFinalSchema();
		jdbcTemplate.execute(
			"truncate table user_played_games, game_mechanism_relations, game_mechanisms, rooms, games, users restart identity cascade");
		seedFixture();
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table user_played_games, game_mechanism_relations, game_mechanisms, rooms, games, users restart identity cascade");
		assertFinalSchema();
	}

	@Test
	void 게임_검색_고정_시나리오는_반복해도_내용과_전체건수가_같다() {
		gameRequests().forEach(this::assertRepeatedGameResult);
	}

	@Test
	void 방_검색_고정_시나리오는_반복해도_내용과_전체건수가_같다() {
		roomRequests().forEach(this::assertRepeatedRoomResult);
	}

	@Test
	void 고정_fixture는_게임과_방_관계_분포를_재현한다() {
		assertEquals(2_000, count("games"));
		assertEquals(1_000, count("rooms"));
		assertEquals(189, count("game_mechanisms"));
		assertEquals(13_263, count("game_mechanism_relations"));
		assertEquals(1_998,
			jdbcTemplate.queryForObject("select count(distinct game_id) from game_mechanism_relations", Integer.class));
		assertEquals(500, count("user_played_games"));
		assertEquals(1,
			jdbcTemplate.queryForObject("select count(*) from games where min_players = 1 and max_players = 1",
				Integer.class));
		assertEquals(1,
			jdbcTemplate.queryForObject("select count(*) from games where min_players = 2 and max_players = 2",
				Integer.class));
		assertEquals(200,
			jdbcTemplate.queryForObject("select count(*) from games where min_players = 1 and max_players = 10",
				Integer.class));
		assertEquals(1_798,
			jdbcTemplate.queryForObject("select count(*) from games where min_players = 2 and max_players = 4",
				Integer.class));
		assertEquals(100,
			jdbcTemplate.queryForObject("select count(*) from games where max_play_time_minutes = 10", Integer.class));
		assertEquals(300,
			jdbcTemplate.queryForObject("select count(*) from games where max_play_time_minutes = 30", Integer.class));
		assertEquals(400,
			jdbcTemplate.queryForObject("select count(*) from games where max_play_time_minutes = 45", Integer.class));
		assertEquals(400,
			jdbcTemplate.queryForObject("select count(*) from games where max_play_time_minutes = 60", Integer.class));
		assertEquals(400,
			jdbcTemplate.queryForObject("select count(*) from games where max_play_time_minutes = 75", Integer.class));
		assertEquals(400,
			jdbcTemplate.queryForObject("select count(*) from games where max_play_time_minutes = 90", Integer.class));
		assertEquals(400,
			jdbcTemplate.queryForObject("select count(*) from games where complexity = 5.00", Integer.class));
		assertEquals(1_600,
			jdbcTemplate.queryForObject("select count(*) from games where complexity = 2.00", Integer.class));
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from (select g.id from games g left join game_mechanism_relations r on g.id = r.game_id group by g.id having count(r.mechanism_id) = 0) distribution",
			Integer.class));
		assertEquals(723, jdbcTemplate.queryForObject(
			"select count(*) from (select game_id from game_mechanism_relations group by game_id having count(*) = 6) distribution",
			Integer.class));
		assertEquals(1_275, jdbcTemplate.queryForObject(
			"select count(*) from (select game_id from game_mechanism_relations group by game_id having count(*) = 7) distribution",
			Integer.class));
		assertEquals(500, jdbcTemplate.queryForObject(
			"select count(*) from user_played_games where user_id = " + USER_ID, Integer.class));
		assertEquals(400, jdbcTemplate
			.queryForObject("select count(*) from rooms where status in ('RECRUITING', 'CLOSED')", Integer.class));
		assertEquals(500,
			jdbcTemplate.queryForObject("select count(*) from rooms where room_type = 'GAME_FOCUSED'", Integer.class));
		assertEquals(500, jdbcTemplate.queryForObject("select count(*) from rooms where room_type = 'PERSON_FOCUSED'",
			Integer.class));
		assertEquals(200,
			jdbcTemplate.queryForObject("select count(*) from rooms where status = 'RECRUITING'", Integer.class));
		assertEquals(200,
			jdbcTemplate.queryForObject("select count(*) from rooms where status = 'CLOSED'", Integer.class));
		assertEquals(300,
			jdbcTemplate.queryForObject("select count(*) from rooms where status = 'CANCELED'", Integer.class));
		assertEquals(300,
			jdbcTemplate.queryForObject("select count(*) from rooms where status = 'FINISHED'", Integer.class));
		assertEquals(750, jdbcTemplate.queryForObject(
			"select count(*) from rooms where capacity - active_participant_count >= 4", Integer.class));
		assertEquals(333, jdbcTemplate
			.queryForObject("select count(*) from rooms where experience_level = 'BEGINNER_WELCOME'", Integer.class));
		assertEquals(334, jdbcTemplate
			.queryForObject("select count(*) from rooms where experience_level = 'ALL_LEVELS'", Integer.class));
		assertEquals(333, jdbcTemplate.queryForObject(
			"select count(*) from rooms where experience_level = 'EXPERIENCED_PREFERRED'", Integer.class));
		assertEquals(500,
			jdbcTemplate.queryForObject("select count(*) from rooms where is_rulemaster_led", Integer.class));
	}

	@Test
	void 대표_SQL은_같은_fixture에서_실행계획과_수집항목을_반복_가능하게_반환한다() {
		List<Scenario> scenarios = scenarios();
		try {
			dropCandidateIndexes();
			List<Measurement> baseline = measureAll("baseline", scenarios);
			measureServiceWholeCalls("baseline");
			measureHttpCalls("baseline");
			createCandidateIndexes();
			List<Measurement> after = measureAll("candidate-after", scenarios);

			assertEquals(baseline.stream().map(Measurement::ids).toList(),
				after.stream().map(Measurement::ids).toList());
			assertEquals(baseline.stream().map(Measurement::count).toList(),
				after.stream().map(Measurement::count).toList());
			assertTrue(after.stream().anyMatch(measurement -> measurement.contentPlan().contains(GAME_INDEX)));
			assertTrue(after.stream().anyMatch(measurement -> measurement.contentPlan().contains(ROOM_INDEX)));
			measureServiceWholeCalls("candidate-after");
			dropCandidateIndexes();
			createRoomIndex();
			measureHttpCalls("final-room-index");
		} finally {
			restoreFinalSchema();
		}
	}

	@Test
	void 최종_방_인덱스_적용뒤에도_게임_결과와_name_id_정렬_페이지가_같다() {
		try {
			dropRoomIndex();
			List<Page<GameListItem>> before = gameRequests().stream().map(this::gamePage).toList();
			createRoomIndex();
			List<Page<GameListItem>> after = gameRequests().stream().map(this::gamePage).toList();

			for (int index = 0; index < before.size(); index++) {
				assertSameGamePage(before.get(index), after.get(index));
			}
		} finally {
			restoreFinalSchema();
		}
	}

	@Test
	void 방_인덱스_적용뒤에도_결과와_startsAt_id_정렬_페이지가_같다() {
		try {
			dropRoomIndex();
			List<PageResponse<PublicRoomResponse>> before = roomRequests().stream().map(this::roomPage).toList();
			createRoomIndex();
			List<PageResponse<PublicRoomResponse>> after = roomRequests().stream().map(this::roomPage).toList();

			for (int index = 0; index < before.size(); index++) {
				assertSameRoomPage(before.get(index), after.get(index));
			}
		} finally {
			restoreFinalSchema();
		}
	}

	@Test
	@Order(1)
	void PostgreSQL_Flyway_인덱스가_존재하고_대표_계획에서_사용된다() {
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from flyway_schema_history where version = '16' and success = true and script = 'V16__add_p1_search_indexes.sql'",
			Integer.class));
		assertIndexExists(ROOM_INDEX);
		assertRoomIndexDefinition();
		assertFalse(indexExists(GAME_INDEX));
		assertTrue(explain(
			"select id from rooms where status in ('RECRUITING', 'CLOSED') and start_at >= timestamp with time zone '2099-01-01T00:10:00Z' order by start_at, id limit 25")
			.contains(ROOM_INDEX));
	}

	@Test
	void 보류한_후보와_재측정_조건은_측정_문서에_남는다() throws Exception {
		String document = Files.readString(Path.of("docs/guides/P1_SEARCH_PERFORMANCE.md"));
		assertTrue(document.contains("보류"));
		assertTrue(document.contains("재측정"));
		assertTrue(document.contains("player"));
		assertTrue(document.contains("mechanism"));
		assertTrue(document.contains("played"));
	}

	private void assertRepeatedGameResult(GameListRequest request) {
		Page<GameListItem> first = gamePage(request);
		Page<GameListItem> second = gamePage(request);
		assertFalse(first.isEmpty());
		assertSameGamePage(first, second);
	}

	private void assertRepeatedRoomResult(RoomListRequest request) {
		PageResponse<PublicRoomResponse> first = roomPage(request);
		PageResponse<PublicRoomResponse> second = roomPage(request);
		assertFalse(first.content().isEmpty());
		assertSameRoomPage(first, second);
	}

	private List<GameListRequest> gameRequests() {
		return List.of(
			new GameListRequest(),
			gameRequest(request -> request.setKeyword("Game-0001")),
			gameRequest(request -> request.setUpcomingOnly(true)),
			gameRequest(request -> request.setPlayerCount(4)),
			gameRequest(request -> request.setPlayerCountMin(4)),
			gameRequest(request -> request.setPlayerCountMax(1)),
			gameRequest(request -> {
				request.setPlayerCountMin(2);
				request.setPlayerCountMax(2);
				request.setPlayerCountExact(true);
			}),
			gameRequest(request -> request.setExclusivePlayerCount(List.of(1, 2))),
			gameRequest(request -> request.setPlayTime(List.of(GamePlayTimeFilter.UP_TO_10))),
			gameRequest(request -> {
				request.setComplexityMin(new BigDecimal("5.00"));
				request.setComplexityMax(new BigDecimal("5.00"));
			}),
			gameRequest(request -> request.setMechanism(List.of("DICE_ROLLING"))),
			gameRequest(request -> request.setMechanism(List.of("DICE_ROLLING", "HAND_MANAGEMENT"))),
			gameRequest(request -> request.setPlayedFilter(List.of(PlayedFilter.PLAYED_ONLY))),
			gameRequest(request -> request.setPlayedFilter(List.of(PlayedFilter.EXCLUDE_PLAYED))),
			gameRequest(request -> {
				request.setPlayerCount(4);
				request.setPlayTime(List.of(GamePlayTimeFilter.AT_LEAST_90));
				request.setMechanism(List.of("DICE_ROLLING"));
				request.setPlayedFilter(List.of(PlayedFilter.EXCLUDE_PLAYED));
			}));
	}

	private List<RoomListRequest> roomRequests() {
		return List.of(
			new RoomListRequest(),
			roomRequest(request -> request.setType(RoomType.GAME_FOCUSED)),
			roomRequest(request -> request.setGameId(GAME_ID_BASE + 2)),
			roomRequest(request -> request.setKeyword("Room-0002")),
			roomRequest(request -> request.setStartsAtFrom(ROOM_START.plusSeconds(600))),
			roomRequest(request -> request.setStartsAtTo(ROOM_START.plusSeconds(1_800))),
			roomRequest(request -> request.setMinRemainingSeats(4)),
			roomRequest(request -> request.setExperienceLevels(Set.of(ExperienceLevel.BEGINNER_WELCOME))),
			roomRequest(request -> request.setRulemasterOnly(true)),
			roomRequest(request -> {
				request.setType(RoomType.GAME_FOCUSED);
				request.setStartsAtFrom(ROOM_START.plusSeconds(600));
				request.setStartsAtTo(ROOM_START.plusSeconds(21_600));
				request.setMinRemainingSeats(3);
				request.setExperienceLevels(Set.of(ExperienceLevel.BEGINNER_WELCOME, ExperienceLevel.ALL_LEVELS));
				request.setRulemasterOnly(true);
			}));
	}

	private Page<GameListItem> gamePage(GameListRequest request) {
		return gameQueryService.findPage(request, request.getPlayedFilter() == null ? null : USER_ID);
	}

	private PageResponse<PublicRoomResponse> roomPage(RoomListRequest request) {
		return roomListQueryService.findPage(request, Optional.empty());
	}

	private void assertSameGamePage(Page<GameListItem> expected, Page<GameListItem> actual) {
		assertEquals(gameIds(expected), gameIds(actual));
		assertEquals(expected.getNumber(), actual.getNumber());
		assertEquals(expected.getSize(), actual.getSize());
		assertEquals(expected.getTotalPages(), actual.getTotalPages());
		assertEquals(expected.getTotalElements(), actual.getTotalElements());
		assertNameIdOrder(actual);
	}

	private void assertSameRoomPage(PageResponse<PublicRoomResponse> expected,
		PageResponse<PublicRoomResponse> actual) {
		assertEquals(roomIds(expected.content()), roomIds(actual.content()));
		assertEquals(expected.page(), actual.page());
		assertEquals(expected.size(), actual.size());
		assertEquals(expected.totalPages(), actual.totalPages());
		assertEquals(expected.totalElements(), actual.totalElements());
		assertStartsAtIdOrder(actual.content());
	}

	private GameListRequest gameRequest(java.util.function.Consumer<GameListRequest> customizer) {
		GameListRequest request = new GameListRequest();
		request.setSize(25);
		customizer.accept(request);
		return request;
	}

	private RoomListRequest roomRequest(java.util.function.Consumer<RoomListRequest> customizer) {
		RoomListRequest request = new RoomListRequest();
		request.setSize(25);
		customizer.accept(request);
		return request;
	}

	private List<Long> gameIds(Page<GameListItem> page) {
		return page.getContent().stream().map(GameListItem::id).toList();
	}

	private List<Long> roomIds(List<PublicRoomResponse> rooms) {
		return rooms.stream().map(PublicRoomResponse::id).toList();
	}

	private void assertNameIdOrder(Page<GameListItem> page) {
		List<String> values = page.getContent().stream().map(item -> item.name() + ":" + item.id()).toList();
		assertEquals(values.stream().sorted().toList(), values);
	}

	private void assertStartsAtIdOrder(List<PublicRoomResponse> rooms) {
		List<String> values = rooms.stream().map(room -> room.startsAt() + ":" + room.id()).toList();
		assertEquals(values.stream().sorted().toList(), values);
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
	}

	private String explain(String sql) {
		return jdbcTemplate.queryForObject("explain (analyze, buffers, format json) " + sql, String.class);
	}

	private void assertIndexExists(String index) {
		assertTrue(indexExists(index));
	}

	private void assertRoomIndexDefinition() {
		String definition = jdbcTemplate.queryForObject("select pg_get_indexdef(?::regclass)", String.class,
			ROOM_INDEX);
		assertTrue(definition.startsWith(
			"CREATE INDEX " + ROOM_INDEX + " ON public.rooms USING btree (start_at, id) WHERE "), definition);
		assertEquals(ROOM_INDEX_PREDICATE, jdbcTemplate.queryForObject(
			"select pg_get_expr(indpred, indrelid) from pg_index where indexrelid = ?::regclass", String.class,
			ROOM_INDEX));
	}

	private boolean indexExists(String index) {
		return jdbcTemplate.queryForObject(
			"select count(*) from pg_indexes where schemaname = 'public' and indexname = ?", Integer.class, index) == 1;
	}

	private List<Scenario> scenarios() {
		return List.of(
			new Scenario("game-unfiltered", "select id from games order by name, id limit 25",
				"select count(*) from games"),
			new Scenario("game-player",
				"select id from games where min_players <= 4 and max_players >= 4 order by name, id limit 25",
				"select count(*) from games where min_players <= 4 and max_players >= 4"),
			new Scenario("game-time",
				"select id from games where max_play_time_minutes <= 10 order by name, id limit 25",
				"select count(*) from games where max_play_time_minutes <= 10"),
			new Scenario("game-complexity", "select id from games where complexity = 5.00 order by name, id limit 25",
				"select count(*) from games where complexity = 5.00"),
			new Scenario("game-mechanism-one",
				"select g.id from games g where exists (select 1 from game_mechanism_relations r join game_mechanisms m on m.id = r.mechanism_id where r.game_id = g.id and m.code = 'DICE_ROLLING' and m.is_public) order by g.name, g.id limit 25",
				"select count(*) from games g where exists (select 1 from game_mechanism_relations r join game_mechanisms m on m.id = r.mechanism_id where r.game_id = g.id and m.code = 'DICE_ROLLING' and m.is_public)"),
			new Scenario("game-mechanism-or",
				"select g.id from games g where exists (select 1 from game_mechanism_relations r join game_mechanisms m on m.id = r.mechanism_id where r.game_id = g.id and m.code in ('DICE_ROLLING', 'HAND_MANAGEMENT') and m.is_public) order by g.name, g.id limit 25",
				"select count(*) from games g where exists (select 1 from game_mechanism_relations r join game_mechanisms m on m.id = r.mechanism_id where r.game_id = g.id and m.code in ('DICE_ROLLING', 'HAND_MANAGEMENT') and m.is_public)"),
			new Scenario("game-played-only",
				"select g.id from games g where exists (select 1 from user_played_games u where u.user_id = 9000001 and u.game_id = g.id) order by g.name, g.id limit 25",
				"select count(*) from games g where exists (select 1 from user_played_games u where u.user_id = 9000001 and u.game_id = g.id)"),
			new Scenario("game-exclude-played",
				"select g.id from games g where not exists (select 1 from user_played_games u where u.user_id = 9000001 and u.game_id = g.id) order by g.name, g.id limit 25",
				"select count(*) from games g where not exists (select 1 from user_played_games u where u.user_id = 9000001 and u.game_id = g.id)"),
			new Scenario("game-complex",
				"select g.id from games g where g.max_play_time_minutes >= 90 and not exists (select 1 from user_played_games u where u.user_id = 9000001 and u.game_id = g.id) order by g.name, g.id limit 25",
				"select count(*) from games g where g.max_play_time_minutes >= 90 and not exists (select 1 from user_played_games u where u.user_id = 9000001 and u.game_id = g.id)"),
			new Scenario("room-unfiltered",
				"select id from rooms where status in ('RECRUITING', 'CLOSED') order by start_at, id limit 25",
				"select count(*) from rooms where status in ('RECRUITING', 'CLOSED')"),
			new Scenario("room-type",
				"select id from rooms where status in ('RECRUITING', 'CLOSED') and room_type = 'GAME_FOCUSED' order by start_at, id limit 25",
				"select count(*) from rooms where status in ('RECRUITING', 'CLOSED') and room_type = 'GAME_FOCUSED'"),
			new Scenario("room-p1",
				"select id from rooms where status in ('RECRUITING', 'CLOSED') and start_at >= timestamp with time zone '2099-01-01T00:10:00Z' and capacity - active_participant_count >= 3 and experience_level = 'BEGINNER_WELCOME' and is_rulemaster_led order by start_at, id limit 25",
				"select count(*) from rooms where status in ('RECRUITING', 'CLOSED') and start_at >= timestamp with time zone '2099-01-01T00:10:00Z' and capacity - active_participant_count >= 3 and experience_level = 'BEGINNER_WELCOME' and is_rulemaster_led"));
	}

	private List<Measurement> measureAll(String phase, List<Scenario> scenarios) {
		List<Measurement> measurements = new ArrayList<>();
		for (Scenario scenario : scenarios) {
			jdbcTemplate.queryForList(scenario.contentSql(), Long.class);
			jdbcTemplate.queryForObject(scenario.countSql(), Long.class);
			List<Double> contentTimes = executionTimes(scenario.contentSql());
			List<Double> countTimes = executionTimes(scenario.countSql());
			String contentPlan = explain(scenario.contentSql());
			String countPlan = explain(scenario.countSql());
			Measurement measurement = new Measurement(scenario.name(),
				jdbcTemplate.queryForList(scenario.contentSql(), Long.class),
				jdbcTemplate.queryForObject(scenario.countSql(), Long.class), contentPlan, countPlan, contentTimes,
				countTimes);
			measurements.add(measurement);
			System.out.printf("SEARCH_PERF %s %s contentMs=%s median=%.3f countMs=%s median=%.3f%n", phase,
				scenario.name(), contentTimes, median(contentTimes), countTimes, median(countTimes));
			printPlanSummary(phase, scenario.name(), "content", contentPlan);
			printPlanSummary(phase, scenario.name(), "count", countPlan);
			assertPlanFields(contentPlan);
			assertPlanFields(countPlan);
		}
		return measurements;
	}

	private List<Double> executionTimes(String sql) {
		List<Double> values = new ArrayList<>();
		for (int repeat = 0; repeat < MEASUREMENT_REPETITIONS; repeat++) {
			values.add(executionTime(explain(sql)));
		}
		return values;
	}

	private void measureServiceWholeCalls(String phase) {
		GameListRequest gameRequest = gameRequest(request -> request.setPlayTime(List.of(GamePlayTimeFilter.UP_TO_10)));
		RoomListRequest roomRequest = roomRequest(request -> request.setStartsAtFrom(ROOM_START.plusSeconds(600)));
		gameQueryService.findPage(gameRequest, null);
		roomListQueryService.findPage(roomRequest, Optional.empty());
		List<Double> gameTimes = wallClockMillis(() -> gameQueryService.findPage(gameRequest, null));
		List<Double> roomTimes = wallClockMillis(() -> roomListQueryService.findPage(roomRequest, Optional.empty()));
		System.out.printf("SEARCH_PERF application-service %s gameMs=%s median=%.3f roomMs=%s median=%.3f%n",
			phase, gameTimes, median(gameTimes), roomTimes, median(roomTimes));
	}

	private void measureHttpCalls(String phase) {
		HttpClient client = HttpClient.newHttpClient();
		URI gameUri = URI.create("http://localhost:" + port + "/api/games?playTime=UP_TO_10&size=25");
		URI roomUri = URI.create("http://localhost:" + port + "/api/rooms?startsAtFrom=2099-01-01T00:10:00Z&size=25");
		get(client, gameUri);
		get(client, roomUri);
		List<Double> gameTimes = wallClockMillis(() -> get(client, gameUri));
		List<Double> roomTimes = wallClockMillis(() -> get(client, roomUri));
		System.out.printf("SEARCH_PERF http %s gameMs=%s median=%.3f roomMs=%s median=%.3f%n",
			phase, gameTimes, median(gameTimes), roomTimes, median(roomTimes));
	}

	private void get(HttpClient client, URI uri) {
		try {
			HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).GET().build(),
				HttpResponse.BodyHandlers.ofString());
			assertEquals(200, response.statusCode());
		} catch (java.io.IOException exception) {
			throw new AssertionError(exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private List<Double> wallClockMillis(Runnable call) {
		List<Double> values = new ArrayList<>();
		for (int repeat = 0; repeat < MEASUREMENT_REPETITIONS; repeat++) {
			long started = System.nanoTime();
			call.run();
			values.add((System.nanoTime() - started) / 1_000_000.0);
		}
		return values;
	}

	private double executionTime(String plan) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\"Execution Time\\\"\\s*:\\s*([0-9.]+)")
			.matcher(plan);
		assertTrue(matcher.find(), plan);
		return Double.parseDouble(matcher.group(1));
	}

	private double median(List<Double> values) {
		List<Double> sorted = values.stream().sorted().toList();
		return sorted.get(sorted.size() / 2);
	}

	private void printPlanSummary(String phase, String scenario, String queryKind, String plan) {
		System.out.printf(
			"SEARCH_PERF_PLAN %s %s %s planningMs=%s executionMs=%s totalCost=%s actualRows=%s actualLoops=%s rowsRemoved=%s sharedHit=%s sharedRead=%s%n",
			phase, scenario, queryKind,
			planNumber(plan, "Planning Time"), planNumber(plan, "Execution Time"),
			planNumber(plan, "Total Cost"), planNumber(plan, "Actual Rows"), planNumber(plan, "Actual Loops"),
			planNumber(plan, "Rows Removed by Filter"), planNumber(plan, "Shared Hit Blocks"),
			planNumber(plan, "Shared Read Blocks"));
	}

	private String planNumber(String plan, String field) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern
			.compile("\\\"" + java.util.regex.Pattern.quote(field) + "\\\"\\s*:\\s*([0-9.]+)").matcher(plan);
		return matcher.find() ? matcher.group(1) : "0";
	}

	private void dropCandidateIndexes() {
		jdbcTemplate.execute("drop index if exists " + GAME_INDEX);
		dropRoomIndex();
	}

	private void dropRoomIndex() {
		jdbcTemplate.execute("drop index if exists " + ROOM_INDEX);
	}

	private void createCandidateIndexes() {
		jdbcTemplate.execute("create index " + GAME_INDEX + " on games (max_play_time_minutes, name, id)");
		createRoomIndex();
		jdbcTemplate.execute("analyze games");
	}

	private void createRoomIndex() {
		jdbcTemplate.execute(
			"create index " + ROOM_INDEX + " on rooms (start_at, id) where status in ('RECRUITING', 'CLOSED')");
		jdbcTemplate.execute("analyze rooms");
	}

	private void restoreFinalSchema() {
		jdbcTemplate.execute("drop index if exists " + GAME_INDEX);
		if (!indexExists(ROOM_INDEX)) {
			createRoomIndex();
		}
		assertFinalSchema();
	}

	private void assertFinalSchema() {
		assertIndexExists(ROOM_INDEX);
		assertFalse(indexExists(GAME_INDEX));
	}

	private void assertPlanFields(String plan) {
		assertTrue(plan.contains("Planning Time"));
		assertTrue(plan.contains("Execution Time"));
		assertTrue(plan.contains("Shared Hit Blocks"));
	}

	private record Scenario(String name, String contentSql, String countSql) {
	}

	private record Measurement(
		String name,
		List<Long> ids,
		long count,
		String contentPlan,
		String countPlan,
		List<Double> contentTimes,
		List<Double> countTimes) {
	}

	private void seedFixture() {
		jdbcTemplate.update(
			"insert into users (id, email, password_hash, nickname, created_at, updated_at) values (?, 'search-performance@example.com', 'hash', '검색 성능', now(), now())",
			USER_ID);
		jdbcTemplate.update(
			"""
					insert into games (id, bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, min_players, max_players, min_play_time_minutes, max_play_time_minutes, complexity, description, detail_description, created_at, updated_at)
				select ? + value, 7000000 + value, 'Game-' || lpad(value::text, 4, '0'), format('Game %s', value), '2~4명', '전략', '30분',
				       case when value = 1 or value % 10 = 0 then 1 else 2 end,
				       case when value = 1 then 1 when value = 2 then 2 when value % 10 = 0 then 10 else 4 end, 10,
					       case when value % 20 = 0 then 10 else 30 + (value % 5) * 15 end,
					       case when value % 5 = 0 then 5.00 else 2.00 end, '설명', '상세 설명', now(), now()
					from generate_series(1, 2000) value
					""",
			GAME_ID_BASE);
		jdbcTemplate.update(
			"""
				insert into game_mechanisms (id, bgg_mechanism_id, code, name_ko, name_en, is_public, source_reference, reviewed_by, reviewed_at, created_at, updated_at)
				select 910000 + value, value,
				       case when value = 1 then 'DICE_ROLLING' when value = 2 then 'HAND_MANAGEMENT' else format('MECHANISM_%03s', value) end,
				       format('메커니즘 %s', value), format('Mechanism %s', value), true, 'fixture', 'tester', now(), now(), now()
				from generate_series(1, 189) value
				""");
		jdbcTemplate.update(
			"insert into game_mechanism_relations (game_id, mechanism_id) select ? + value, 910001 from generate_series(1, 1998) value",
			GAME_ID_BASE);
		jdbcTemplate.update(
			"insert into game_mechanism_relations (game_id, mechanism_id) select ? + ((value - 1) % 1998) + 1, 910000 + ((value - 1) / 1998) + 2 from generate_series(1, 11265) value",
			GAME_ID_BASE);
		jdbcTemplate.update(
			"insert into user_played_games (user_id, game_id, created_at) select ?, ? + value, now() from generate_series(1, 2000) value where value % 4 = 1",
			USER_ID, GAME_ID_BASE);
		jdbcTemplate.update(
			"""
				insert into rooms (id, game_id, host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity, active_participant_count, start_at, place, status, version, created_at, updated_at)
				select ? + value, ? + value, ?, case when value % 2 = 0 then 'GAME_FOCUSED' else 'PERSON_FOCUSED' end, 'Room-' || lpad(value::text, 4, '0'),
				       case when value % 3 = 0 then 'BEGINNER_WELCOME' when value % 3 = 1 then 'ALL_LEVELS' else 'EXPERIENCED_PREFERRED' end,
				       value % 2 = 0, '홍대', 6, value % 4, timestamp with time zone '2099-01-01T00:00:00Z' + value * interval '1 minute', '장소',
				       case when value <= 200 then 'RECRUITING' when value <= 400 then 'CLOSED' when value <= 700 then 'CANCELED' else 'FINISHED' end, 0, now(), now()
				from generate_series(1, 1000) value
				""",
			ROOM_ID_BASE, GAME_ID_BASE, USER_ID);
		jdbcTemplate.execute("analyze games");
		jdbcTemplate.execute("analyze rooms");
		jdbcTemplate.execute("analyze game_mechanism_relations");
		jdbcTemplate.execute("analyze user_played_games");
	}
}
