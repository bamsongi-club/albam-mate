package cloud.bamsongi.albammate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.exception.EmailAlreadyExistsException;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@Testcontainers
@SpringBootTest
@Import(PostgresSchemaValidationTest.ConcurrentSignupBarrierConfiguration.class)
class PostgresSchemaValidationTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Set<String> EXPECTED_TABLES = Set.of("users", "games", "rooms", "participations");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_test");

	@Autowired
	private Environment environment;

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private ConcurrentSignupExistsBarrier concurrentSignupExistsBarrier;

	@Test
	void 빈_PostgreSQL에_Flyway_V1_V2_V3와_Hibernate_스키마_검증이_적용된다() {
		assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));

		flyway.validate();

		Set<String> appliedVersions = jdbcTemplate
			.query(
				"select version from flyway_schema_history "
					+ "where success = true",
				(resultSet, rowNumber) -> resultSet.getString("version"))
			.stream()
			.collect(java.util.stream.Collectors.toSet());
		assertTrue(appliedVersions.containsAll(Set.of("1", "2", "3")));

		Set<String> actualTables = jdbcTemplate
			.query(
				"select table_name from information_schema.tables "
					+ "where table_schema = current_schema()",
				(resultSet, rowNumber) -> resultSet.getString("table_name"))
			.stream()
			.map(String::toLowerCase)
			.collect(java.util.stream.Collectors.toSet());
		assertTrue(actualTables.containsAll(EXPECTED_TABLES));
	}

	@Test
	void V1_V2_V3는_가능_추천_최적인원을_단계적으로_추가하고_기존_값을_보존한다() {
		String schemaName = "game_player_counts_" + UUID.randomUUID().toString().replace("-", "");
		try {
			migrate(schemaName, "1");
			assertColumn(schemaName, "supported_player_count", false);
			assertColumnMissing(schemaName, "recommended_player_count");
			assertColumnMissing(schemaName, "best_player_count");
			assertColumn(schemaName, "updated_at", false);
			assertColumn(schemaName, "participations", "created_at", false);
			assertColumn(schemaName, "participations", "updated_at", false);

			jdbcTemplate.update(
				"insert into "
					+ schemaName
					+ ".games "
					+ "(bgg_id, name, english_name, supported_player_count, tag, "
					+ "estimated_play_time, description, detail_description, created_at, updated_at) "
					+ "values (9001, '마이그레이션 테스트 게임', 'Migration Test Game', '2~4명', '전략', "
					+ "'60~90분', '설명', '상세 설명', "
					+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
					+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')");

			migrate(schemaName, "2");
			assertColumn(schemaName, "supported_player_count", false);
			assertColumn(schemaName, "recommended_player_count", true);
			assertColumnMissing(schemaName, "best_player_count");
			assertEquals("2~4명", gamePlayerCount(schemaName, "supported_player_count"));
			jdbcTemplate.update(
				"update "
					+ schemaName
					+ ".games set recommended_player_count = '3~4명' where bgg_id = 9001");

			migrate(schemaName, null);
			assertColumn(schemaName, "supported_player_count", false);
			assertColumn(schemaName, "recommended_player_count", true);
			assertColumn(schemaName, "best_player_count", true);
			assertEquals("2~4명", gamePlayerCount(schemaName, "supported_player_count"));
			assertEquals("3~4명", gamePlayerCount(schemaName, "recommended_player_count"));
		} finally {
			jdbcTemplate.execute("drop schema if exists " + schemaName + " cascade");
		}
	}

	@Test
	void PostgreSQL_연결과_기본_설정으로_컨텍스트가_기동된다() throws SQLException {
		assertEquals(
			"none", environment.getProperty("spring.datasource.embedded-database-connection"));

		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metadata = connection.getMetaData();
			assertEquals("PostgreSQL", metadata.getDatabaseProductName());
			assertEquals(18, metadata.getDatabaseMajorVersion());
		}
	}

	@Test
	void PostgreSQL_실제_INSERT는_핵심_CHECK와_FK를_거절한다() {
		insertUser(1001L, "postgres-host@example.com");
		insertUser(1002L, "postgres-participant@example.com");
		insertRoom(2001L, 1001L, 2);

		assertConstraintViolation("23514", "ck_rooms_capacity", () -> insertRoom(2002L, 1001L, 0));
		assertConstraintViolation(
			"23514",
			"ck_participations_status_canceled_at",
			() -> insertParticipation(3001L, 2001L, 1002L, "ACTIVE", true));
		assertConstraintViolation(
			"23503",
			"fk_participations_user",
			() -> insertParticipation(3002L, 2001L, 9999L, "ACTIVE", false));
		assertConstraintViolation(
			"23503",
			"fk_participations_room",
			() -> insertParticipation(3003L, 9999L, 1002L, "ACTIVE", false));
	}

	@Test
	void 독립_트랜잭션의_같은_정규화_이메일_가입_경합은_한_건만_생성한다() throws Exception {
		String token = UUID.randomUUID().toString().replace("-", "");
		String normalizedEmail = "signup-race-" + token + "@example.com";
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		concurrentSignupExistsBarrier.activate(normalizedEmail);
		try {
			List<Future<Throwable>> results = List.of(
				executor.submit(
					() -> createCompetingAccount(
						ready,
						start,
						" Signup-Race-" + token + "@Example.COM ",
						"첫 경합 사용자")),
				executor.submit(
					() -> createCompetingAccount(
						ready,
						start,
						"signup-race-" + token + "@example.com",
						"둘 경합 사용자")));
			assertTrue(ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			List<Throwable> outcomes = results.stream().map(this::getOutcome).toList();

			assertEquals(1, outcomes.stream().filter(outcome -> outcome == null).count());
			assertEquals(2, concurrentSignupExistsBarrier.falseReadCount());
			EmailAlreadyExistsException duplicate = outcomes.stream()
				.filter(EmailAlreadyExistsException.class::isInstance)
				.map(EmailAlreadyExistsException.class::cast)
				.findFirst()
				.orElseThrow();
			assertTrue(containsCause(duplicate, DataIntegrityViolationException.class));
			assertEquals(
				1,
				jdbcTemplate.queryForObject(
					"select count(*) from users where email = ?",
					Integer.class,
					normalizedEmail));
		} finally {
			concurrentSignupExistsBarrier.deactivate();
			start.countDown();
			executor.shutdownNow();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConcurrentSignupBarrierConfiguration {

		@Bean
		ConcurrentSignupExistsBarrier concurrentSignupExistsBarrier() {
			return new ConcurrentSignupExistsBarrier();
		}

		@Bean
		@Primary
		UserRepository synchronizedUserRepository(
			@Qualifier("userRepository") UserRepository delegate,
			ConcurrentSignupExistsBarrier concurrentSignupExistsBarrier) {
			return (UserRepository)Proxy.newProxyInstance(
				UserRepository.class.getClassLoader(),
				new Class<?>[] {UserRepository.class},
				(proxy, method, arguments) -> {
					try {
						Object result = method.invoke(delegate, arguments);
						if (method.getName().equals("existsByEmail")) {
							concurrentSignupExistsBarrier.awaitAfterFalseRead(
								(String)arguments[0], (boolean)result);
						}
						return result;
					} catch (InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
		}
	}

	static final class ConcurrentSignupExistsBarrier {

		private final AtomicReference<BarrierState> active = new AtomicReference<>();

		void activate(String normalizedEmail) {
			active.set(new BarrierState(normalizedEmail));
		}

		void deactivate() {
			active.set(null);
		}

		int falseReadCount() {
			BarrierState state = active.get();
			return state == null ? 0 : state.falseReadCount.get();
		}

		void awaitAfterFalseRead(String email, boolean exists) {
			BarrierState state = active.get();
			if (state == null || exists || !state.normalizedEmail.equals(email)) {
				return;
			}
			state.falseReadCount.incrementAndGet();
			try {
				state.bothFalseReads.countDown();
				if (!state.bothFalseReads.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("두 가입 요청의 사전 중복 확인을 기다리다 시간 초과했습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("가입 경합 동기화가 중단되었습니다.", exception);
			}
		}

		private static final class BarrierState {

			private final String normalizedEmail;
			private final CountDownLatch bothFalseReads = new CountDownLatch(2);
			private final AtomicInteger falseReadCount = new AtomicInteger();

			private BarrierState(String normalizedEmail) {
				this.normalizedEmail = normalizedEmail;
			}
		}
	}

	private Throwable createCompetingAccount(
		CountDownLatch ready, CountDownLatch start, String email, String nickname) {
		try {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) {
				return new AssertionError("가입 경합 시작 신호를 기다리다 시간 초과했습니다.");
			}
			userAccountService.createAccount(
				new CreateUserAccountCommand(
					UserEmail.from(email).orElseThrow(),
					RawPassword.from("123456789012345").orElseThrow(),
					UserNickname.from(nickname).orElseThrow()));
			return null;
		} catch (EmailAlreadyExistsException exception) {
			return exception;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return exception;
		} catch (RuntimeException exception) {
			return exception;
		}
	}

	private Throwable getOutcome(Future<Throwable> future) {
		try {
			return future.get(15, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private void assertConstraintViolation(
		String expectedSqlState,
		String expectedConstraint,
		org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);

		assertEquals(expectedSqlState, sqlException.getSQLState());
		assertTrue(
			containsMessage(exception, expectedConstraint),
			() -> "Expected PostgreSQL constraint in exception: " + expectedConstraint);
	}

	private boolean containsMessage(Throwable throwable, String expectedText) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(expectedText)) {
				return true;
			}
		}
		return false;
	}

	private boolean containsCause(Throwable throwable, Class<? extends Throwable> expectedType) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (expectedType.isInstance(current)) {
				return true;
			}
		}
		return false;
	}

	private SQLException findSqlException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				return sqlException;
			}
		}
		throw new AssertionError("Expected a PostgreSQL SQLException cause", throwable);
	}

	private void migrate(String schemaName, String target) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration")
			.schemas(schemaName)
			.defaultSchema(schemaName);
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}

	private void assertColumn(String schemaName, String columnName, boolean nullable) {
		assertColumn(schemaName, "games", columnName, nullable);
	}

	private void assertColumn(
		String schemaName, String tableName, String columnName, boolean nullable) {
		assertEquals(
			nullable ? "YES" : "NO",
			jdbcTemplate.queryForObject(
				"select is_nullable from information_schema.columns "
					+ "where table_schema = ? and table_name = ? and column_name = ?",
				String.class,
				schemaName,
				tableName,
				columnName));
	}

	private void assertColumnMissing(String schemaName, String columnName) {
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from information_schema.columns "
					+ "where table_schema = ? and table_name = 'games' and column_name = ?",
				Integer.class,
				schemaName,
				columnName));
	}

	private String gamePlayerCount(String schemaName, String columnName) {
		return jdbcTemplate.queryForObject(
			"select " + columnName + " from " + schemaName + ".games where bgg_id = 9001",
			String.class);
	}

	private void insertUser(long id, String email) {
		jdbcTemplate.update(
			"insert into users "
				+ "(id, email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, ?, 'postgres-test-hash', 'PostgreSQL 테스트 사용자', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
			id,
			email);
	}

	private void insertRoom(long id, long hostUserId, int capacity) {
		jdbcTemplate.update(
			"insert into rooms "
				+ "(id, host_user_id, room_type, title, experience_level, "
				+ "is_rulemaster_led, capacity, active_participant_count, start_at, place, "
				+ "status, created_at, updated_at) "
				+ "values (?, ?, 'PERSON_FOCUSED', 'PostgreSQL 제약 테스트 방', 'ALL_LEVELS', "
				+ "true, ?, 0, TIMESTAMP WITH TIME ZONE '2026-07-27T01:00:00Z', "
				+ "'PostgreSQL 테스트 장소', 'RECRUITING', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
			id,
			hostUserId,
			capacity);
	}

	private void insertParticipation(
		long id, long roomId, long userId, String status, boolean canceledAtPresent) {
		String canceledAt = canceledAtPresent ? "TIMESTAMP WITH TIME ZONE '2026-07-27T02:00:00Z'" : "NULL";
		jdbcTemplate.update(
			"insert into participations "
				+ "(id, room_id, user_id, status, joined_at, canceled_at, created_at, updated_at) "
				+ "values (?, ?, ?, ?, TIMESTAMP WITH TIME ZONE '2026-07-27T01:30:00Z', "
				+ canceledAt
				+ ", TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
			id,
			roomId,
			userId,
			status);
	}
}
