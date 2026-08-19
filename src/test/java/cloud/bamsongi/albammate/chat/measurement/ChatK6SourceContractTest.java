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
		String library = file("load-tests/k6/eungi/lib/chat.js");
		String rateLimitContract = file("load-tests/k6/eungi/rate-limit-contract.js");

		assertThat(rateLimitContract).contains("const ROOM_RATE_LIMIT_PARTICIPANT_COUNT = 3;");
		assertThat(rateLimitContract).contains(
			"const RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(\n\t'K6_RATE_LIMIT_ATTEMPTS',\n\t51,\n\t'the exact user limiter proof',");
		assertThat(rateLimitContract).contains(
			"const ROOM_RATE_LIMIT_ATTEMPTS = readFixedPositiveInteger(\n\t'K6_ROOM_RATE_LIMIT_ATTEMPTS',\n\t34,\n\t'the exact room limiter proof',");
		assertThat(rateLimitContract).containsPattern(
			"(?s)export\\s+function\\s+rateLimitRoom\\s*\\(\\s*data\\s*\\)\\s*\\{.*?"
				+ "Array\\.from\\(\\{\\s*length:\\s*ROOM_RATE_LIMIT_MESSAGES\\s*\\}.*?"
				+ "user:\\s*users\\[sequence % users\\.length\\].*?"
				+ "sendRateLimitBatch\\(messages,\\s*data\\.runId,\\s*'rate-limit-room'\\)");
		assertThat(rateLimitContract).containsPattern(
			"(?s)export\\s+const\\s+options\\s*=\\s*CASE\\s*===\\s*'user'\\s*\\?\\s*"
				+ "rateLimitOptions\\(\\s*'rate_limit_user',\\s*'rateLimitUser',\\s*"
				+ "RATE_LIMIT_ATTEMPTS,.*?\\)\\s*:\\s*"
				+ "rateLimitOptions\\(\\s*'rate_limit_room',\\s*'rateLimitRoom',\\s*"
				+ "ROOM_RATE_LIMIT_MESSAGES,.*?rateLimitThresholds\\(");
		assertThat(rateLimitContract).contains(
			"const ROOM_RATE_LIMIT_MESSAGES = ROOM_RATE_LIMIT_ATTEMPTS * ROOM_RATE_LIMIT_PARTICIPANT_COUNT - 1;");
		assertThat(rateLimitContract)
			.contains("const RATE_LIMIT_WINDOW_MILLISECONDS = RATE_LIMIT_WINDOW_SECONDS * 1_000;");
		assertThat(rateLimitContract)
			.contains("const RATE_LIMIT_BOUNDARY_TARGET_SECONDS = RATE_LIMIT_WINDOW_SECONDS - 1;");
		assertThat(rateLimitContract).contains(
			"const RATE_LIMIT_RECOVERY_WAIT_SECONDS = RATE_LIMIT_WINDOW_SECONDS - RATE_LIMIT_BOUNDARY_TARGET_SECONDS + 1;");
		assertThat(rateLimitContract).contains(
			"const RATE_LIMIT_RECOVERY_TARGET_MILLISECONDS = (\n\tRATE_LIMIT_BOUNDARY_TARGET_SECONDS + RATE_LIMIT_RECOVERY_WAIT_SECONDS\n) * 1_000;");
		assertThat(rateLimitContract)
			.contains("const RATE_LIMIT_RECOVERY_LATEST_MILLISECONDS = (RATE_LIMIT_WINDOW_SECONDS + 2) * 1_000;");
		assertThat(rateLimitContract).contains("const RATE_LIMIT_COOLDOWN_SECONDS = readPositiveNumber(");
		assertThat(rateLimitContract).contains("K6_RATE_LIMIT_COOLDOWN_SECONDS must be greater than");
		assertThat(rateLimitContract)
			.contains("const rateLimitWindowValid = new Rate('chat_rate_limit_window_valid');");
		assertThat(rateLimitContract)
			.contains("const rateLimitWindowElapsedMs = new Trend('chat_rate_limit_window_elapsed_ms', true);");
		assertThat(rateLimitContract)
			.contains("const rateLimitWindowStillLimited = new Rate('chat_rate_limit_window_still_limited');");
		assertThat(rateLimitContract)
			.contains("const rateLimitWindowRecovered = new Rate('chat_rate_limit_window_recovered');");
		assertThat(rateLimitContract)
			.contains("const rateLimitWindowRecoveryTiming = new Rate('chat_rate_limit_window_recovery_timing');");
		assertThat(rateLimitContract)
			.contains(
				"const rateLimitWindowRecoveryElapsedMs = new Trend('chat_rate_limit_window_recovery_elapsed_ms', true);");
		assertThat(rateLimitContract).contains("const withinWindow = elapsedMs < RATE_LIMIT_WINDOW_MILLISECONDS;");
		assertThat(rateLimitContract)
			.contains("const remainingSeconds = RATE_LIMIT_BOUNDARY_TARGET_SECONDS - elapsedMs / 1_000;");
		assertThat(rateLimitContract).contains("sleep(remainingSeconds);");
		assertThat(rateLimitContract)
			.contains(
				"const remainingSeconds = (RATE_LIMIT_RECOVERY_TARGET_MILLISECONDS - elapsedBeforeRecoveryMs) / 1_000;");
		assertThat(rateLimitContract).contains("const dispatchElapsedMs = Date.now() - startedAt;");
		assertThat(rateLimitContract).contains("const completionElapsedMs = Date.now() - startedAt;");
		assertThat(rateLimitContract)
			.contains("&& completionElapsedMs >= RATE_LIMIT_WINDOW_MILLISECONDS");
		assertThat(rateLimitContract).contains("rateLimitWindowRecoveryElapsedMs.add(completionElapsedMs);");
		assertThat(rateLimitContract).contains("postNewMessage(user, runId, purpose, sequence);");
		assertThat(rateLimitContract).contains("validateRateLimitFixtureIsolation();");
		assertThat(rateLimitContract)
			.contains("rate-limit-contract requires credential fixture emails generated by rooms.sql");
		assertThat(rateLimitContract).contains("batchPerHost: batchSize");
		assertThat(library).contains("export function postNewMessagesBatch(messages, runId, purpose");
		assertThat(library).contains("return http.batch(requests).map(recordMessageResponse);");
		assertThat(rateLimitContract)
			.contains("const expectedCreated = Math.min(RATE_LIMIT_ATTEMPTS, USER_RATE_LIMIT_PER_WINDOW);");
		assertThat(rateLimitContract)
			.contains("const expectedThrottled = Math.max(0, RATE_LIMIT_ATTEMPTS - USER_RATE_LIMIT_PER_WINDOW);");
		assertThat(rateLimitContract)
			.contains("Math.min(RATE_LIMIT_ATTEMPTS, USER_RATE_LIMIT_PER_WINDOW)");
		assertThat(rateLimitContract).contains("Math.min(ROOM_RATE_LIMIT_MESSAGES, ROOM_RATE_LIMIT_PER_WINDOW)");
		assertThat(rateLimitContract).contains("Math.max(0, ROOM_RATE_LIMIT_MESSAGES - ROOM_RATE_LIMIT_PER_WINDOW)");
		assertThat(rateLimitContract).contains("chat_send_created: [`count==${expectedCreated + 1}`]");
		assertThat(rateLimitContract).contains("chat_send_rate_limited: [`count==${expectedRateLimited + 1}`]");
	}

	@Test
	void T7_API와_CHAT_문서와_테스트_k6_링크가_오십백_계약으로_일치한다() throws IOException {
		String properties = file(
			"src/main/java/cloud/bamsongi/albammate/infra/redis/ChatMessageRateLimitProperties.java");
		String library = file("load-tests/k6/eungi/lib/chat.js");
		String rateLimitContract = file("load-tests/k6/eungi/rate-limit-contract.js");
		String api = file("docs/API.md");

		assertThat(properties).contains("@DefaultValue(\"50\")");
		assertThat(properties).contains("@DefaultValue(\"100\")");
		assertThat(library).contains("export const RATE_LIMIT_WINDOW_SECONDS = 10;");
		assertThat(library).contains("export const USER_RATE_LIMIT_PER_SECOND = 5;");
		assertThat(library).contains("export const ROOM_RATE_LIMIT_PER_SECOND = 10;");
		assertThat(library)
			.contains(
				"export const USER_RATE_LIMIT_PER_WINDOW = USER_RATE_LIMIT_PER_SECOND * RATE_LIMIT_WINDOW_SECONDS;");
		assertThat(library)
			.contains(
				"export const ROOM_RATE_LIMIT_PER_WINDOW = ROOM_RATE_LIMIT_PER_SECOND * RATE_LIMIT_WINDOW_SECONDS;");
		assertThat(library).contains("if (messagesPerUser > USER_RATE_LIMIT_PER_WINDOW) {");
		assertThat(library)
			.contains("if ((roomCounts[roomId] || 0) * messagesPerUser > ROOM_RATE_LIMIT_PER_WINDOW) {");
		assertThat(library).contains("if (FANOUT_MESSAGES > USER_RATE_LIMIT_PER_WINDOW) {");
		assertThat(api).containsPattern(
			"(?m)^\\|\\s*사용자\\s*\\|[^\\n]*\\|\\s*50건/10초\\s*\\|\\s*10초 고정 창\\s*\\|\\s*$");
		assertThat(api).containsPattern(
			"(?m)^\\|\\s*방\\s*\\|[^\\n]*\\|\\s*100건/10초\\s*\\|\\s*10초 고정 창\\s*\\|\\s*$");
		assertThat(api).contains("현재 제품·HTTP·WebSocket 계약은 이 문서가 정본");
		assertThat(api).contains("issues/760#issuecomment-5300372595");
		assertThat(api).doesNotContain("issues/761#issuecomment-5300395172");
		assertThat(api).contains(
			"[CHAT-03](#chat-03-실시간-메시지-구독) · [P1 종료 기록](archive/p1/chatting.md#chat-03-실시간-전달재연결-복구)");
		assertThat(rateLimitContract).contains("validateCommonPrerequisites();");
		assertThat(rateLimitContract).containsPattern(
			"(?s)if\\s*\\(CASE\\s*===\\s*'room'\\)\\s*\\{\\s*validateRateLimitRoomProfile\\(\\);\\s*\\}");
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
		return Files.readString(REPOSITORY_ROOT.resolve(path), java.nio.charset.StandardCharsets.UTF_8)
			.replace("\r\n", "\n")
			.replace('\r', '\n');
	}
}
