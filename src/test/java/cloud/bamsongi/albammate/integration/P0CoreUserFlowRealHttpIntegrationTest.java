package cloud.bamsongi.albammate.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
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
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.security.cookie.secure=false")
class P0CoreUserFlowRealHttpIntegrationTest {

    private static final Instant FUTURE_STARTS_AT = Instant.parse("2099-01-01T10:00:00Z");
    private static final Instant FIXTURE_JOINED_AT = Instant.parse("2026-07-28T00:00:00Z");
    private static final String PASSWORD = "123456789012345";
    private static final AtomicLong BGG_ID_SEQUENCE = new AtomicLong(9_000_000_000L);

    @LocalServerPort private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserAccountService userAccountService;
    @Autowired private GameRepository gameRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private ParticipationRepository participationRepository;
    @Autowired private UserRepository userRepository;

    private final List<ParticipationCleanupKey> participationCleanupKeys = new ArrayList<>();
    private final List<Long> roomIds = new ArrayList<>();
    private final List<RoomCleanupKey> roomCleanupKeys = new ArrayList<>();
    private final List<Long> gameIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        participationCleanupKeys.forEach(this::deleteParticipationIfPresent);
        roomIds.forEach(roomRepository::deleteById);
        roomCleanupKeys.forEach(this::deleteRoomIfPresent);
        gameIds.forEach(gameRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
    }

    @Test
    void 게임부터_찾기는_실제_HTTP_세션과_CSRF로_참가한_방을_내_모임에서_확인한다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        Game game = saveGame("game-flow-" + suffix);
        Game otherGame = saveGame("other-game-" + suffix);
        UserAccount host = saveUser("game-host-" + suffix);
        Room room = saveRoom(host.id(), RoomType.GAME_FOCUSED, "game-room-" + suffix, game.getId());
        Room otherGameRoom =
                saveRoom(
                        host.id(),
                        RoomType.GAME_FOCUSED,
                        "other-game-room-" + suffix,
                        otherGame.getId());
        UserAccount otherParticipant = saveUser("other-game-participant-" + suffix);
        saveActiveParticipation(otherGameRoom, otherParticipant.id());
        FlowUser participant = saveFlowUser("game-participant-" + suffix);
        ClientSession session = newClientSession();

        JsonNode gameList = getData(session.client(), "/api/games?keyword=" + game.getName());
        JsonNode listedGame = findById(gameList.path("content"), game.getId());
        assertEquals(game.getId().longValue(), listedGame.path("id").asLong());
        assertEquals(game.getName(), listedGame.path("name").asText());
        assertFalse(containsId(gameList.path("content"), otherGame.getId()));

        JsonNode gameDetail = getData(session.client(), "/api/games/" + game.getId());
        assertEquals(game.getId().longValue(), gameDetail.path("id").asLong());
        assertEquals(game.getName(), gameDetail.path("name").asText());

        JsonNode roomList =
                getData(session.client(), "/api/rooms?type=GAME_FOCUSED&gameId=" + game.getId());
        JsonNode listedRoom = findById(roomList.path("content"), room.getId());
        assertEquals("GAME_FOCUSED", listedRoom.path("roomType").asText());
        assertEquals("RECRUITING", listedRoom.path("status").asText());
        assertFalse(containsId(roomList.path("content"), otherGameRoom.getId()));

        JsonNode publicRoom = getData(session.client(), "/api/rooms/" + room.getId());
        assertEquals(room.getId().longValue(), publicRoom.path("id").asLong());
        assertEquals("GAME_FOCUSED", publicRoom.path("roomType").asText());
        assertFalse(publicRoom.has("myRole"));

