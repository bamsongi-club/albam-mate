package cloud.bamsongi.albammate.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.cookie.secure=false")
@Import(RoomLifecycleRealHttpIntegrationTest.FixedClockConfiguration.class)
class RoomLifecycleRealHttpIntegrationTest {

	private static final Instant FUTURE_STARTS_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final Instant NOW = Instant.parse("2026-07-31T00:00:00Z");
	private static final Instant STARTED_AT = NOW.minusSeconds(60);
	private static final Instant FIXTURE_JOINED_AT = STARTED_AT.minusSeconds(1);
	private static final String PASSWORD = "123456789012345";

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private UserRepository userRepository;

	private final List<ParticipationCleanupKey> participationCleanupKeys = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<RoomCleanupKey> roomCleanupKeys = new ArrayList<>();
	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		participationCleanupKeys.forEach(this::deleteParticipationIfPresent);
		roomIds.forEach(roomRepository::deleteById);
		roomCleanupKeys.forEach(this::deleteRoomIfPresent);
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void 생성_수정은_실제_HTTP_세션과_CSRF에서_영속_조회에_반영되고_실패하면_보존된다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		String title = "lifecycle-create-" + suffix;
		ClientSession anonymous = newClientSession();

		assertFailure(post(anonymous.client(), "/api/rooms", roomCreateBody(title), null), 401, "UNAUTHENTICATED");
		assertFalse(roomExistsWithTitle(title));

		FlowUser host = saveFlowUser("lifecycle-host-" + suffix);
		ClientSession hostSession = newClientSession();
		loginAndRefreshCsrf(hostSession, host);
		registerRoomCleanup(host.id(), title);
		JsonNode created = assertSuccess(
			post(hostSession.client(), "/api/rooms", roomCreateBody(title), hostSession.csrfToken()), 201);
		long roomId = created.path("id").asLong();
		roomIds.add(roomId);
		Room beforeUpdate = findRoom(roomId);
		String descriptionBeforeUpdate = beforeUpdate.getDescription();
		Long gameIdBeforeUpdate = beforeUpdate.getGameId();
		RoomType roomTypeBeforeUpdate = beforeUpdate.getRoomType();
		ExperienceLevel experienceLevelBeforeUpdate = beforeUpdate.getExperienceLevel();
		boolean rulemasterLedBeforeUpdate = beforeUpdate.isRulemasterLed();
		Instant startsAtBeforeUpdate = beforeUpdate.getStartAt();
		String placeBeforeUpdate = beforeUpdate.getPlace();
		String regionBeforeUpdate = beforeUpdate.getRegion();
		RoomStatus statusBeforeUpdate = beforeUpdate.getStatus();
		Long hostUserIdBeforeUpdate = beforeUpdate.getHostUserId();

		JsonNode updated = assertSuccess(patch(
			hostSession,
			"/api/rooms/" + roomId,
			objectMapper.writeValueAsString(Map.of("title", "updated-" + suffix, "recruitmentCapacity", 2))), 200);
		assertEquals("updated-" + suffix, updated.path("title").asText());
		assertEquals("RECRUITING", updated.path("status").asText());
		Room stored = findRoom(roomId);
		assertEquals("updated-" + suffix, stored.getTitle());
		assertEquals(2, stored.getCapacity());
		assertEquals(descriptionBeforeUpdate, stored.getDescription());
		assertEquals(gameIdBeforeUpdate, stored.getGameId());
		assertEquals(roomTypeBeforeUpdate, stored.getRoomType());
		assertEquals(experienceLevelBeforeUpdate, stored.getExperienceLevel());
		assertEquals(rulemasterLedBeforeUpdate, stored.isRulemasterLed());
		assertEquals(startsAtBeforeUpdate, stored.getStartAt());
		assertEquals(placeBeforeUpdate, stored.getPlace());
		assertEquals(regionBeforeUpdate, stored.getRegion());
		assertEquals(statusBeforeUpdate, stored.getStatus());
		assertEquals(hostUserIdBeforeUpdate, stored.getHostUserId());
		JsonNode detail = getData(hostSession.client(), "/api/rooms/" + roomId);
		assertEquals("updated-" + suffix, detail.path("title").asText());
		assertEquals(2, detail.path("recruitmentCapacity").asInt());
		assertEquals("RECRUITING", detail.path("status").asText());
		assertEquals("updated-" + suffix,
			findById(getData(hostSession.client(), "/api/rooms?type=PERSON_FOCUSED").path("content"), roomId)
				.path("title").asText());
		assertEquals(2,
			findById(getData(hostSession.client(), "/api/rooms?type=PERSON_FOCUSED").path("content"), roomId)
				.path("recruitmentCapacity").asInt());
		assertEquals("updated-" + suffix,
			findById(getData(hostSession.client(), "/api/users/me/rooms?role=hosted").path("content"), roomId)
				.path("title").asText());
		assertEquals(2,
			findById(getData(hostSession.client(), "/api/users/me/rooms?role=hosted").path("content"), roomId)
				.path("recruitmentCapacity").asInt());

