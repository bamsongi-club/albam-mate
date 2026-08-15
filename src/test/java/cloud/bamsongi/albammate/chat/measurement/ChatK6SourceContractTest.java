package cloud.bamsongi.albammate.chat.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ChatK6SourceContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();

	@Test
	void 필수_대상과_시나리오_입력을_요청_전에_검증한다() throws IOException {
		String library = file("load-tests/k6/eungi/lib/chat.js");
		String throughput = file("load-tests/k6/eungi/load-throughput.js");
		String sendContract = file("load-tests/k6/eungi/send-contract.js");
		String websocketContract = file("load-tests/k6/eungi/websocket-contract.js");
		String rateLimitContract = file("load-tests/k6/eungi/rate-limit-contract.js");

		assertThat(library).contains("K6_BASE_URL or ALBAM_MATE_TARGET_URL is required");
		assertThat(library).doesNotContain("http://localhost:5173");
		assertThat(throughput).contains("validateThroughputFixture");
		assertThat(throughput).contains("throughputSenderForSequence");
		assertThat(throughput).contains("Math.floor(sequence / roomCount) + 1");
		assertThat(sendContract).contains("readEnum('K6_CHAT_CASE'");
		assertThat(websocketContract).contains("readEnum('K6_CHAT_CASE'");
		assertThat(rateLimitContract).contains("readEnum('K6_CHAT_CASE'");
	}

	@Test
	void T5_k6_전송_제한_계약은_사용자_오십오십일과_방_백백일_경계를_검증한다() throws IOException {
		String rateLimitContract = file("load-tests/k6/eungi/rate-limit-contract.js");

		assertThat(rateLimitContract).contains("const ROOM_RATE_LIMIT_PARTICIPANT_COUNT = 3;");
		assertThat(rateLimitContract).contains(
			"const RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(\n\t'K6_RATE_LIMIT_ATTEMPTS',\n\t51,\n\t'the exact user limiter proof',");
		assertThat(rateLimitContract).contains(
			"const ROOM_RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(\n\t'K6_ROOM_RATE_LIMIT_ATTEMPTS',\n\t34,\n\t'the exact room limiter proof',");
		assertThat(rateLimitContract).contains(
			"execution.vu.idInTest === ROOM_RATE_LIMIT_PARTICIPANT_COUNT\n\t\t&& execution.vu.iterationInScenario === ROOM_RATE_LIMIT_ATTEMPTS - 1");
		assertThat(rateLimitContract).contains("const expectedCreated = Math.min(RATE_LIMIT_ATTEMPTS, 50);");
		assertThat(rateLimitContract).contains("const expectedThrottled = Math.max(0, RATE_LIMIT_ATTEMPTS - 50);");
		assertThat(rateLimitContract)
			.contains("rateLimitThresholds(Math.min(RATE_LIMIT_ATTEMPTS, 50), Math.max(0, RATE_LIMIT_ATTEMPTS - 50))");
		assertThat(rateLimitContract).contains("rateLimitThresholds(100, 1)");
	}

	@Test
	void T7_API와_CHAT_문서와_테스트_k6_링크가_오십백_계약으로_일치한다() throws IOException {
		String properties = file(
			"src/main/java/cloud/bamsongi/albammate/infra/redis/ChatMessageRateLimitProperties.java");
		String library = file("load-tests/k6/eungi/lib/chat.js");
		String rateLimitContract = file("load-tests/k6/eungi/rate-limit-contract.js");
		String api = file("docs/API.md");
		String chatting = file("docs/archive/p1/chatting.md");
		String p1Readme = file("docs/archive/p1/README.md");

		assertThat(properties).contains("@DefaultValue(\"50\")");
		assertThat(properties).contains("@DefaultValue(\"100\")");
		assertThat(library).contains("export const USER_RATE_LIMIT_PER_SECOND = 5;");
		assertThat(library).contains("export const ROOM_RATE_LIMIT_PER_SECOND = 10;");
		assertThat(rateLimitContract).contains("rateLimitThresholds(100, 1)");
		assertThat(api).contains("50건/10초");
		assertThat(api).contains("100건/10초");
		assertThat(api).contains("issues/760#issuecomment-5300372595");
		assertThat(api).contains("issues/761#issuecomment-5300395172");
		assertThat(chatting).contains("50건/10초");
		assertThat(chatting).contains("100건/10초");
		assertThat(chatting).contains("issues/761#issuecomment-5300395172");
		assertThat(p1Readme).contains("issues/760#issuecomment-5300372595");
		assertThat(p1Readme).contains("issues/761#issuecomment-5300395172");
	}

	@Test
	void 장기_구독자의_전달_지연은_수신_시점의_단계로_기록한다() throws IOException {
		String library = file("load-tests/k6/eungi/lib/chat.js");
		String fanout = file("load-tests/k6/eungi/load-fanout.js");
		String rooms = file("load-tests/k6/eungi/load-rooms.js");
		String mixed = file("load-tests/k6/eungi/load-mixed.js");

		assertThat(library).contains("function holdLoadSubscriber(user, roomId, connectionStage, deliveryStage, mode)");
		assertThat(library).contains("loadStageConnectMs.add(Date.now() - startedAt, { stage: connectionStage });");
		assertThat(library).contains("loadStageDeliveryMs.add(");
		assertThat(library).contains("stage: deliveryStage(),");
		assertThat(library).contains("loadSubscriberReceivedMessages");
		assertThat(library).contains("loadSubscriberDeliveryComplete.add(receivedMessageCount > 0");
		assertThat(library).contains("const warmupDuration = `${warmupMilliseconds}ms`;");
		assertThat(fanout).contains("thresholds: loadThresholds(stepCount, true)");
		assertThat(rooms).contains("thresholds: loadThresholds(stepCount, true)");
		assertThat(mixed).contains("thresholds: loadThresholds(stepCount, true)");
		assertThat(fanout).contains("stages: loadSubscriberStages(LOAD_FANOUT_SUBSCRIBER_STEPS)");
		assertThat(rooms)
			.contains("stages: loadSubscriberStages(LOAD_ROOM_STEPS.map((rooms) => rooms * LOAD_ROOM_SUBSCRIBERS))");
		assertThat(mixed)
			.contains("stages: loadSubscriberStages(LOAD_MIXED_SCALES.map((scale) => scale * LOAD_MIXED_CONNECTIONS))");
		assertSubscriberUsesAlignedDeliveryStage(fanout, "LOAD_FANOUT_SUBSCRIBER_STEPS");
		assertSubscriberUsesAlignedDeliveryStage(rooms, "LOAD_ROOM_STEPS");
		assertSubscriberUsesAlignedDeliveryStage(mixed, "LOAD_MIXED_SCALES");
	}

	@Test
	void 준비된_세션_fixture는_로그인하지_않고_사용한다() throws IOException {
		String library = file("load-tests/k6/eungi/lib/chat.js");

		assertThat(library).contains("const users = FIXTURE_USERS.map(prepareFixtureUser);");
		assertThat(library).contains("if (isPreparedSessionFixtureUser(user))");
		assertThat(library).contains("needs a prepared session or login credentials");
	}

	@Test
	void 워밍업과_팬아웃_설정은_정식_측정_단계와_연결_수에_반영한다() throws IOException {
		String library = file("load-tests/k6/eungi/lib/chat.js");
		String rooms = file("load-tests/k6/eungi/load-rooms.js");
		String websocketContract = file("load-tests/k6/eungi/websocket-contract.js");
		String crossInstanceContract = file("load-tests/k6/eungi/cross-instance-contract.js");

		assertThat(library).contains("export const LOAD_WARMUP_STAGE = 'warmup';");
		assertThat(library).contains("if (elapsed < 0) {");
		assertThat(library).contains("return LOAD_WARMUP_STAGE;");
		assertThat(rooms).contains("stage === LOAD_WARMUP_STAGE ? 0 : Number(stage) - 1");
		assertThat(websocketContract).contains("fanoutParticipants(data.users, PRIMARY_ROOM_ID)");
		assertThat(crossInstanceContract).contains("fanoutParticipants(data.users, PRIMARY_ROOM_ID)");
	}

	@Test
	void 접근_무효화_시나리오는_제어와_WebSocket_route를_분리하고_기존_시나리오와_분리한다() throws IOException {
		String accessInvalidation = file("load-tests/k6/eungi/room-access-invalidation-contract.js");
		String readme = file("load-tests/k6/eungi/README.md");

		assertThat(accessInvalidation).contains("const CONTROL_ROUTE = 'app-a'");
		assertThat(accessInvalidation).contains("const WEBSOCKET_ROUTE = 'app-b'");
		assertThat(accessInvalidation).contains("const CONTROL_BASE_URL = BASE_URL;");
		assertThat(accessInvalidation).contains("const WEBSOCKET_BASE_URL = BASE_URL;");
		assertThat(accessInvalidation)
			.contains("verifyCrossInstanceRoute(participant, CONTROL_BASE_URL, CONTROL_ROUTE)");
		assertThat(accessInvalidation)
			.contains("verifyCrossInstanceRoute(participant, WEBSOCKET_BASE_URL, WEBSOCKET_ROUTE)");
		assertThat(accessInvalidation).contains("headers: performanceRouteHeader(CONTROL_ROUTE)");
		assertThat(accessInvalidation)
			.contains("headers: { ...jsonHeaders(user), ...performanceRouteHeader(CONTROL_ROUTE) }");
		assertThat(accessInvalidation)
			.contains("WEBSOCKET_BASE_URL,\n\t\tperformanceRouteHeader(WEBSOCKET_ROUTE),");
		assertThat(accessInvalidation).contains("routePreflightExpected.add(routesExpected);");
		assertThat(accessInvalidation).contains("throw new Error('access invalidation route preflight failed');");
		assertThat(accessInvalidation).contains("/participants/me");
		assertThat(accessInvalidation).contains("POLICY_VIOLATION");
		assertThat(accessInvalidation).contains("closeCode === 1008");
		assertThat(accessInvalidation).contains("/api/rooms/${user.roomId}");
		assertThat(accessInvalidation).contains("chat_access_invalidation_participant_message_created");
		assertThat(accessInvalidation).contains("chat_access_invalidation_terminal_message_rejected");
		assertThat(readme).contains("room-access-invalidation-contract.js");
	}

	private void assertSubscriberUsesAlignedDeliveryStage(String scenario, String stepVariable) {
		assertThat(scenario).containsPattern(
			"(?s)holdLoadSubscriber\\(\\s*user,\\s*roomId,\\s*currentStage\\(data, " + stepVariable
				+ "\\.length, durationMilliseconds\\(WS_READY_DELAY\\)\\),\\s*\\(\\) => currentStage\\(data, "
				+ stepVariable + "\\.length, durationMilliseconds\\(WS_READY_DELAY\\)\\),");
	}

	private String file(String path) throws IOException {
		return Files.readString(REPOSITORY_ROOT.resolve(path));
	}
}
