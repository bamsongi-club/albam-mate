package cloud.bamsongi.albammate.matching.measurement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MatchCandidateClaimBaselineExternalRunnerPostgresTest {

	private static final String TRUSTED_STACK_ID = "perf-test";
	private static final String TRUSTED_DATABASE = "albam_mate_external_runner";
	private static final String POSTGRES_ADMIN_ROLE = "measurement";
	private static final String TRUSTED_DATABASE_ROLE = "match01_runner";
	private static final String TRUSTED_DATABASE_PASSWORD = "match01-runner-password";
	private static final String TRUSTED_METADATA_OWNER = "match01_metadata_provisioner";
	private static final String TRUSTED_RUNNER = "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1";
	private static final String TRUSTED_RELEASE_SHA = "0123456789abcdef0123456789abcdef01234567";
	private static final String TRUSTED_METADATA_SCHEMA = "MATCH-01-EXTERNAL-TARGET-V1";
	private static String postgresEndpoint;
	private static String nonEphemeralEndpoint;

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
		cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages.postgres18())
		.withDatabaseName(TRUSTED_DATABASE)
		.withUsername(POSTGRES_ADMIN_ROLE);

	@Container
	static final PostgreSQLContainer UNPROVISIONED_POSTGRES = new PostgreSQLContainer(
		cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages.postgres18());

	@Container
	static final PostgreSQLContainer NON_EPHEMERAL_POSTGRES = new PostgreSQLContainer(
		cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages.postgres18())
		.withDatabaseName(TRUSTED_DATABASE)
		.withUsername(POSTGRES_ADMIN_ROLE);

	@BeforeAll
	static void provisionTrustedMetadata() throws Exception {
		postgresEndpoint = configureTrustedMetadata(POSTGRES, true);
		nonEphemeralEndpoint = configureTrustedMetadata(NON_EPHEMERAL_POSTGRES, false);
	}

	@Test
	void 외부_측정_설정은_비밀번호를_환경변수에서만_읽고_환경_profile을_보존한다() {
		Properties properties = validProperties();

		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration configuration = MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration
			.from(
				properties, Map.of("ISSUE775_JDBC_PASSWORD", "not-in-output"),
				"{\"stackId\":\"perf-test\",\"ephemeral\":true}");

		assertEquals("jdbc:postgresql://postgres.perf-test.albam.internal:5432/albam_mate", configuration.jdbcUrl());
		assertEquals("measurement", configuration.jdbcUsername());
		assertEquals("0123456789abcdef0123456789abcdef01234567", configuration.measuredGitSha());
		assertEquals(Path.of("build/reports/match01-external-input.json"), configuration.outputPath());
		assertEquals("{\"stackId\":\"perf-test\",\"ephemeral\":true}", configuration.environmentProfile());
		assertFalse(configuration.toString().contains("not-in-output"));
	}

	@Test
	void 외부_측정_설정은_측정_승인과_임시환경_변경_승인이_없으면_거절한다() {
		Properties missingMeasurement = validProperties();
		missingMeasurement.remove("issue775.measurement");

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				missingMeasurement, Map.of("ISSUE775_JDBC_PASSWORD", "password"), "{\"ephemeral\":true}"));

		Properties missingMutationApproval = validProperties();
		missingMutationApproval.remove("match01.external.allow-mutation");

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				missingMutationApproval, Map.of("ISSUE775_JDBC_PASSWORD", "password"), "{\"ephemeral\":true}"));
	}

	@Test
	void 외부_측정_설정은_비밀번호를_시스템_속성으로_받지_않는다() {
		Properties properties = validProperties();
		properties.setProperty("match01.external.jdbc-password", "password-in-property");

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				properties, Map.of(), "{\"ephemeral\":true}"));
	}

	@Test
	void 외부_측정_환경_profile은_허용된_비밀값_없는_JSON_필드만_보존한다() {
		Properties properties = validProperties();

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				properties, Map.of("ISSUE775_JDBC_PASSWORD", "password"),
				"{\"stackId\":\"perf-test\",\"ephemeral\":true,\"apiKey\":\"secret\"}"));
	}

	@Test
	void 외부_측정은_dirty_worktree를_거절한다() {
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.requireCleanWorktree(" M src/example.java\n?? secret.txt"));
	}

	@Test
	void trusted_서버_메타데이터가_없거나_ephemeral이_아니면_외부_mutation을_거절한다() throws Exception {
		assertDoesNotThrow(() -> verifyPostgreSql(dataSource(UNPROVISIONED_POSTGRES)));
		assertThrows(IllegalArgumentException.class,
			() -> verifyTrustedTarget(dataSource(UNPROVISIONED_POSTGRES), validConfiguration(UNPROVISIONED_POSTGRES,
				"jdbc:postgresql://" + serverEndpoint(UNPROVISIONED_POSTGRES) + "/" + TRUSTED_DATABASE)));
		assertThrows(IllegalArgumentException.class,
			() -> verifyTrustedTarget(runnerDataSource(NON_EPHEMERAL_POSTGRES),
				validConfiguration(NON_EPHEMERAL_POSTGRES,
					"jdbc:postgresql://" + nonEphemeralEndpoint + "/" + TRUSTED_DATABASE)));
	}

	@Test
	void JDBC_endpoint와_database_identity가_trusted_메타데이터와_모두_일치할_때만_승인한다() throws Exception {
		DriverManagerDataSource dataSource = runnerDataSource(POSTGRES);
		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration configuration = validConfiguration(
			POSTGRES, "jdbc:postgresql://" + postgresEndpoint + "/" + TRUSTED_DATABASE);

		assertDoesNotThrow(() -> verifyTrustedTarget(dataSource, configuration));

		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration endpointMismatch = validConfiguration(
			POSTGRES,
			"jdbc:postgresql://different-host:5432/" + TRUSTED_DATABASE);
		assertThrows(IllegalArgumentException.class,
			() -> verifyTrustedTarget(dataSource, endpointMismatch));

		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration databaseMismatch = validConfiguration(
			POSTGRES,
			"jdbc:postgresql://" + postgresEndpoint + "/different_database");
		assertThrows(IllegalArgumentException.class,
			() -> verifyTrustedTarget(dataSource, databaseMismatch));
	}

	@Test
	void provisioner_owned_metadata는_runner가_수정할_수_없는_read_only_경계다() throws Exception {
		try (Connection connection = runnerDataSource(POSTGRES).getConnection();
			var query = connection.createStatement()) {
			assertTrue(query.executeQuery(
				"select has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'SELECT')")
				.next());
			assertThrows(java.sql.SQLException.class,
				() -> query
					.executeUpdate("update match01_control.match01_external_target_metadata set stack_id = 'forged'"));
		}
	}

	@Test
	void worker_연결_초기화는_각_물리_연결의_trusted_identity를_검증한다() throws Exception {
		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration configuration = validConfiguration(
			POSTGRES, "jdbc:postgresql://" + postgresEndpoint + "/" + TRUSTED_DATABASE);
		Object metadata;
		String connectionInitSql;
		try (Connection connection = runnerDataSource(POSTGRES).getConnection()) {
			java.lang.reflect.Method verify = MatchCandidateClaimBaselineExternalRunner.class.getDeclaredMethod(
				"verifyTrustedTarget", Connection.class,
				MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.class);
			verify.setAccessible(true);
			metadata = invokeReturning(verify, connection, configuration);
			java.lang.reflect.Method buildSql = MatchCandidateClaimBaselineExternalRunner.class.getDeclaredMethod(
				"workerConnectionInitSql", metadata.getClass());
			buildSql.setAccessible(true);
			connectionInitSql = (String)invokeReturning(buildSql, metadata);
			try (var statement = connection.createStatement()) {
				assertDoesNotThrow(() -> statement.execute(connectionInitSql));
			}
		}

		try (Connection connection = dataSource(UNPROVISIONED_POSTGRES).getConnection();
			var statement = connection.createStatement()) {
			assertThrows(java.sql.SQLException.class, () -> statement.execute(connectionInitSql));
		}
	}

	private MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration validConfiguration(
		PostgreSQLContainer postgres, String jdbcUrl) {
		Properties properties = validProperties();
		properties.setProperty("match01.external.jdbc-url", jdbcUrl);
		properties.setProperty("match01.external.jdbc-username", TRUSTED_DATABASE_ROLE);
		return MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
			properties, Map.of("ISSUE775_JDBC_PASSWORD", TRUSTED_DATABASE_PASSWORD),
			"{\"stackId\":\"" + TRUSTED_STACK_ID + "\",\"runner\":\"" + TRUSTED_RUNNER
				+ "\",\"databaseRole\":\"" + TRUSTED_DATABASE_ROLE + "\",\"ephemeral\":true,\"releaseSha\":\""
				+ TRUSTED_RELEASE_SHA + "\"}");
	}

	private static String configureTrustedMetadata(PostgreSQLContainer postgres, boolean ephemeral) throws Exception {
		try (Connection connection = dataSource(postgres).getConnection(); var query = connection.createStatement()) {
			String database;
			String serverAddress;
			int serverPort;
			try (var resultSet = query.executeQuery(
				"select current_database(), inet_server_addr()::text, inet_server_port()")) {
				assertTrue(resultSet.next());
				database = resultSet.getString(1);
				serverAddress = normalizeServerAddress(resultSet.getString(2));
				serverPort = resultSet.getInt(3);
			}
			String endpoint = serverEndpoint(serverAddress, serverPort);
			query.execute("create role " + TRUSTED_METADATA_OWNER + " nologin nosuperuser nocreatedb nocreaterole");
			query
				.execute("create role " + TRUSTED_DATABASE_ROLE + " login password '" + TRUSTED_DATABASE_PASSWORD + "' "
					+ "nosuperuser nocreatedb nocreaterole");
			query.execute("create schema match01_control authorization " + TRUSTED_METADATA_OWNER);
			query.execute("create table match01_control.match01_external_target_metadata ("
				+ "metadata_id text primary key, schema_version text not null, stack_id text not null, "
				+ "database_name text not null, database_role text not null, database_user text not null, "
				+ "jdbc_endpoint text not null, server_address text not null, server_port integer not null, "
				+ "runner text not null, ephemeral boolean not null, release_sha text not null)");
			try (var insert = connection.prepareStatement(
				"insert into match01_control.match01_external_target_metadata "
					+ "(metadata_id, schema_version, stack_id, database_name, database_role, database_user, "
					+ "jdbc_endpoint, server_address, server_port, runner, ephemeral, release_sha) "
					+ "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
				insert.setString(1, TRUSTED_RUNNER);
				insert.setString(2, TRUSTED_METADATA_SCHEMA);
				insert.setString(3, TRUSTED_STACK_ID);
				insert.setString(4, database);
				insert.setString(5, TRUSTED_DATABASE_ROLE);
				insert.setString(6, TRUSTED_DATABASE_ROLE);
				insert.setString(7, endpoint);
				insert.setString(8, serverAddress);
				insert.setInt(9, serverPort);
				insert.setString(10, TRUSTED_RUNNER);
				insert.setBoolean(11, ephemeral);
				insert.setString(12, TRUSTED_RELEASE_SHA);
				insert.executeUpdate();
			}
			query.execute(
				"alter table match01_control.match01_external_target_metadata owner to " + TRUSTED_METADATA_OWNER);
			query.execute("grant usage on schema match01_control to " + TRUSTED_DATABASE_ROLE);
			query.execute(
				"grant select on table match01_control.match01_external_target_metadata to " + TRUSTED_DATABASE_ROLE);
			return endpoint;
		}
	}

	private static String serverEndpoint(PostgreSQLContainer postgres) throws Exception {
		try (Connection connection = dataSource(postgres).getConnection();
			var statement = connection.createStatement();
			var resultSet = statement.executeQuery("select inet_server_addr()::text, inet_server_port()")) {
			assertTrue(resultSet.next());
			return serverEndpoint(normalizeServerAddress(resultSet.getString(1)), resultSet.getInt(2));
		}
	}

	private static String serverEndpoint(String address, int port) {
		return address.contains(":") && !address.startsWith("[") ? "[" + address + "]:" + port : address + ":" + port;
	}

	private static String normalizeServerAddress(String address) {
		int maskSeparator = address.indexOf('/');
		return maskSeparator < 0 ? address : address.substring(0, maskSeparator);
	}

	private static DriverManagerDataSource dataSource(PostgreSQLContainer postgres) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.postgresql.Driver");
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUsername(postgres.getUsername());
		dataSource.setPassword(postgres.getPassword());
		return dataSource;
	}

	private static DriverManagerDataSource runnerDataSource(PostgreSQLContainer postgres) {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.postgresql.Driver");
		dataSource.setUrl(postgres.getJdbcUrl());
		dataSource.setUsername(TRUSTED_DATABASE_ROLE);
		dataSource.setPassword(TRUSTED_DATABASE_PASSWORD);
		return dataSource;
	}

	private void verifyPostgreSql(DriverManagerDataSource dataSource) throws Exception {
		java.lang.reflect.Method method = MatchCandidateClaimBaselineExternalRunner.class
			.getDeclaredMethod("verifyPostgreSql", DriverManagerDataSource.class);
		method.setAccessible(true);
		invoke(method, dataSource);
	}

	private void verifyTrustedTarget(
		DriverManagerDataSource dataSource,
		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration configuration) throws Exception {
		java.lang.reflect.Method method = MatchCandidateClaimBaselineExternalRunner.class.getDeclaredMethod(
			"verifyTrustedTarget", DriverManagerDataSource.class,
			MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.class);
		method.setAccessible(true);
		invoke(method, dataSource, configuration);
	}

	private void invoke(java.lang.reflect.Method method, Object... arguments) throws Exception {
		try {
			method.invoke(null, arguments);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			if (exception.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw exception;
		}
	}

	private Object invokeReturning(java.lang.reflect.Method method, Object... arguments) throws Exception {
		try {
			return method.invoke(null, arguments);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			if (exception.getCause() instanceof Exception cause) {
				throw cause;
			}
			throw exception;
		}
	}

	private Properties validProperties() {
		Properties properties = new Properties();
		properties.setProperty("issue775.measurement", "true");
		properties.setProperty("match01.external.allow-mutation", "true");
		properties.setProperty("match01.external.jdbc-url",
			"jdbc:postgresql://postgres.perf-test.albam.internal:5432/albam_mate");
		properties.setProperty("match01.external.jdbc-username", "measurement");
		properties.setProperty("match01.external.measured-git-sha",
			"0123456789abcdef0123456789abcdef01234567");
		properties.setProperty("match01.external.output",
			"build/reports/match01-external-input.json");
		return properties;
	}
}