		assertFailure(patch(
			hostSession.client(),
			"/api/rooms/" + roomId,
			objectMapper.writeValueAsString(Map.of("place", "변경되면 안 되는 장소")),
			new CsrfToken(hostSession.csrfToken().headerName(), "invalid-token")), 403, "CSRF_TOKEN_INVALID");
		assertEquals("홍대 테스트 보드게임 카페", findRoom(roomId).getPlace());
	}

	@Test
	void 수정_권한_활성_참가자와_상태_시간_실패는_저장된_방을_변경하지_않는다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		FlowUser host = saveFlowUser("update-host-" + suffix);
		FlowUser other = saveFlowUser("update-other-" + suffix);
		FlowUser participant = saveFlowUser("update-participant-" + suffix);
		Room room = saveRoom(host.id(), "update-room-" + suffix, 3, FUTURE_STARTS_AT);
		ClientSession hostSession = loggedInSession(host);
		ClientSession otherSession = loggedInSession(other);

		assertFailure(
			patch(otherSession, "/api/rooms/" + room.getId(),
				objectMapper.writeValueAsString(Map.of("title", "forbidden"))),
			403, "FORBIDDEN");
		assertEquals(room.getTitle(), findRoom(room.getId()).getTitle());

		saveActiveParticipation(room, participant.id());
		assertFailure(
			patch(hostSession, "/api/rooms/" + room.getId(),
				objectMapper.writeValueAsString(Map.of("title", "active"))),
			409, "ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS");
		assertEquals(room.getTitle(), findRoom(room.getId()).getTitle());
		assertEquals(1, findRoom(room.getId()).getActiveParticipantCount());

		Room closedRoom = saveClosedRoom(host.id(), "closed-update-" + suffix);
		assertFailure(
			patch(hostSession, "/api/rooms/" + closedRoom.getId(),
				objectMapper.writeValueAsString(Map.of("title", "closed"))),
			409, "INVALID_ROOM_STATUS_TRANSITION");
		assertEquals("closed-update-" + suffix, findRoom(closedRoom.getId()).getTitle());
		assertEquals(RoomStatus.CLOSED, findRoom(closedRoom.getId()).getStatus());

		Room startedRoom = saveRoom(host.id(), "started-update-" + suffix, 3, STARTED_AT);
		String startedRoomTitle = startedRoom.getTitle();
		Instant startedRoomStartAt = findRoom(startedRoom.getId()).getStartAt();
		assertFailure(
			patch(hostSession, "/api/rooms/" + startedRoom.getId(),
				objectMapper.writeValueAsString(Map.of("title", "started"))),
			409, "INVALID_ROOM_STATUS_TRANSITION");
		Room storedStartedRoom = findRoom(startedRoom.getId());
		assertEquals(startedRoomTitle, storedStartedRoom.getTitle());
		assertEquals(startedRoomStartAt, storedStartedRoom.getStartAt());
		assertEquals(RoomStatus.RECRUITING, storedStartedRoom.getStatus());
	}

	@Test
	void 마지막_좌석_참가와_취소_재참가는_관계와_공개_조회에_즉시_반영된다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		FlowUser host = saveFlowUser("participation-host-" + suffix);
		FlowUser participant = saveFlowUser("participation-user-" + suffix);
		FlowUser excessUser = saveFlowUser("participation-excess-" + suffix);
		Room room = saveRoom(host.id(), "participation-room-" + suffix, 1, FUTURE_STARTS_AT);
		ClientSession hostSession = loggedInSession(host);
		ClientSession participantSession = loggedInSession(participant);
		ClientSession excessSession = loggedInSession(excessUser);

		registerParticipationCleanup(room.getId(), participant.id());
		JsonNode firstJoin = assertSuccess(post(participantSession, "/api/rooms/" + room.getId() + "/participants", ""),
			201);
		long firstParticipationId = findParticipation(room.getId(), participant.id()).getId();
		assertEquals("CLOSED", firstJoin.path("roomStatus").asText());
		assertEquals(RoomStatus.CLOSED, findRoom(room.getId()).getStatus());
		assertActiveParticipation(room.getId(), participant.id());
		assertEquals("JOINED",
			getData(participantSession.client(), "/api/rooms/" + room.getId()).path("myRole").asText());
		assertEquals("CLOSED",
			findById(getData(participantSession.client(), "/api/users/me/rooms?role=joined").path("content"),
				room.getId()).path("status").asText());
		assertFalse(containsId(getData(participantSession.client(), "/api/users/me/rooms?role=hosted").path("content"),
			room.getId()));
		assertEquals(1, countId(getData(participantSession.client(), "/api/users/me/rooms?role=all").path("content"),
			room.getId()));
		assertEquals("HOST",
			findById(getData(hostSession.client(), "/api/users/me/rooms?role=hosted").path("content"), room.getId())
				.path("myRole").asText());

		assertSuccess(delete(participantSession, "/api/rooms/" + room.getId() + "/participants/me"), 200);
		assertEquals(RoomStatus.RECRUITING, findRoom(room.getId()).getStatus());
		assertCanceledParticipation(room.getId(), participant.id());
		assertFalse(containsId(getData(participantSession.client(), "/api/users/me/rooms?role=joined").path("content"),
			room.getId()));
		assertFalse(containsId(getData(participantSession.client(), "/api/users/me/rooms?role=hosted").path("content"),
			room.getId()));
		assertFalse(containsId(getData(participantSession.client(), "/api/users/me/rooms?role=all").path("content"),
			room.getId()));
		JsonNode publicDetailAfterCancel = getData(participantSession.client(), "/api/rooms/" + room.getId());
		assertFalse(publicDetailAfterCancel.has("myRole"));
		assertFalse(publicDetailAfterCancel.has("place"));
		assertFalse(publicDetailAfterCancel.has("host"));
		assertFalse(publicDetailAfterCancel.has("participants"));
		assertEquals("RECRUITING",
			getData(newClientSession().client(), "/api/rooms/" + room.getId()).path("status").asText());

		assertEquals("ACTIVE",
			assertSuccess(post(participantSession, "/api/rooms/" + room.getId() + "/participants", ""), 201)
				.path("participationStatus").asText());
		assertEquals(RoomStatus.CLOSED, findRoom(room.getId()).getStatus());
		assertActiveParticipation(room.getId(), participant.id());
		assertEquals(firstParticipationId, findParticipation(room.getId(), participant.id()).getId());
		assertEquals(1, participationCount(room.getId(), participant.id()));
		assertEquals("JOINED",
			getData(participantSession.client(), "/api/rooms/" + room.getId()).path("myRole").asText());
		assertTrue(containsId(getData(participantSession.client(), "/api/users/me/rooms?role=joined").path("content"),
			room.getId()));
		assertFalse(containsId(getData(participantSession.client(), "/api/users/me/rooms?role=hosted").path("content"),
			room.getId()));
		assertEquals(1, countId(getData(participantSession.client(), "/api/users/me/rooms?role=all").path("content"),
			room.getId()));
		assertFailure(post(participantSession, "/api/rooms/" + room.getId() + "/participants", ""), 409,
			"ALREADY_PARTICIPATING");
		assertFailure(post(excessSession, "/api/rooms/" + room.getId() + "/participants", ""), 409,
			"CAPACITY_EXCEEDED");
		assertEquals(1, findRoom(room.getId()).getActiveParticipantCount());
	}

	@Test
	void 참가_취소는_주최자와_관계_없음과_시작_이후를_구분하고_상태를_보존한다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		FlowUser host = saveFlowUser("cancel-host-" + suffix);
		FlowUser unrelated = saveFlowUser("cancel-unrelated-" + suffix);
		FlowUser lateParticipant = saveFlowUser("cancel-late-" + suffix);
		Room room = saveRoom(host.id(), "cancel-room-" + suffix, 3, FUTURE_STARTS_AT);
		ClientSession hostSession = loggedInSession(host);
		ClientSession unrelatedSession = loggedInSession(unrelated);

		assertFailure(delete(hostSession, "/api/rooms/" + room.getId() + "/participants/me"), 403, "FORBIDDEN");
		assertFailure(delete(unrelatedSession, "/api/rooms/" + room.getId() + "/participants/me"), 404,
			"PARTICIPATION_NOT_FOUND");
		assertEquals(RoomStatus.RECRUITING, findRoom(room.getId()).getStatus());
		assertEquals(0, findRoom(room.getId()).getActiveParticipantCount());

		Room startedRoom = saveRoom(host.id(), "started-cancel-room-" + suffix, 3, STARTED_AT);
		saveActiveParticipation(startedRoom, lateParticipant.id());
		ClientSession lateSession = loggedInSession(lateParticipant);
		assertFailure(delete(lateSession, "/api/rooms/" + startedRoom.getId() + "/participants/me"), 409,
			"INVALID_ROOM_STATUS_TRANSITION");
		assertActiveParticipation(startedRoom.getId(), lateParticipant.id());
		assertEquals(1, findRoom(startedRoom.getId()).getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, findRoom(startedRoom.getId()).getStatus());
	}

	@Test
	void 방_취소는_최종_상태와_관계자_공개_범위와_후속_명령을_일관되게_처리한다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		FlowUser host = saveFlowUser("room-cancel-host-" + suffix);
		FlowUser participant = saveFlowUser("room-cancel-participant-" + suffix);
		FlowUser stranger = saveFlowUser("room-cancel-stranger-" + suffix);
		Room room = saveRoom(host.id(), "room-cancel-" + suffix, 3, FUTURE_STARTS_AT);
		saveActiveParticipation(room, participant.id());
		ClientSession hostSession = loggedInSession(host);
		ClientSession participantSession = loggedInSession(participant);
		ClientSession strangerSession = loggedInSession(stranger);

		assertEquals("CANCELED",
			assertSuccess(delete(hostSession, "/api/rooms/" + room.getId()), 200).path("roomStatus").asText());
		assertEquals(RoomStatus.CANCELED, findRoom(room.getId()).getStatus());
		assertFalse(containsId(getData(newClientSession().client(), "/api/rooms").path("content"), room.getId()));
		assertEquals("CANCELED", getData(hostSession.client(), "/api/rooms/" + room.getId()).path("status").asText());
		assertEquals("CANCELED",
			getData(participantSession.client(), "/api/rooms/" + room.getId()).path("status").asText());
		assertFailure(get(strangerSession.client(), "/api/rooms/" + room.getId()), 404, "ROOM_NOT_FOUND");
		assertFailure(post(strangerSession, "/api/rooms/" + room.getId() + "/participants", ""), 409,
			"ROOM_NOT_RECRUITING");
		assertFailure(delete(hostSession, "/api/rooms/" + room.getId()), 409, "INVALID_ROOM_STATUS_TRANSITION");
		assertFailure(patch(hostSession, "/api/rooms/" + room.getId() + "/status", "{\"status\":\"FINISHED\"}"), 409,
			"INVALID_ROOM_STATUS_TRANSITION");
		assertEquals(RoomStatus.CANCELED, findRoom(room.getId()).getStatus());
	}

	@Test
	void 방_종료는_시작_전_권한_실패와_시작_후_멱등_완료_공개_범위를_검증한다() throws Exception {
		String suffix = UUID.randomUUID().toString();
		FlowUser host = saveFlowUser("finish-host-" + suffix);
		FlowUser other = saveFlowUser("finish-other-" + suffix);
		FlowUser participant = saveFlowUser("finish-participant-" + suffix);
		FlowUser newcomer = saveFlowUser("finish-newcomer-" + suffix);
		Room futureRoom = saveRoom(host.id(), "future-finish-" + suffix, 1, FUTURE_STARTS_AT);
		ClientSession hostSession = loggedInSession(host);
		ClientSession otherSession = loggedInSession(other);

		assertFailure(patch(hostSession, "/api/rooms/" + futureRoom.getId() + "/status", "{\"status\":\"FINISHED\"}"),
			409,
			"INVALID_ROOM_STATUS_TRANSITION");
		assertFailure(patch(otherSession, "/api/rooms/" + futureRoom.getId() + "/status", "{\"status\":\"FINISHED\"}"),
			403, "FORBIDDEN");
		assertEquals(RoomStatus.RECRUITING, findRoom(futureRoom.getId()).getStatus());

		Room startedRoom = saveRoom(host.id(), "started-finish-" + suffix, 1, STARTED_AT);
		saveActiveParticipation(startedRoom, participant.id());
		ClientSession participantSession = loggedInSession(participant);
		ClientSession newcomerSession = loggedInSession(newcomer);
		assertEquals("FINISHED",
			assertSuccess(
				patch(hostSession, "/api/rooms/" + startedRoom.getId() + "/status", "{\"status\":\"FINISHED\"}"), 200)
				.path("roomStatus").asText());
		Room finished = findRoom(startedRoom.getId());
		Long version = finished.getVersion();
		Instant updatedAt = finished.getUpdatedAt();
		assertEquals(RoomStatus.FINISHED, finished.getStatus());

		assertEquals("FINISHED",
			assertSuccess(
				patch(hostSession, "/api/rooms/" + startedRoom.getId() + "/status", "{\"status\":\"FINISHED\"}"), 200)
				.path("roomStatus").asText());
		Room repeatedFinished = findRoom(startedRoom.getId());
		assertEquals(version, repeatedFinished.getVersion());
		assertEquals(updatedAt, repeatedFinished.getUpdatedAt());
		assertFalse(
			containsId(getData(newClientSession().client(), "/api/rooms").path("content"), startedRoom.getId()));
		assertEquals("HOST",
			getData(hostSession.client(), "/api/rooms/" + startedRoom.getId()).path("myRole").asText());
		assertEquals("FINISHED",
			getData(participantSession.client(), "/api/rooms/" + startedRoom.getId()).path("status").asText());
		assertEquals("JOINED",
			getData(participantSession.client(), "/api/rooms/" + startedRoom.getId()).path("myRole").asText());
		assertFailure(get(otherSession.client(), "/api/rooms/" + startedRoom.getId()), 404, "ROOM_NOT_FOUND");
		assertEquals("FINISHED",
			findById(getData(participantSession.client(), "/api/users/me/rooms?role=joined").path("content"),
				startedRoom.getId())
				.path("status").asText());
		assertFailure(post(newcomerSession, "/api/rooms/" + startedRoom.getId() + "/participants", ""), 409,
			"ROOM_NOT_RECRUITING");
	}

	private ClientSession loggedInSession(FlowUser user) throws Exception {
		ClientSession session = newClientSession();
		loginAndRefreshCsrf(session, user);
		return session;
	}

	private ClientSession newClientSession() {
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		return new ClientSession(HttpClient.newBuilder().cookieHandler(cookieManager).build(), cookieManager);
	}

	private FlowUser saveFlowUser(String emailLocalPart) {
		String email = emailLocalPart + "@example.com";
		UserAccount account = userAccountService.createAccount(new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from("user-" + UUID.randomUUID()).orElseThrow()));
		userIds.add(account.id());
		return new FlowUser(account.id(), email);
	}

	private Room saveRoom(Long hostUserId, String title, int capacity, Instant startsAt) {
		Room room = roomRepository.saveAndFlush(Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			title,
			"ROOM lifecycle HTTP integration fixture",
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startsAt,
			"홍대 테스트 보드게임 카페",
			capacity));
		roomIds.add(room.getId());
		return room;
	}

	private Room saveClosedRoom(Long hostUserId, String title) {
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			title,
			"ROOM lifecycle HTTP integration fixture",
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			STARTED_AT,
			"홍대 테스트 보드게임 카페",
			3);
		room.reconcileStateAt(NOW);
		Room stored = roomRepository.saveAndFlush(room);
		roomIds.add(stored.getId());
		return stored;
	}

	private void saveActiveParticipation(Room room, Long userId) {
		registerParticipationCleanup(room.getId(), userId);
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		participationRepository.saveAndFlush(Participation.createActive(room, userId, FIXTURE_JOINED_AT));
	}

	private void loginAndRefreshCsrf(ClientSession session, FlowUser user) throws Exception {
		CsrfToken initialCsrf = getCsrf(session);
		JsonNode login = assertSuccess(post(
			session.client(),
			"/api/auth/login",
			objectMapper.writeValueAsString(Map.of("email", user.email(), "password", PASSWORD)),
			initialCsrf), 200);
		assertEquals(user.id().longValue(), login.path("id").asLong());
		CsrfToken refreshedCsrf = getCsrf(session);
		assertFalse(initialCsrf.token().equals(refreshedCsrf.token()));
		session.setCsrfToken(refreshedCsrf);
	}

	private CsrfToken getCsrf(ClientSession session) throws Exception {
		JsonNode csrf = assertSuccess(get(session.client(), "/api/auth/csrf"), 200);
		String headerName = csrf.path("headerName").asText();
		String token = csrf.path("token").asText();
		assertFalse(headerName.isBlank());
		assertFalse(token.isBlank());
		return new CsrfToken(headerName, token);
	}

	private String roomCreateBody(String title) throws Exception {
		return objectMapper.writeValueAsString(Map.of(
			"roomType", "PERSON_FOCUSED",
			"title", title,
			"description", "ROOM lifecycle HTTP integration fixture",
			"experienceLevel", "ALL_LEVELS",
			"isRulemasterLed", false,
			"startsAt", "2099-01-01T19:00:00+09:00",
			"place", "홍대 테스트 보드게임 카페",
			"recruitmentCapacity", 3));
	}

	private HttpResponse<String> get(HttpClient client, String path) throws Exception {
		return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(ClientSession session, String path, String body) throws Exception {
		return post(session.client(), path, body, session.csrfToken());
	}

	private HttpResponse<String> post(HttpClient client, String path, String body, CsrfToken csrfToken)
		throws Exception {
		return send(client, "POST", path, body, csrfToken);
	}

	private HttpResponse<String> patch(ClientSession session, String path, String body) throws Exception {
		return patch(session.client(), path, body, session.csrfToken());
	}

	private HttpResponse<String> patch(HttpClient client, String path, String body, CsrfToken csrfToken)
		throws Exception {
		return send(client, "PATCH", path, body, csrfToken);
	}

	private HttpResponse<String> delete(ClientSession session, String path) throws Exception {
		return send(session.client(), "DELETE", path, "", session.csrfToken());
	}

	private HttpResponse<String> send(HttpClient client, String method, String path, String body, CsrfToken csrfToken)
		throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
		if (csrfToken != null) {
			request.header(csrfToken.headerName(), csrfToken.token());
		}
		return client.send(
			request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private JsonNode getData(HttpClient client, String path) throws Exception {
		return assertSuccess(get(client, path), 200);
	}

	private JsonNode assertSuccess(HttpResponse<String> response, int expectedStatus) throws Exception {
		assertEquals(expectedStatus, response.statusCode(), response.body());
		JsonNode body = objectMapper.readTree(response.body());
		assertEquals(expectedStatus, body.path("status").asInt());
		return body.path("data");
	}

	private void assertFailure(HttpResponse<String> response, int expectedStatus, String expectedCode)
		throws Exception {
		assertEquals(expectedStatus, response.statusCode(), response.body());
		JsonNode body = objectMapper.readTree(response.body());
		assertEquals(expectedStatus, body.path("status").asInt());
		assertEquals(expectedCode, body.path("code").asText());
		assertFalse(body.path("message").asText().isBlank());
		assertTrue(body.path("data").isNull());
	}

	private JsonNode findById(JsonNode content, long expectedId) {
		assertTrue(content.isArray());
		for (JsonNode item : content) {
			if (item.path("id").asLong() == expectedId) {
				return item;
			}
		}
		throw new AssertionError("ID가 목록에 없습니다: " + expectedId);
	}

	private boolean containsId(JsonNode content, long expectedId) {
		assertTrue(content.isArray());
		for (JsonNode item : content) {
			if (item.path("id").asLong() == expectedId) {
				return true;
			}
		}
		return false;
	}

	private int countId(JsonNode content, long expectedId) {
		assertTrue(content.isArray());
		int count = 0;
		for (JsonNode item : content) {
			if (item.path("id").asLong() == expectedId) {
				count++;
			}
		}
		return count;
	}

	private Room findRoom(long roomId) {
		return roomRepository.findById(roomId).orElseThrow();
	}

	private Participation findParticipation(Long roomId, Long userId) {
		return participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow();
	}

	private long participationCount(Long roomId, Long userId) {
		return participationRepository.findAll().stream()
			.filter(participation -> roomId.equals(participation.getRoom().getId()))
			.filter(participation -> userId.equals(participation.getUserId()))
			.count();
	}

	private boolean roomExistsWithTitle(String title) {
		return roomRepository.findAll().stream().anyMatch(room -> title.equals(room.getTitle()));
	}

	private void assertActiveParticipation(Long roomId, Long userId) {
		assertEquals(ParticipationStatus.ACTIVE,
			participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow().getStatus());
	}

	private void assertCanceledParticipation(Long roomId, Long userId) {
		assertEquals(ParticipationStatus.CANCELED,
			participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow().getStatus());
	}

	private void registerParticipationCleanup(Long roomId, Long userId) {
		participationCleanupKeys.add(new ParticipationCleanupKey(roomId, userId));
	}

	private void registerRoomCleanup(Long hostUserId, String title) {
		roomCleanupKeys.add(new RoomCleanupKey(hostUserId, title));
	}

	private void deleteParticipationIfPresent(ParticipationCleanupKey cleanupKey) {
		participationRepository.findByRoomIdAndUserId(cleanupKey.roomId(), cleanupKey.userId())
			.ifPresent(participationRepository::delete);
	}

	private void deleteRoomIfPresent(RoomCleanupKey cleanupKey) {
		roomRepository.findAll().stream()
			.filter(room -> cleanupKey.hostUserId().equals(room.getHostUserId())
				&& cleanupKey.title().equals(room.getTitle()))
			.forEach(roomRepository::delete);
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private record FlowUser(Long id, String email) {
	}

	private record CsrfToken(String headerName, String token) {
	}

	private record ParticipationCleanupKey(Long roomId, Long userId) {
	}

	private record RoomCleanupKey(Long hostUserId, String title) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	private static final class ClientSession {

		private final HttpClient client;
		private final CookieManager cookieManager;
		private CsrfToken csrfToken;

		private ClientSession(HttpClient client, CookieManager cookieManager) {
			this.client = client;
			this.cookieManager = cookieManager;
		}

		private HttpClient client() {
			return client;
		}

		private CsrfToken csrfToken() {
			return csrfToken;
		}

		private void setCsrfToken(CsrfToken csrfToken) {
			this.csrfToken = csrfToken;
		}
	}
}
