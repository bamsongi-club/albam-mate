package cloud.bamsongi.albammate.matching.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.recovery.MatchPartyLifecycleExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchPreparingRecoveryExecutor;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalCoordinator;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;
import cloud.bamsongi.albammate.matching.service.query.MatchCurrentStateQueryCoordinator;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = "spring.task.scheduling.enabled=false")
class MatchLifecycleConcurrencyPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MatchProposalCoordinator primaryMatcher;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table match_idempotency_records, match_proposal_members, match_proposals, "
			+ "match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void 서로_다른_두_matcher_인스턴스가_후보를_한번만_claim하고_전원_ACCEPT를_하나의_PREPARING_Party로_확정한다()
		throws Exception {
		long firstUserId = insertUser("first");
		long secondUserId = insertUser("second");
		long firstRequestId = insertWaitingRequest(firstUserId, 10);
		long secondRequestId = insertWaitingRequest(secondUserId, 20);

		Match746IndependentApplicationProcess.runConcurrently(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
			Match746IndependentApplicationProcess.command("claim"),
			Match746IndependentApplicationProcess.command("claim"));
		assertTrue(jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class) <= 1);
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from match_proposals proposal
			where proposal.party_size <> (
				select count(*) from match_proposal_members member where member.proposal_id = proposal.id)
			""", Integer.class));
		// 두 독립 matcher가 서로의 anchor를 SKIP LOCKED로 건너뛴 turn도 다음 claim에서 같은 후보를 한 번만 처리한다.
		primaryMatcher.claimAvailableCandidates();

		long proposalId = jdbcTemplate.queryForObject("select id from match_proposals", Long.class);
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class));
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from match_proposal_members where proposal_id = ?", Integer.class, proposalId));
		assertEquals("PROPOSED", requestStatus(firstRequestId));
		assertEquals("PROPOSED", requestStatus(secondRequestId));

		Match746IndependentApplicationProcess.runConcurrently(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
			Match746IndependentApplicationProcess.command(
				"respond", String.valueOf(firstUserId), String.valueOf(proposalId), "ACCEPT",
				"primary-" + UUID.randomUUID()),
			Match746IndependentApplicationProcess.command(
				"respond", String.valueOf(secondUserId), String.valueOf(proposalId), "ACCEPT",
				"secondary-" + UUID.randomUUID()));

		assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
			"select status from match_proposals", String.class));
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
		assertEquals("PREPARING", jdbcTemplate.queryForObject("select status from match_parties", String.class));
		assertEquals(2, jdbcTemplate.queryForObject("select count(*) from match_party_participants", Integer.class));
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from match_requests where status = 'MATCHED'", Integer.class));
	}

	private long insertUser(String name) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, "match-integration-" + name + "-" + UUID.randomUUID() + "@example.com", name,
			Timestamp.from(now), Timestamp.from(now));
	}

	private long insertWaitingRequest(long userId, int priorityOffset) {
		Instant queuedAt = Instant.parse("2026-08-22T00:00:00Z").plusSeconds(priorityOffset);
		return jdbcTemplate.queryForObject("""
			insert into match_requests
			(user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at)
			values (?, 2, 2, 'WAITING', ?, ?, ?, ?)
			returning id
			""", Long.class, userId, Timestamp.from(queuedAt), Timestamp.from(queuedAt), Timestamp.from(queuedAt),
			Timestamp.from(queuedAt));
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	static final class Match746IndependentApplicationProcess {

		private static final String PASSWORD_ENVIRONMENT_VARIABLE = "MATCH746_JDBC_PASSWORD";
		private static final int PROCESS_TIMEOUT_SECONDS = 90;

		private Match746IndependentApplicationProcess() {}

		static WorkerCommand command(String action, String... arguments) {
			return new WorkerCommand(action, List.copyOf(Arrays.asList(arguments)), null, null);
		}

		static WorkerCommand serverCommand(String redisHost, int redisPort) {
			return new WorkerCommand("server", List.of(), redisHost, String.valueOf(redisPort));
		}

		static RunningServer startServer(
			String jdbcUrl,
			String jdbcUsername,
			String jdbcPassword,
			String redisHost,
			int redisPort) throws Exception {
			List<LaunchedWorker> launchedWorkers = new ArrayList<>();
			List<WorkerConnection> connections = new ArrayList<>();
			try (ServerSocket barrier = new ServerSocket(0)) {
				barrier.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
				launchedWorkers.add(startWorker(
					jdbcUrl, jdbcUsername, jdbcPassword, barrier.getLocalPort(), serverCommand(redisHost, redisPort)));
				WorkerConnection connection = awaitConnection(barrier);
				connections.add(connection);
				if (connection.serverPort() <= 0) {
					throw new IllegalStateException("독립 서버 프로세스가 HTTP port를 보고하지 않았습니다.");
				}
				connection.writer().write("GO\n");
				connection.writer().flush();
				return new RunningServer(
					launchedWorkers.get(0), connection, URI.create("http://127.0.0.1:" + connection.serverPort()));
			} catch (Exception exception) {
				cleanup(connections, launchedWorkers);
				throw exception;
			}
		}

		static void stopServer(RunningServer server) throws Exception {
			try {
				server.connection().writer().write("STOP\n");
				server.connection().writer().flush();
				String result = server.connection().reader().readLine();
				if (!"DONE|STOPPED".equals(result)) {
					throw new IllegalStateException("독립 서버 프로세스 종료 신호가 올바르지 않습니다: " + result);
				}
				awaitProcess(server.worker());
			} finally {
				cleanup(List.of(server.connection()), List.of(server.worker()));
			}
		}

		static void runConcurrently(
			String jdbcUrl,
			String jdbcUsername,
			String jdbcPassword,
			WorkerCommand... commands) throws Exception {
			if (commands.length == 0) {
				throw new IllegalArgumentException("독립 애플리케이션 프로세스 명령이 필요합니다.");
			}

			List<LaunchedWorker> launchedWorkers = new ArrayList<>();
			List<WorkerConnection> connections = new ArrayList<>();
			try (ServerSocket barrier = new ServerSocket(0)) {
				barrier.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
				for (WorkerCommand command : commands) {
					launchedWorkers
						.add(startWorker(jdbcUrl, jdbcUsername, jdbcPassword, barrier.getLocalPort(), command));
				}

				for (int index = 0; index < commands.length; index++) {
					connections.add(awaitConnection(barrier));
				}
				long distinctPidCount = connections.stream().map(WorkerConnection::pid).distinct().count();
				if (distinctPidCount != commands.length) {
					throw new IllegalStateException("독립 애플리케이션 프로세스 PID가 중복되었습니다.");
				}

				for (WorkerConnection connection : connections) {
					connection.writer().write("GO\n");
					connection.writer().flush();
				}

				for (WorkerConnection connection : connections) {
					String result = connection.reader().readLine();
					if (result == null || result.startsWith("ERROR|")) {
						throw new IllegalStateException("독립 애플리케이션 프로세스 실행 실패: " + result);
					}
					if (!result.startsWith("DONE|")) {
						throw new IllegalStateException("독립 애플리케이션 프로세스 완료 신호가 올바르지 않습니다: " + result);
					}
				}

				for (LaunchedWorker worker : launchedWorkers) {
					awaitProcess(worker);
				}
			} finally {
				cleanup(connections, launchedWorkers);
			}
		}

		static String runSingle(
			String jdbcUrl,
			String jdbcUsername,
			String jdbcPassword,
			WorkerCommand command) throws Exception {
			return runSingleWithResult(jdbcUrl, jdbcUsername, jdbcPassword, command);
		}

		private static String runSingleWithResult(
			String jdbcUrl,
			String jdbcUsername,
			String jdbcPassword,
			WorkerCommand command) throws Exception {
			List<LaunchedWorker> launchedWorkers = new ArrayList<>();
			List<WorkerConnection> connections = new ArrayList<>();
			try (ServerSocket barrier = new ServerSocket(0)) {
				barrier.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
				launchedWorkers.add(startWorker(jdbcUrl, jdbcUsername, jdbcPassword, barrier.getLocalPort(), command));
				connections.add(awaitConnection(barrier));
				WorkerConnection connection = connections.get(0);
				connection.writer().write("GO\n");
				connection.writer().flush();
				String result = connection.reader().readLine();
				if (result == null || result.startsWith("ERROR|")) {
					throw new IllegalStateException("독립 애플리케이션 프로세스 실행 실패: " + result);
				}
				if (!result.startsWith("DONE|")) {
					throw new IllegalStateException("독립 애플리케이션 프로세스 완료 신호가 올바르지 않습니다: " + result);
				}
				awaitProcess(launchedWorkers.get(0));
				return result.substring("DONE|".length());
			} catch (Exception exception) {
				throw new IllegalStateException(exception.getMessage() + " | " + diagnostics(launchedWorkers),
					exception);
			} finally {
				cleanup(connections, launchedWorkers);
			}
		}

		private static LaunchedWorker startWorker(
			String jdbcUrl,
			String jdbcUsername,
			String jdbcPassword,
			int barrierPort,
			WorkerCommand workerCommand) throws IOException {
			String javaExecutable = Path.of(
				System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win")
					? "java.exe" : "java")
				.toString();
			List<String> command = new ArrayList<>(List.of(
				javaExecutable,
				"-cp",
				System.getProperty("java.class.path"),
				Match746IndependentApplicationProcess.class.getName(),
				"--worker",
				"--jdbc-url", jdbcUrl,
				"--jdbc-username", jdbcUsername,
				"--barrier-port", String.valueOf(barrierPort),
				"--action", workerCommand.action()));
			for (int index = 0; index < workerCommand.arguments().size(); index++) {
				command.add("--arg" + (index + 1));
				command.add(workerCommand.arguments().get(index));
			}
			if (workerCommand.redisHost() != null) {
				command.add("--redis-host");
				command.add(workerCommand.redisHost());
				command.add("--redis-port");
				command.add(workerCommand.redisPort());
			}

			Path argumentFile = Files.createTempFile("match746-independent-process-", ".args");
			Path logPath = Files.createTempFile("match746-independent-process-", ".log");
			try {
				Files.writeString(argumentFile, command.subList(1, command.size()).stream()
					.map(Match746IndependentApplicationProcess::argumentFileLine)
					.collect(java.util.stream.Collectors.joining("\n")), StandardCharsets.UTF_8);
				ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable, "@" + argumentFile)
					.directory(new File(System.getProperty("user.dir")))
					.redirectErrorStream(true)
					.redirectOutput(logPath.toFile());
				processBuilder.environment().put(PASSWORD_ENVIRONMENT_VARIABLE, jdbcPassword);
				return new LaunchedWorker(processBuilder.start(), argumentFile, logPath);
			} catch (IOException exception) {
				Files.deleteIfExists(argumentFile);
				Files.deleteIfExists(logPath);
				throw exception;
			}
		}

		private static WorkerConnection awaitConnection(ServerSocket barrier) throws IOException {
			Socket socket = barrier.accept();
			socket.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			BufferedWriter writer = new BufferedWriter(
				new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
			if (!"CONNECTED".equals(reader.readLine())) {
				socket.close();
				throw new IOException("독립 애플리케이션 프로세스가 barrier 연결을 증명하지 않았습니다.");
			}
			String ready = reader.readLine();
			String[] fields = ready == null ? new String[0] : ready.split("\\|", -1);
			if ((fields.length != 3 && fields.length != 4) || !"READY".equals(fields[0]) || fields[1].isBlank()) {
				socket.close();
				throw new IOException("독립 애플리케이션 프로세스 READY 신호가 올바르지 않습니다: " + ready);
			}
			int serverPort = fields.length == 4 ? Integer.parseInt(fields[3]) : -1;
			return new WorkerConnection(socket, reader, writer, Long.parseLong(fields[1]), serverPort);
		}

		private static void awaitProcess(LaunchedWorker worker) throws Exception {
			if (!worker.process().waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				throw new IllegalStateException("독립 애플리케이션 프로세스가 종료되지 않았습니다: " + diagnostic(worker));
			}
			if (worker.process().exitValue() != 0) {
				throw new IllegalStateException("독립 애플리케이션 프로세스가 실패했습니다: " + diagnostic(worker));
			}
		}

		private static void cleanup(List<WorkerConnection> connections, List<LaunchedWorker> workers) {
			for (WorkerConnection connection : connections) {
				try {
					connection.socket().close();
				} catch (IOException ignored) {
					// 프로세스 종료 뒤 소켓이 이미 닫힌 경우다.
				}
			}
			for (LaunchedWorker worker : workers) {
				if (worker.process().isAlive()) {
					worker.process().destroyForcibly();
				}
				try {
					worker.process().waitFor(5, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
				try {
					Files.deleteIfExists(worker.logPath());
				} catch (IOException ignored) {
					worker.logPath().toFile().deleteOnExit();
				}
				try {
					Files.deleteIfExists(worker.argumentFile());
				} catch (IOException ignored) {
					worker.argumentFile().toFile().deleteOnExit();
				}
			}
		}

		private static String diagnostic(LaunchedWorker worker) {
			try {
				return "pid=" + worker.process().pid() + ", exit="
					+ (worker.process().isAlive() ? "running" : worker.process().exitValue())
					+ ", output=" + new String(Files.readAllBytes(worker.logPath()), StandardCharsets.UTF_8);
			} catch (IOException exception) {
				return "pid=" + worker.process().pid() + ", output-read-failed=" + exception.getMessage();
			}
		}

		private static String diagnostics(List<LaunchedWorker> workers) {
			return workers.stream().map(Match746IndependentApplicationProcess::diagnostic)
				.collect(java.util.stream.Collectors.joining(" || "));
		}

		public static void main(String[] args) throws Exception {
			if (args.length == 0 || !"--worker".equals(args[0])) {
				throw new IllegalArgumentException("MATCH-01 독립 worker 실행 인자가 필요합니다.");
			}
			Map<String, String> values = arguments(args);
			try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(required(values, "--barrier-port")))) {
				BufferedReader reader = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
				BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
				writer.write("CONNECTED\n");
				writer.flush();
				try {
					try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
						AlbamMateApplication.class)
						.web(WebApplicationType.SERVLET)
						.properties(applicationProperties(values))
						.run(applicationArguments(values))) {
						int serverPort = serverPort(context, values);
						writer.write("READY|" + ProcessHandle.current().pid() + "|"
							+ Match746IndependentApplicationProcess.class.getName()
							+ (serverPort > 0 ? "|" + serverPort : "") + "\n");
						writer.flush();
						if (!"GO".equals(reader.readLine())) {
							throw new IllegalStateException("독립 애플리케이션 프로세스가 GO 신호를 받지 못했습니다.");
						}
						if ("server".equals(required(values, "--action"))) {
							if (!"STOP".equals(reader.readLine())) {
								throw new IllegalStateException("독립 서버 프로세스가 STOP 신호를 받지 못했습니다.");
							}
							writer.write("DONE|STOPPED\n");
						} else {
							String result = execute(context, values);
							writer.write("DONE|" + result + "\n");
						}
						writer.flush();
					}
				} catch (Exception exception) {
					writer
						.write("ERROR|" + exception.getClass().getSimpleName() + ": " + exception.getMessage() + "\n");
					writer.flush();
					throw exception;
				}
			}
		}

		private static Map<String, Object> applicationProperties(Map<String, String> values) {
			Map<String, Object> properties = new LinkedHashMap<>(Map.of(
				"spring.datasource.url", required(values, "--jdbc-url"),
				"spring.datasource.username", required(values, "--jdbc-username"),
				"spring.datasource.password", requiredEnvironmentPassword(),
				"spring.flyway.enabled", "false",
				"spring.task.scheduling.enabled", "false",
				"app.notification.relay.enabled", "false",
				"app.chat.retention.enabled", "false",
				"app.security.cookie.secure", "false"));
			if (values.containsKey("--redis-host")) {
				properties.put("app.redis.host", required(values, "--redis-host"));
				properties.put("app.redis.port", required(values, "--redis-port"));
			}
			return properties;
		}

		private static String[] applicationArguments(Map<String, String> values) {
			List<String> arguments = new ArrayList<>(List.of(
				"--server.port=0",
				"--spring.datasource.url=" + required(values, "--jdbc-url"),
				"--spring.datasource.username=" + required(values, "--jdbc-username"),
				"--spring.datasource.password=" + requiredEnvironmentPassword(),
				"--spring.flyway.enabled=false",
				"--spring.task.scheduling.enabled=false",
				"--app.notification.relay.enabled=false",
				"--app.chat.retention.enabled=false",
				"--app.security.cookie.secure=false"));
			if (values.containsKey("--redis-host")) {
				arguments.add("--spring.profiles.active=local");
				arguments.add("--app.redis.host=" + required(values, "--redis-host"));
				arguments.add("--app.redis.port=" + required(values, "--redis-port"));
			}
			return arguments.toArray(String[]::new);
		}

		private static int serverPort(ConfigurableApplicationContext context, Map<String, String> values) {
			if (!"server".equals(required(values, "--action"))) {
				return -1;
			}
			return ((ServletWebServerApplicationContext)context).getWebServer().getPort();
		}

		private static String execute(ConfigurableApplicationContext context, Map<String, String> values) {
			return switch (required(values, "--action")) {
				case "claim" -> {
					context.getBean(MatchProposalCoordinator.class).claimAvailableCandidates();
					yield "CLAIMED";
				}
				case "respond" -> {
					long userId = Long.parseLong(required(values, "--arg1"));
					long proposalId = Long.parseLong(required(values, "--arg2"));
					MatchProposalResponseAction action = MatchProposalResponseAction
						.valueOf(required(values, "--arg3"));
					context.getBean(MatchProposalResponseService.class).respond(
						userId, proposalId, action, required(values, "--arg4"));
					yield "RESPONDED";
				}
				case "preparing" -> {
					context.getBean(MatchPreparingRecoveryExecutor.class)
						.recover(Long.parseLong(required(values, "--arg1")));
					yield "PREPARING_RECOVERED";
				}
				case "lifecycle" -> {
					context.getBean(MatchPartyLifecycleExecutor.class)
						.recover(Long.parseLong(required(values, "--arg1")));
					yield "LIFECYCLE_RECOVERED";
				}
				case "current" -> {
					CurrentMatchStateResponse response = context.getBean(MatchCurrentStateQueryCoordinator.class)
						.read(Long.parseLong(required(values, "--arg1")));
					long partyId = response.chat() == null ? -1 : response.chat().partyId();
					long memberCount = response.chat() == null ? 0 : response.chat().members().size();
					yield response.state().name() + "|" + partyId + "|" + memberCount;
				}
				default -> throw new IllegalArgumentException("지원하지 않는 MATCH-01 worker action입니다.");
			};
		}

		private static Map<String, String> arguments(String[] args) {
			Map<String, String> values = new LinkedHashMap<>();
			for (int index = 1; index < args.length; index += 2) {
				if (index + 1 >= args.length || !args[index].startsWith("--")) {
					throw new IllegalArgumentException("MATCH-01 worker 인자가 올바르지 않습니다.");
				}
				values.put(args[index], args[index + 1]);
			}
			return values;
		}

		private static String required(Map<String, String> values, String key) {
			String value = values.get(key);
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(key + " 값이 필요합니다.");
			}
			return value;
		}

		private static String requiredEnvironmentPassword() {
			return required(System.getenv(), PASSWORD_ENVIRONMENT_VARIABLE);
		}

		private static String argumentFileLine(String argument) {
			return '"' + argument.replace("\"", "\\\"") + '"';
		}

		record WorkerCommand(String action, List<String> arguments, String redisHost, String redisPort) {
		}

		static final class RunningServer {

			private final LaunchedWorker worker;
			private final WorkerConnection connection;
			private final URI baseUri;

			private RunningServer(LaunchedWorker worker, WorkerConnection connection, URI baseUri) {
				this.worker = worker;
				this.connection = connection;
				this.baseUri = baseUri;
			}

			private LaunchedWorker worker() {
				return worker;
			}

			private WorkerConnection connection() {
				return connection;
			}

			URI baseUri() {
				return baseUri;
			}
		}

		private record LaunchedWorker(Process process, Path argumentFile, Path logPath) {
		}

		private record WorkerConnection(Socket socket, BufferedReader reader, BufferedWriter writer, long pid,
			int serverPort) {
		}
	}
}
