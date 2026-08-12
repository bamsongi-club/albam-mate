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
		String library = file("load-tests/k6/chat/lib/chat.js");
		String throughput = file("load-tests/k6/chat/load-throughput.js");
		String sendContract = file("load-tests/k6/chat/send-contract.js");
		String websocketContract = file("load-tests/k6/chat/websocket-contract.js");
		String rateLimitContract = file("load-tests/k6/chat/rate-limit-contract.js");

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
	void 장기_구독자의_전달_지연은_수신_시점의_단계로_기록한다() throws IOException {
		String library = file("load-tests/k6/chat/lib/chat.js");
		String fanout = file("load-tests/k6/chat/load-fanout.js");
		String rooms = file("load-tests/k6/chat/load-rooms.js");
		String mixed = file("load-tests/k6/chat/load-mixed.js");

		assertThat(library).contains("function holdLoadSubscriber(user, roomId, connectionStage, deliveryStage, mode)");
		assertThat(library).contains("loadStageConnectMs.add(Date.now() - startedAt, { stage: connectionStage });");
		assertThat(library).contains("loadStageDeliveryMs.add(");
		assertThat(library).contains("stage: deliveryStage(),");
		assertSubscriberUsesDynamicDeliveryStage(fanout, "LOAD_FANOUT_SUBSCRIBER_STEPS");
		assertSubscriberUsesDynamicDeliveryStage(rooms, "LOAD_ROOM_STEPS");
		assertSubscriberUsesDynamicDeliveryStage(mixed, "LOAD_MIXED_SCALES");
	}

	@Test
	void 워밍업과_팬아웃_설정은_정식_측정_단계와_연결_수에_반영한다() throws IOException {
		String library = file("load-tests/k6/chat/lib/chat.js");
		String rooms = file("load-tests/k6/chat/load-rooms.js");
		String websocketContract = file("load-tests/k6/chat/websocket-contract.js");
		String crossInstanceContract = file("load-tests/k6/chat/cross-instance-contract.js");

		assertThat(library).contains("export const LOAD_WARMUP_STAGE = 'warmup';");
		assertThat(library).contains("if (elapsed < 0) {");
		assertThat(library).contains("return LOAD_WARMUP_STAGE;");
		assertThat(rooms).contains("stage === LOAD_WARMUP_STAGE ? 0 : Number(stage) - 1");
		assertThat(websocketContract).contains("fanoutParticipants(data.users, PRIMARY_ROOM_ID)");
		assertThat(crossInstanceContract).contains("fanoutParticipants(data.users, PRIMARY_ROOM_ID)");
	}

	private void assertSubscriberUsesDynamicDeliveryStage(String scenario, String stepVariable) {
		assertThat(scenario).containsPattern(
			"(?s)holdLoadSubscriber\\(\\s*user,\\s*roomId,\\s*currentStage\\(data, " + stepVariable
				+ "\\.length, 0\\),\\s*\\(\\) => currentStage\\(data, " + stepVariable + "\\.length, 0\\),");
	}

	private String file(String path) throws IOException {
		return Files.readString(REPOSITORY_ROOT.resolve(path));
	}
}
