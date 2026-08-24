package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import com.openai.errors.OpenAIServiceException;

class OpenAiAssistantProviderTest {

	@Test
	void T2_local_openai_adapter는_버전_instruction과_단일_tool을_강제한다() {
		OpenAiAssistantProvider provider = new OpenAiAssistantProvider(
			mock(ChatModel.class),
			AiProviderSettings.fakeDefaults());
		AiProviderPayload payload = payload();

		OpenAiChatOptions options = provider.optionsFor(payload);
		Prompt prompt = provider.promptFor(payload);

		assertEquals("gpt-5.6-luna", options.getModel());
		assertEquals(Duration.ofSeconds(10), options.getTimeout());
		assertEquals(0, options.getMaxRetries());
		assertEquals(Boolean.FALSE, options.getStore());
		assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
		assertEquals(Boolean.FALSE, options.getParallelToolCalls());
		assertEquals(1, options.getToolCallbacks().size());
		assertEquals("propose_game_room_intent",
			options.getToolCallbacks().getFirst().getToolDefinition().name());
		String inputSchema = options.getToolCallbacks().getFirst().getToolDefinition().inputSchema();
		assertTrue(inputSchema.contains("categories"));
		assertTrue(inputSchema.contains("mechanisms"));
		assertTrue(inputSchema.contains("themes"));
		assertFalse(inputSchema.contains("uniqueItems"));
		assertThrows(UnsupportedOperationException.class,
			() -> options.getToolCallbacks().getFirst().call("{}"));
		assertTrue(String.valueOf(options.getToolChoice()).contains("propose_game_room_intent"));
		assertTrue(prompt.getInstructions().getFirst() instanceof SystemMessage);
		assertTrue(prompt.getSystemMessage().getText().contains("exactly once"));
		assertTrue(prompt.getSystemMessage().getText().contains("at least one of category, mechanism, or theme"));
		assertTrue(prompt.getUserMessage().getText().contains("전략 게임 추천"));
		assertFalse(prompt.getSystemMessage().getText().contains("전략 게임 추천"));
		assertEquals("openai", provider.providerName());
	}