        loginAndRefreshCsrf(session, participant);
        registerParticipationCleanup(room.getId(), participant.id());
        HttpResponse<String> participationResponse =
                postCreated(session, "/api/rooms/" + room.getId() + "/participants", "");
        assertActiveParticipation(room.getId(), participant.id());
        assertEquals(1, findRoom(room.getId()).getActiveParticipantCount());
        JsonNode participation = assertSuccess(participationResponse, 201);
        assertEquals(room.getId().longValue(), participation.path("roomId").asLong());
        assertEquals("ACTIVE", participation.path("participationStatus").asText());
        assertEquals("RECRUITING", participation.path("roomStatus").asText());
        assertEquals(2, participation.path("participantCount").asInt());
        assertEquals(2, participation.path("remainingRecruitmentSeats").asInt());

        JsonNode joinedRooms = getData(session.client(), "/api/users/me/rooms?role=joined");
        JsonNode joinedRoom = findById(joinedRooms.path("content"), room.getId());
        assertEquals("JOINED", joinedRoom.path("myRole").asText());
        assertEquals("ACTIVE", joinedRoom.path("participationStatus").asText());
        assertEquals("RECRUITING", joinedRoom.path("status").asText());
        assertFalse(containsId(joinedRooms.path("content"), otherGameRoom.getId()));
    }

    @Test
    void 사람부터_만나기는_키워드_검색한_방에_실제_HTTP로_참가한다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        UserAccount host = saveUser("person-host-" + suffix);
        Room room = saveRoom(host.id(), RoomType.PERSON_FOCUSED, "person-room-" + suffix, null);
        Room unmatchedRoom =
                saveRoom(host.id(), RoomType.PERSON_FOCUSED, "unmatched-room-" + suffix, null);
        UserAccount otherParticipant = saveUser("other-person-participant-" + suffix);
        saveActiveParticipation(unmatchedRoom, otherParticipant.id());
        FlowUser participant = saveFlowUser("person-participant-" + suffix);
        ClientSession session = newClientSession();

        JsonNode roomList =
                getData(
                        session.client(),
                        "/api/rooms?type=PERSON_FOCUSED&keyword=" + room.getTitle());
        JsonNode listedRoom = findById(roomList.path("content"), room.getId());
        assertEquals("PERSON_FOCUSED", listedRoom.path("roomType").asText());
        assertEquals(room.getTitle(), listedRoom.path("title").asText());
        assertFalse(containsId(roomList.path("content"), unmatchedRoom.getId()));

        JsonNode publicRoom = getData(session.client(), "/api/rooms/" + room.getId());
        assertEquals(room.getId().longValue(), publicRoom.path("id").asLong());
        assertEquals("PERSON_FOCUSED", publicRoom.path("roomType").asText());
        assertEquals("RECRUITING", publicRoom.path("status").asText());

        loginAndRefreshCsrf(session, participant);
        registerParticipationCleanup(room.getId(), participant.id());
        HttpResponse<String> participationResponse =
                postCreated(session, "/api/rooms/" + room.getId() + "/participants", "");
        assertActiveParticipation(room.getId(), participant.id());
        assertEquals(1, findRoom(room.getId()).getActiveParticipantCount());
        JsonNode participation = assertSuccess(participationResponse, 201);
        assertEquals(room.getId().longValue(), participation.path("roomId").asLong());
        assertEquals("ACTIVE", participation.path("participationStatus").asText());
        assertEquals(2, participation.path("participantCount").asInt());
        assertEquals(2, participation.path("remainingRecruitmentSeats").asInt());

        JsonNode joinedRooms = getData(session.client(), "/api/users/me/rooms?role=joined");
        JsonNode joinedRoom = findById(joinedRooms.path("content"), room.getId());
        assertEquals("JOINED", joinedRoom.path("myRole").asText());
        assertEquals("ACTIVE", joinedRoom.path("participationStatus").asText());
        assertEquals("RECRUITING", joinedRoom.path("status").asText());
        assertFalse(containsId(joinedRooms.path("content"), unmatchedRoom.getId()));
    }

    @Test
    void 방_만들기는_실제_HTTP_세션과_CSRF로_생성한_방을_개설_목록에서_확인한다() throws Exception {
        String suffix = UUID.randomUUID().toString();
        FlowUser host = saveFlowUser("create-host-" + suffix);
        UserAccount otherHost = saveUser("other-create-host-" + suffix);
        Room otherHostedRoom =
                saveRoom(
                        otherHost.id(),
                        RoomType.PERSON_FOCUSED,
                        "other-hosted-room-" + suffix,
                        null);
        ClientSession session = newClientSession();
        String createdTitle = "created-room-" + suffix;

        loginAndRefreshCsrf(session, host);
        registerRoomCleanup(host.id(), createdTitle);
        HttpResponse<String> createdResponse =
                postCreated(
                        session,
                        "/api/rooms",
                        objectMapper.writeValueAsString(
                                Map.of(
                                        "roomType", "PERSON_FOCUSED",
                                        "title", createdTitle,
                                        "description", "P0 HTTP integration fixture",
                                        "experienceLevel", "ALL_LEVELS",
                                        "isRulemasterLed", true,
                                        "startsAt", "2099-01-01T19:00:00+09:00",
                                        "place", "홍대 테스트 보드게임 카페",
                                        "recruitmentCapacity", 3)));
        JsonNode createdRoom = assertSuccess(createdResponse, 201);
        long roomId = createdRoom.path("id").asLong();
        assertTrue(roomId > 0);
        assertEquals("PERSON_FOCUSED", createdRoom.path("roomType").asText());
        assertEquals("HOST", createdRoom.path("myRole").asText());
        assertEquals("홍대", createdRoom.path("region").asText());
        assertEquals("RECRUITING", createdRoom.path("status").asText());

        Room storedRoom = roomRepository.findById(roomId).orElseThrow();
        assertEquals(host.id(), storedRoom.getHostUserId());
        assertEquals(RoomType.PERSON_FOCUSED, storedRoom.getRoomType());
        assertEquals("홍대", storedRoom.getRegion());
        assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());

        JsonNode hostedRooms = getData(session.client(), "/api/users/me/rooms?role=hosted");
        JsonNode hostedRoom = findById(hostedRooms.path("content"), roomId);
        assertEquals("HOST", hostedRoom.path("myRole").asText());
        assertEquals("RECRUITING", hostedRoom.path("status").asText());
        assertFalse(containsId(hostedRooms.path("content"), otherHostedRoom.getId()));
    }

    private ClientSession newClientSession() {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
        return new ClientSession(client, cookieManager);
    }

    private UserAccount saveUser(String emailLocalPart) {
        String email = emailLocalPart + "@example.com";
        String nickname = "user-" + UUID.randomUUID();
        UserAccount account =
                userAccountService.createAccount(
                        new CreateUserAccountCommand(
                                UserEmail.from(email).orElseThrow(),
                                RawPassword.from(PASSWORD).orElseThrow(),
                                UserNickname.from(nickname).orElseThrow()));
        userIds.add(account.id());
        return account;
    }

    private FlowUser saveFlowUser(String emailLocalPart) {
        UserAccount account = saveUser(emailLocalPart);
        return new FlowUser(account.id(), emailLocalPart + "@example.com");
    }

    private Game saveGame(String name) {
        Game game =
                gameRepository.saveAndFlush(
                        new Game(
                                BGG_ID_SEQUENCE.getAndIncrement(),
                                name,
                                name + " English",
                                "2-4",
                                "전략",
                                "30-45분",
                                "P0 HTTP integration fixture",
                                "P0 HTTP integration fixture detail"));
        gameIds.add(game.getId());
        return game;
    }

    private Room saveRoom(Long hostUserId, RoomType roomType, String title, Long gameId) {
        Room room =
                roomRepository.saveAndFlush(
                        Room.create(
                                hostUserId,
                                roomType,
                                title,
                                "P0 HTTP integration fixture",
                                gameId,
                                ExperienceLevel.ALL_LEVELS,
                                false,
                                FUTURE_STARTS_AT,
                                "홍대 테스트 보드게임 카페",
                                3));
        roomIds.add(room.getId());
        return room;
    }

    private void loginAndRefreshCsrf(ClientSession session, FlowUser user) throws Exception {
        CsrfToken initialCsrf = getCsrf(session);
        assertTrue(cookieNamed(session.cookieManager(), "XSRF-TOKEN").isPresent());
        assertTrue(cookieNamed(session.cookieManager(), "JSESSIONID").isEmpty());

        HttpResponse<String> login =
                post(
                        session.client(),
                        "/api/auth/login",
                        objectMapper.writeValueAsString(
                                Map.of("email", user.email(), "password", PASSWORD)),
                        initialCsrf);
        JsonNode loginData = assertSuccess(login, 200);
        assertEquals(user.id().longValue(), loginData.path("id").asLong());
        assertTrue(cookieNamed(session.cookieManager(), "JSESSIONID").isPresent());

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

    private JsonNode getData(HttpClient client, String path) throws Exception {
        return assertSuccess(get(client, path), 200);
    }

    private HttpResponse<String> postCreated(ClientSession session, String path, String body)
            throws Exception {
        assertNotNull(session.csrfToken());
        HttpResponse<String> response = post(session.client(), path, body, session.csrfToken());
        assertEquals(201, response.statusCode(), response.body());
        return response;
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(
            HttpClient client, String path, String body, CsrfToken csrfToken) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .header("Content-Type", "application/json")
                        .header(csrfToken.headerName(), csrfToken.token())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode assertSuccess(HttpResponse<String> response, int expectedStatus)
            throws Exception {
        assertEquals(expectedStatus, response.statusCode(), response.body());
        JsonNode responseBody = objectMapper.readTree(response.body());
        assertEquals(expectedStatus, responseBody.path("status").asInt());
        return responseBody.path("data");
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

    private void registerParticipationCleanup(Long roomId, Long userId) {
        participationCleanupKeys.add(new ParticipationCleanupKey(roomId, userId));
    }

    private void assertActiveParticipation(Long roomId, Long userId) {
        Participation participation =
                participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow();
        assertEquals(ParticipationStatus.ACTIVE, participation.getStatus());
    }

    private void saveActiveParticipation(Room room, Long userId) {
        registerParticipationCleanup(room.getId(), userId);
        room.addActiveParticipant();
        roomRepository.saveAndFlush(room);
        Participation participation =
                participationRepository.saveAndFlush(
                        Participation.createActive(room, userId, FIXTURE_JOINED_AT));
        assertEquals(ParticipationStatus.ACTIVE, participation.getStatus());
    }

    private void deleteParticipationIfPresent(ParticipationCleanupKey cleanupKey) {
        participationRepository
                .findByRoomIdAndUserId(cleanupKey.roomId(), cleanupKey.userId())
                .ifPresent(participationRepository::delete);
    }

    private Room findRoom(Long roomId) {
        return roomRepository.findById(roomId).orElseThrow();
    }

    private void registerRoomCleanup(Long hostUserId, String title) {
        roomCleanupKeys.add(new RoomCleanupKey(hostUserId, title));
    }

    private void deleteRoomIfPresent(RoomCleanupKey cleanupKey) {
        roomRepository.findAll().stream()
                .filter(
                        room ->
                                cleanupKey.hostUserId().equals(room.getHostUserId())
                                        && cleanupKey.title().equals(room.getTitle()))
                .forEach(roomRepository::delete);
    }

    private java.util.Optional<HttpCookie> cookieNamed(CookieManager cookieManager, String name) {
        return cookieManager.getCookieStore().getCookies().stream()
                .filter(cookie -> name.equals(cookie.getName()))
                .findFirst();
    }

    private record FlowUser(Long id, String email) {}

    private record CsrfToken(String headerName, String token) {}

    private record ParticipationCleanupKey(Long roomId, Long userId) {}

    private record RoomCleanupKey(Long hostUserId, String title) {}

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

        private CookieManager cookieManager() {
            return cookieManager;
        }

        private CsrfToken csrfToken() {
            return csrfToken;
        }

        private void setCsrfToken(CsrfToken csrfToken) {
            this.csrfToken = csrfToken;
        }
    }
}
