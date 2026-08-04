package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.dto.PlayedGameStateResponse;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.game.service.UserPlayedGameService;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@Testcontainers
@SpringBootTest
class UserPlayedGamePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("user_played_game_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UserPlayedGameRepository userPlayedGameRepository;
	@Autowired
	private UserPlayedGameService userPlayedGameService;

	private final List<Long> gameIds = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		userIds.forEach(userId -> gameIds.forEach(gameId -> userPlayedGameRepository.deleteByUserIdAndGameId(userId, gameId)));
		gameIds.forEach(gameRepository::deleteById);
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void PostgreSQL_Flyway와_JPA는_해본게임_관계의_identity_제약과_NO_ACTION_FK를_일치시킨다() {
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'user_played_games' and column_name = 'id' and data_type = 'bigint' and is_identity = 'YES'",
				Integer.class));
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns where table_name = 'user_played_games' and column_name = 'created_at' and is_nullable = 'NO'",
				Integer.class));
		assertEquals(
			2,
			jdbcTemplate.queryForObject(
				"select count(*) from pg_constraint where conrelid = 'user_played_games'::regclass and contype = 'f' and confdeltype = 'a'",
				Integer.class));
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from pg_constraint where conrelid = 'user_played_games'::regclass and contype = 'u'",
				Integer.class));
		assertEquals(
			"UNIQUE (user_id, game_id)",
			jdbcTemplate.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'uq_user_played_games_user_game'",
				String.class));
		assertEquals(
			"FOREIGN KEY (user_id) REFERENCES users(id)",
			jdbcTemplate.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'fk_user_played_games_user'",
				String.class));
		assertEquals(
			"FOREIGN KEY (game_id) REFERENCES games(id)",
			jdbcTemplate.queryForObject(
				"select pg_get_constraintdef(oid) from pg_constraint where conname = 'fk_user_played_games_game'",
				String.class));
	}

	@Test
	void PostgreSQL에서_동시_등록도_하나의_관계와_성공_응답으로_수렴한다() throws Exception {
		User user = user("concurrent");
		Game game = game("Concurrent");
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			List<Callable<PlayedGameStateResponse>> calls = List.of(
				markCall(ready, start, user.getId(), game.getId()),
				markCall(ready, start, user.getId(), game.getId()));
			var futures = calls.stream().map(executor::submit).toList();
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			for (var future : futures) {
				assertEquals(new PlayedGameStateResponse(game.getId(), true), future.get(20, TimeUnit.SECONDS));
			}
		}
		assertEquals(1, userPlayedGameRepository.findByUserIdAndGameId(user.getId(), game.getId()).size());
	}

	private Callable<PlayedGameStateResponse> markCall(
		CountDownLatch ready, CountDownLatch start, long userId, long gameId) {
		return () -> {
			ready.countDown();
			assertTrue(start.await(10, TimeUnit.SECONDS));
			return userPlayedGameService.markPlayed(userId, gameId);
		};
	}

	private User user(String suffix) {
		User user = userRepository.saveAndFlush(
			User.create("played-game-postgres-" + suffix + "@example.com", "{bcrypt}hash", "사용자" + suffix));
		userIds.add(user.getId());
		return user;
	}

	private Game game(String suffix) {
		Game game = gameRepository.saveAndFlush(
			new Game(
				910_000L + gameIds.size(),
				"PlayedGamePostgres-" + suffix,
				"Played game " + suffix,
				"2~4명",
				"전략",
				"30분",
				"설명",
				"상세 설명"));
		gameIds.add(game.getId());
		return game;
	}
}
