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

	private String file(String path) throws IOException {
		return Files.readString(REPOSITORY_ROOT.resolve(path));
	}
}
