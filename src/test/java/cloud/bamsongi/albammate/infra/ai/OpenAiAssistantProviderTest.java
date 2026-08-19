package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
		assertTrue(options.getToolCallbacks().getFirst().getToolDefinition().inputSchema().contains("gameStyles"));
		assertTrue(String.valueOf(options.getToolChoice()).contains("propose_game_room_intent"));
		assertTrue(prompt.getInstructions().getFirst() instanceof SystemMessage);
		assertTrue(prompt.getSystemMessage().getText().contains("exactly once"));
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
				"{\"action\":\"RECOMMEND\",\"gameStyles\":[\"STRATEGY\"]}")))
			.build();
		when(chatModel.call(any(Prompt.class))).thenReturn(
			new ChatResponse(List.of(new Generation(output))));
		OpenAiAssistantProvider provider = new OpenAiAssistantProvider(chatModel, AiProviderSettings.fakeDefaults());

		AiProviderResponse response = provider.propose(payload());

		assertTrue(response.succeeded());
		assertEquals("RECOMMEND", response.action());
		assertEquals(List.of("STRATEGY"), response.gameStyles());
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