	@Test
	void T3_openai_adapter는_강제_tool_arguments만_구조화_결과로_받는다() {
		ChatModel chatModel = mock(ChatModel.class);
		AssistantMessage output = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(
				"call-1", "function", "propose_game_room_intent",
				"{\"action\":\"RECOMMEND\",\"categories\":[\"STRATEGY\"],"
					+ "\"mechanisms\":[\"WORKER_PLACEMENT\"],\"themes\":[\"HORROR\"],"
					+ "\"complexityMax\":3.0,\"playTimeMax\":\"OVER_20_TO_30\",\"playerCount\":4}")))
			.build();
		when(chatModel.call(any(Prompt.class))).thenReturn(
			new ChatResponse(List.of(new Generation(output))));
		OpenAiAssistantProvider provider = new OpenAiAssistantProvider(chatModel, AiProviderSettings.fakeDefaults());

		AiProviderResponse response = provider.propose(payload());

		assertTrue(response.succeeded());
		assertEquals("RECOMMEND", response.action());
		assertEquals(List.of("STRATEGY"), response.categories());
		assertEquals(List.of("WORKER_PLACEMENT"), response.mechanisms());
		assertEquals(List.of("HORROR"), response.themes());
		assertEquals(4, response.playerCount());
	}

	@Test
	void T2_action별_구조화_조건의_형식과_범위를_검증한다() {
		String emptyConditions = "\"categories\":[],\"mechanisms\":[],\"themes\":[],"
			+ "\"complexityMax\":null,\"playTimeMax\":null,\"playerCount\":null";
		assertTrue(responseFor("{\"action\":\"NEEDS_INPUT\"," + emptyConditions + "}").succeeded());
		assertEquals("NEEDS_INPUT", responseFor("{\"action\":\"NEEDS_INPUT\"," + emptyConditions + "}").action());
		AiProviderResponse needsInputWithRefinement = responseFor(
			"{\"action\":\"NEEDS_INPUT\",\"categories\":[],\"mechanisms\":[],\"themes\":[],"
				+ "\"complexityMax\":2.0,\"playTimeMax\":\"OVER_10_TO_20\",\"playerCount\":4}");
		assertTrue(needsInputWithRefinement.succeeded());
		assertEquals("NEEDS_INPUT", needsInputWithRefinement.action());
		assertEquals(0, needsInputWithRefinement.complexityMax().compareTo(BigDecimal.valueOf(2.0)));
		assertEquals("OVER_10_TO_20", needsInputWithRefinement.playTimeMax());
		assertEquals(4, needsInputWithRefinement.playerCount());
		assertTrue(responseFor("{\"action\":\"UNSUPPORTED\"," + emptyConditions + "}").succeeded());
		assertEquals("UNSUPPORTED", responseFor("{\"action\":\"UNSUPPORTED\"," + emptyConditions + "}").action());
		assertEquals(AiProviderFailure.INVALID_SCHEMA,
			responseFor("{\"action\":\"NEEDS_INPUT\",\"categories\":[\"STRATEGY\"],\"mechanisms\":[],\"themes\":[],"
				+ "\"complexityMax\":null,\"playTimeMax\":null,\"playerCount\":null}").failure());
		assertEquals(AiProviderFailure.INVALID_SCHEMA,
			responseFor("{\"action\":\"RECOMMEND\",\"categories\":[\"invalid\"],\"mechanisms\":[],\"themes\":[],"
				+ "\"complexityMax\":null,\"playTimeMax\":null,\"playerCount\":null}").failure());
		AiProviderResponse recommendWithoutSearchCondition = responseFor(
			"{\"action\":\"RECOMMEND\"," + emptyConditions + "}");
		assertTrue(recommendWithoutSearchCondition.succeeded());
		assertEquals("NEEDS_INPUT", recommendWithoutSearchCondition.action());
		assertEquals(AiProviderFailure.INVALID_SCHEMA,
			responseFor("{\"action\":\"RECOMMEND\",\"categories\":[\"STRATEGY\"],\"mechanisms\":[],\"themes\":[],"
				+ "\"complexityMax\":5.1,\"playTimeMax\":null,\"playerCount\":null}").failure());
		assertEquals(AiProviderFailure.INVALID_SCHEMA,
			responseFor("{\"action\":\"RECOMMEND\",\"categories\":[\"STRATEGY\"],\"mechanisms\":[],\"themes\":[],"
				+ "\"complexityMax\":null,\"playTimeMax\":null,\"playerCount\":4.5}").failure());
		assertEquals(AiProviderFailure.INVALID_SCHEMA,
			responseFor(
				"{\"action\":\"RECOMMEND\",\"categories\":[\"STRATEGY\",\"STRATEGY\"],\"mechanisms\":[],\"themes\":[],"
					+ "\"complexityMax\":null,\"playTimeMax\":null,\"playerCount\":null}")
				.failure());
	}

	@Test
	void T3_openai_adapter의_깨진_tool_arguments는_INVALID_SCHEMA로_닫힌다() {
		ChatModel chatModel = mock(ChatModel.class);
		AssistantMessage output = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(
				"call-1", "function", "propose_game_room_intent", "{malformed")))
			.build();
		when(chatModel.call(any(Prompt.class))).thenReturn(
			new ChatResponse(List.of(new Generation(output))));
		OpenAiAssistantProvider provider = new OpenAiAssistantProvider(chatModel, AiProviderSettings.fakeDefaults());

		AiProviderResponse response = provider.propose(payload());

		assertEquals(AiProviderFailure.INVALID_SCHEMA, response.failure());
	}

	@Test
	void T3_429만_RATE_LIMITED이고_인증_model_5xx는_SERVICE_UNAVAILABLE이다() {
		assertEquals(AiProviderFailure.RATE_LIMITED, failureForStatus(429));
		assertEquals(AiProviderFailure.SERVICE_UNAVAILABLE, failureForStatus(401));
		assertEquals(AiProviderFailure.SERVICE_UNAVAILABLE, failureForStatus(403));
		assertEquals(AiProviderFailure.SERVICE_UNAVAILABLE, failureForStatus(404));
		assertEquals(AiProviderFailure.SERVICE_UNAVAILABLE, failureForStatus(500));
	}

	@Test
	void T3_미분류_runtime_예외는_TIMEOUT으로_오인하지_않는다() {
		ChatModel chatModel = mock(ChatModel.class);
		when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("adapter bug"));
		OpenAiAssistantProvider provider = new OpenAiAssistantProvider(chatModel, AiProviderSettings.fakeDefaults());

		assertEquals(AiProviderFailure.SERVICE_UNAVAILABLE, provider.propose(payload()).failure());
	}

	private AiProviderFailure failureForStatus(int status) {
		ChatModel chatModel = mock(ChatModel.class);
		OpenAIServiceException exception = mock(OpenAIServiceException.class);
		when(exception.statusCode()).thenReturn(status);
		when(chatModel.call(any(Prompt.class))).thenThrow(exception);
		return new OpenAiAssistantProvider(chatModel, AiProviderSettings.fakeDefaults())
			.propose(payload())
			.failure();
	}

	private AiProviderResponse responseFor(String arguments) {
		ChatModel chatModel = mock(ChatModel.class);
		AssistantMessage output = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall(
				"call-1", "function", "propose_game_room_intent", arguments)))
			.build();
		when(chatModel.call(any(Prompt.class))).thenReturn(
			new ChatResponse(List.of(new Generation(output))));
		return new OpenAiAssistantProvider(chatModel, AiProviderSettings.fakeDefaults())
			.propose(payload());
	}

	private AiProviderPayload payload() {
		return new AiProviderPayload(
			"AI-02-INSTRUCTION-V1",
			"propose_game_room_intent",
			"AI-02-SCHEMA-V1",
			"Asia/Seoul",
			"전략 게임 추천",
			List.of("PLAYER_COUNT"));
	}
}
