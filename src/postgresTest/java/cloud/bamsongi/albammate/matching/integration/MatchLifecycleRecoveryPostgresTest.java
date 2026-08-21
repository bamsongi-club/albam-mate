package cloud.bamsongi.albammate.matching.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.recovery.MatchPartyLifecycleExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchPreparingRecoveryExecutor;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(classes = AlbamMateApplication.class, properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false",
	"app.security.cookie.secure=false"
})
class MatchLifecycleRecoveryPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final Pattern MESSAGE_ID_PATTERN = Pattern.compile("\\\"messageId\\\":(\\d+)");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private MatchPreparingRecoveryExecutor preparingRecovery;
	@Autowired
	private MatchPartyLifecycleExecutor lifecycleRecovery;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void localRedisProperties(DynamicPropertyRegistry registry) {
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table match_chat_messages, match_chat_rooms, match_proposal_members, "
			+ "match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void 재시작한_인스턴스를_포함한_PREPARING_복구는_채팅을_한번만_열고_기한_초과_Party는_우선순위를_보존해_정리한다()
		throws Exception {
		long activeUserId = insertUser("active");
		long activeRequestId = insertMatchedRequest(activeUserId, Instant.parse("2026-08-22T00:00:10Z"));
		long activeProposalId = insertConfirmedProposal();
		insertAcceptedMember(activeProposalId, activeRequestId, activeUserId);
		long activePartyId = insertPreparingParty(activeProposalId, Instant.now().minusSeconds(30));
		insertParticipant(activePartyId, activeUserId);

		MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.runConcurrently(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.command(
				"preparing", String.valueOf(activePartyId)),
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.command(
				"preparing", String.valueOf(activePartyId)));
		assertEquals("ACTIVE", partyStatus(activePartyId));
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_chat_rooms where party_id = ?",
			Integer.class, activePartyId));
		assertEquals(1, eventCount(activePartyId, "CHAT_OPENED"));

		long expiredUserId = insertUser("expired");
		Instant originalPriority = Instant.parse("2026-08-22T00:00:20Z");
		long expiredRequestId = insertMatchedRequest(expiredUserId, originalPriority);
		long expiredProposalId = insertConfirmedProposal();
		insertAcceptedMember(expiredProposalId, expiredRequestId, expiredUserId);
		long expiredPartyId = insertPreparingParty(expiredProposalId, Instant.now().minusSeconds(301));
		insertParticipant(expiredPartyId, expiredUserId);
		preparingRecovery.recover(expiredPartyId);

		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class,
			expiredPartyId));
		assertEquals("WAITING", requestStatus(expiredRequestId));
		assertEquals(originalPriority,
			jdbcTemplate.queryForObject("select priority_since from match_requests where id = ?",
				Timestamp.class, expiredRequestId).toInstant());
	}

	@Test
	void 재시작과_중복_scheduler_복구가_종료_한시간_안내를_한번만_저장하고_종료된_Party에는_만들지_않는다() throws Exception {
		long duePartyId = insertActiveParty(Instant.now().plusSeconds(3_599));
		insertMatchChatRoom(duePartyId);
		MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.runConcurrently(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.command(
				"lifecycle", String.valueOf(duePartyId)),
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.command(
				"lifecycle", String.valueOf(duePartyId)));
		assertEquals("ACTIVE", partyStatus(duePartyId));
		assertEquals(1, eventCount(duePartyId, "CLOSES_IN_ONE_HOUR"));

		long closedPartyId = insertActiveParty(Instant.now().minusSeconds(1));
		insertMatchChatRoom(closedPartyId);
		lifecycleRecovery.recover(closedPartyId);
		assertEquals("CLOSED", partyStatus(closedPartyId));
		assertEquals(0, eventCount(closedPartyId, "CLOSES_IN_ONE_HOUR"));
	}

	@Test
	void 서버_재시작_뒤_재연결_현재상태는_영속된_MATCH_채팅_handoff와_공개_프로필을_복구하고_연결종료를_나가기로_처리하지_않는다()
		throws Exception {
		String firstEmail = "match-recovery-ws-first-" + UUID.randomUUID() + "@example.com";
		String secondEmail = "match-recovery-ws-second-" + UUID.randomUUID() + "@example.com";
		long firstUserId = userAccountService.createAccount(command(firstEmail, "reconnect-first")).id();
		long secondUserId = userAccountService.createAccount(command(secondEmail, "reconnect-second")).id();
		long partyId = insertActiveParty(Instant.now().plusSeconds(86_400));
		insertMatchChatRoom(partyId);
		insertParticipant(partyId, firstUserId);
		insertParticipant(partyId, secondUserId);

		MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.RunningServer sender = null;
		MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.RunningServer receiver = null;
		MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.RunningServer restarted = null;
		WebSocket liveConnection = null;
		WebSocket recoveredConnection = null;
		try {
			sender = startServer();
			receiver = startServer();
			HttpClient client = loginClient(sender.baseUri(), firstEmail);
			String sessionId = cookieNamed(client, "JSESSIONID").getValue();

			LinkedBlockingQueue<String> liveFrames = new LinkedBlockingQueue<>();
			liveConnection = connect(receiver.baseUri(), partyId, null, sessionId, liveFrames);
			long firstLiveMessageId = sendMessage(client, sender.baseUri(), partyId, "교차 프로세스 메시지 1");
			assertEvent(awaitFrame(liveFrames), firstLiveMessageId);
			long secondLiveMessageId = sendMessage(client, sender.baseUri(), partyId, "교차 프로세스 메시지 2");
			assertEvent(awaitFrame(liveFrames), secondLiveMessageId);
			liveConnection.abort();
			liveConnection = null;

			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from match_party_participants where party_id = ? and left_at is not null",
				Integer.class, partyId));
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.stopServer(receiver);
			receiver = null;

			long firstMissedMessageId = sendMessage(client, sender.baseUri(), partyId, "재접속 전 누락 메시지 1");
			long secondMissedMessageId = sendMessage(client, sender.baseUri(), partyId, "재접속 전 누락 메시지 2");
			MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.stopServer(sender);
			sender = null;

			restarted = startServer();
			String currentState = MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.runSingle(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
				MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.command(
					"current", String.valueOf(firstUserId)));
			assertEquals("ACTIVE|" + partyId + "|2", currentState);

			HttpResponse<String> currentResponse = getWithSession(
				restarted.baseUri().resolve("/api/matches/current"), sessionId);
			assertEquals(200, currentResponse.statusCode(), currentResponse.body());
			assertTrue(currentResponse.body().contains("reconnect-first"), currentResponse.body());
			assertTrue(currentResponse.body().contains("reconnect-second"), currentResponse.body());

			LinkedBlockingQueue<String> recoveredFrames = new LinkedBlockingQueue<>();
			recoveredConnection = connect(
				restarted.baseUri(), partyId, secondLiveMessageId, sessionId, recoveredFrames);
			assertEvent(awaitFrame(recoveredFrames), firstMissedMessageId);
			assertEvent(awaitFrame(recoveredFrames), secondMissedMessageId);
			assertNull(recoveredFrames.poll(500, TimeUnit.MILLISECONDS));
		} finally {
			if (liveConnection != null) {
				liveConnection.abort();
			}
			if (recoveredConnection != null) {
				recoveredConnection.abort();
			}
			if (restarted != null) {
				MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.stopServer(restarted);
			}
			if (receiver != null) {
				MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.stopServer(receiver);
			}
			if (sender != null) {
				MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.stopServer(sender);
			}
		}
	}

	private MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.RunningServer startServer()
		throws Exception {
		return MatchLifecycleConcurrencyPostgresTest.Match746IndependentApplicationProcess.startServer(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), REDIS.getHost(),
			REDIS.getMappedPort(6379));
	}

	private HttpClient loginClient(URI baseUri, String email) throws Exception {
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		String csrfToken = csrfToken(get(client, baseUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> login = post(
			client, baseUri.resolve("/api/auth/login"), loginBody(email), csrfToken);
		assertEquals(200, login.statusCode(), login.body());
		return client;
	}

	private WebSocket connect(
		URI baseUri,
		long partyId,
		Long afterMessageId,
		String sessionId,
		LinkedBlockingQueue<String> receivedFrames) throws Exception {
		String afterMessageIdQuery = afterMessageId == null ? "" : "?afterMessageId=" + afterMessageId;
		String webSocketScheme = "https".equalsIgnoreCase(baseUri.getScheme()) ? "wss" : "ws";
		URI webSocketUri = URI.create(webSocketScheme + "://" + baseUri.getAuthority()
			+ "/api/matches/parties/" + partyId + "/chat/ws" + afterMessageIdQuery);
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", ALLOWED_ORIGIN)
			.buildAsync(
				webSocketUri,
				new WebSocket.Listener() {

					@Override
					public void onOpen(WebSocket webSocket) {
						webSocket.request(1);
					}

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						receivedFrames.add(data.toString());
						webSocket.request(1);
						return null;
					}
				})
			.get(10, TimeUnit.SECONDS);
	}

	private long sendMessage(HttpClient client, URI baseUri, long partyId, String content) throws Exception {
		String csrfToken = csrfToken(get(client, baseUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> response = post(
			client,
			baseUri.resolve("/api/matches/parties/" + partyId + "/chat/messages"),
			"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"" + content + "\"}",
			csrfToken);
		assertEquals(201, response.statusCode(), response.body());
		return messageId(response.body());
	}

	private String awaitFrame(LinkedBlockingQueue<String> frames) throws InterruptedException {
		String frame = frames.poll(15, TimeUnit.SECONDS);
		assertTrue(frame != null, "WebSocket frame을 받지 못했습니다.");
		return frame;
	}

	private void assertEvent(String frame, long messageId) {
		assertTrue(frame.contains("\"eventId\":" + messageId), frame);
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> getWithSession(URI uri, String sessionId) throws Exception {
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(uri).header("Cookie", "JSESSIONID=" + sessionId).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", csrfToken)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpCookie cookieNamed(HttpClient client, String name) {
		CookieManager cookieManager = (CookieManager)client.cookieHandler().orElseThrow();
		return cookieManager.getCookieStore().getCookies().stream()
			.filter(cookie -> name.equals(cookie.getName()))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private long messageId(String body) {
		Matcher matcher = MESSAGE_ID_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return Long.parseLong(matcher.group(1));
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private CreateUserAccountCommand command(String email, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(), RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}

	private long insertUser(String name) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject("insert into users (email, password_hash, nickname, created_at, updated_at) "
			+ "values (?, 'hash', ?, ?, ?) returning id", Long.class,
			"match-recovery-" + name + "-" + UUID.randomUUID() + "@example.com", name, Timestamp.from(now),
			Timestamp.from(now));
	}

	private long insertMatchedRequest(long userId, Instant priority) {
		return jdbcTemplate.queryForObject(
			"""
				insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, matched_at, created_at, updated_at)
				values (?, 1, 1, 'MATCHED', ?, ?, ?, ?, ?) returning id
				""",
			Long.class, userId, Timestamp.from(priority), Timestamp.from(priority), Timestamp.from(priority),
			Timestamp.from(priority), Timestamp.from(priority));
	}

	private long insertConfirmedProposal() {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject("""
			insert into match_proposals (party_size, status, respond_by, confirmed_at, created_at, updated_at)
			values (1, 'CONFIRMED', ?, ?, ?, ?) returning id
			""", Long.class, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private void insertAcceptedMember(long proposalId, long requestId, long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"""
				insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, responded_at, created_at, updated_at)
				values (?, ?, ?, 'ACCEPTED', ?, ?, ?)
				""",
			proposalId, requestId, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private long insertPreparingParty(long proposalId, Instant startedAt) {
		return jdbcTemplate.queryForObject("""
			insert into match_parties (proposal_id, status, preparing_started_at, created_at, updated_at)
			values (?, 'PREPARING', ?, ?, ?) returning id
			""", Long.class, proposalId, Timestamp.from(startedAt), Timestamp.from(startedAt),
			Timestamp.from(startedAt));
	}

	private long insertActiveParty(Instant closesAt) {
		Instant openedAt = closesAt.minusSeconds(86_400);
		return jdbcTemplate.queryForObject("""
			insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at)
			values ('ACTIVE', ?, ?, ?, ?, ?) returning id
			""", Long.class, Timestamp.from(openedAt), Timestamp.from(openedAt), Timestamp.from(closesAt),
			Timestamp.from(openedAt), Timestamp.from(openedAt));
	}

	private void insertParticipant(long partyId, long userId) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, UUID.randomUUID(), Timestamp.from(Instant.now()));
	}

	private void insertMatchChatRoom(long partyId) {
		Instant now = Instant.now();
		jdbcTemplate.update("insert into match_chat_rooms (party_id, created_at, updated_at) values (?, ?, ?)", partyId,
			Timestamp.from(now), Timestamp.from(now));
	}

	private String partyStatus(long partyId) {
		return jdbcTemplate.queryForObject("select status from match_parties where id = ?", String.class, partyId);
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	private int eventCount(long partyId, String eventKey) {
		return jdbcTemplate.queryForObject("""
			select count(*) from match_chat_messages message
			join match_chat_rooms room on room.id = message.match_chat_room_id
			where room.party_id = ? and message.system_event_key = ?
			""", Integer.class, partyId, eventKey);
	}

}
