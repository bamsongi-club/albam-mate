package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

			// 실제 SDK 경로에서 tool 호출이 강제되고 응답이 그대로 해석되는지 고정한다.
			String sentRequest = capturedRequest.get();
			assertNotNull(sentRequest);
			assertTrue(sentRequest.contains("propose_game_room_intent"));
			assertTrue(sentRequest.contains("\"tool_choice\""));

			// provider는 예외 없이 실패를 반환할 수 있으므로 실패 여부를 단언한다.
			assertNull(response.failure());
			assertTrue(response.succeeded());
			assertEquals("NEEDS_INPUT", response.action());
			assertEquals(List.of(), response.categories());
		} finally {
			server.stop(0);
		}
	}
}
