package cloud.bamsongi.albammate.infra.ai;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.sun.net.httpserver.HttpServer;

/**
 * 운영에서만 재현되는 SERVICE_UNAVAILABLE 원인을 좁히기 위한 임시 재현 테스트다. 기존 테스트가 ChatModel을 mock으로
 * 대체해 실제 Spring AI + OpenAI SDK 경로를 한 번도 태우지 않는다는 점을 보완한다.
 */
class OpenAiAssistantProviderStubServerReproTest {

	private static final String CAPTURED_RESPONSE = """
		{"id":"chatcmpl-repro","object":"chat.completion","created":1787513038,"model":"gpt-5.6-luna",
		"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_repro",
		"type":"function","function":{"name":"propose_game_room_intent","arguments":
		"{\\"action\\":\\"NEEDS_INPUT\\",\\"categories\\":[],\\"mechanisms\\":[],\\"themes\\":[],
		\\"complexityMax\\":null,\\"playTimeMax\\":null,\\"playerCount\\":null}"}}],"refusal":null,
		"annotations":[]},"finish_reason":"tool_calls"}],
		"usage":{"prompt_tokens":513,"completion_tokens":50,"total_tokens":563},
		"service_tier":"default","system_fingerprint":null}
		""".replace("\n", "");

	@Test
	void 실제_SDK_경로로_propose를_호출한다() throws Exception {
		AtomicReference<String> capturedRequest = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = CAPTURED_RESPONSE.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		try {
			OpenAiChatModel chatModel = OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder()
					.baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
					.apiKey("test-key")
					.model("gpt-5.6-luna")
					.timeout(Duration.ofSeconds(10))
					.maxRetries(0)
					.maxCompletionTokens(256)
					.store(false)
					.build())
				.build();
			OpenAiAssistantProvider provider = new OpenAiAssistantProvider(
				chatModel, AiProviderSettings.fakeDefaults());

			AiProviderResponse response = provider.propose(new AiProviderPayload(
				"AI-02-INSTRUCTION-V1", "propose_game_room_intent", "AI-02-SCHEMA-V1", "Asia/Seoul",
				"보드게임 추천해줘", List.of()));

			System.out.println("=== REQUEST SENT TO OPENAI ===");
			System.out.println(capturedRequest.get());
			System.out.println("=== PROVIDER RESULT ===");
			System.out.println("succeeded=" + response.succeeded() + " failure=" + response.failure()
				+ " action=" + response.action());
		} finally {
			server.stop(0);
		}
	}
}
