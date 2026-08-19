package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.openai.errors.OpenAIServiceException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 명시적인 local-openai 경계에서만 Spring AI OpenAI adapter를 호출한다. */
final class OpenAiAssistantProvider implements AiProviderClient {

	private static final String TOOL_NAME = "propose_game_room_intent";
	private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");
	private static final Set<String> ALLOWED_GAME_STYLES = Set.of(
		"STRATEGY", "ABSTRACT_STRATEGY", "COLLECTIBLE", "FAMILY",
		"CHILDREN", "THEMATIC", "PARTY", "WARGAME");
	private static final String V1_INSTRUCTION = """
		You extract a board-game room intent from exactly one current user sentence.
		Call propose_game_room_intent exactly once and return only arguments matching its schema.
		Never call any other tool, search for games, create rooms, execute SQL, or infer identifiers.
		Use only the current user sentence, the server-provided missing field names, and Asia/Seoul as reference zone.
		""";
	private static final String TOOL_SCHEMA = """
		{
		  "$schema":"https://json-schema.org/draft/2020-12/schema",
		  "type":"object",
		  "additionalProperties":false,
		  "properties":{
		    "action":{"type":"string","enum":["RECOMMEND"]},
		    "gameStyles":{
		      "type":"array",
		      "minItems":1,
		      "maxItems":8,
		      "uniqueItems":true,
		      "items":{
		        "type":"string",
		        "enum":["STRATEGY","ABSTRACT_STRATEGY","COLLECTIBLE","FAMILY","CHILDREN","THEMATIC","PARTY","WARGAME"]
		      }
		    }
		  },
		  "required":["action","gameStyles"]
		}
		""";
	private static final ToolCallback INTENT_TOOL = new ToolCallback() {
		@Override
		public ToolDefinition getToolDefinition() {
			return ToolDefinition.builder()
				.name(TOOL_NAME)
				.description("Return the structured game-room intent")
				.inputSchema(TOOL_SCHEMA)
				.build();
		}

		@Override
		public String call(String toolInput) {
			throw new UnsupportedOperationException("AI tool execution is disabled");
		}
	};

	private final ChatModel chatModel;
	private final AiProviderSettings settings;
	private final ObjectMapper objectMapper = new ObjectMapper();

	OpenAiAssistantProvider(ChatModel chatModel, AiProviderSettings settings) {
		this.chatModel = chatModel;
		this.settings = settings;
	}

	@Override
	public String providerName() {
		return "openai";
	}

	@Override
	public AiProviderResponse propose(AiProviderPayload request) {
		if (!withinInputBudget(request)) {
			return AiProviderResponse.failure(AiProviderFailure.INPUT_TOO_LARGE);
		}
		int inputTokens = 0;
		int outputTokens = 0;
		BigDecimal costUsd = BigDecimal.ZERO;
		try {
			ChatResponse response = chatModel.call(promptFor(request));
			Usage usage = response.getMetadata().getUsage();
			inputTokens = tokenCount(usage == null ? null : usage.getPromptTokens());
			outputTokens = tokenCount(usage == null ? null : usage.getCompletionTokens());
			costUsd = costEstimate(inputTokens, outputTokens);
			Generation generation = response.getResult();
			String arguments = toolArguments(generation);
			if (arguments == null) {
				return AiProviderResponse.failure(AiProviderFailure.INVALID_SCHEMA, inputTokens, outputTokens, costUsd);
			}
			JsonNode output = objectMapper.readTree(arguments);
			if (!isValidOutput(output)) {
				return AiProviderResponse.failure(AiProviderFailure.INVALID_SCHEMA, inputTokens, outputTokens, costUsd);
			}
			return AiProviderResponse.success(
				output.get("action").asText(),
				styles(output.get("gameStyles")),
				inputTokens,
				outputTokens,
				costUsd);
		} catch (JacksonException exception) {
			return AiProviderResponse.failure(AiProviderFailure.INVALID_SCHEMA, inputTokens, outputTokens, costUsd);
		} catch (RuntimeException exception) {
			return AiProviderResponse.failure(failureFor(exception), inputTokens, outputTokens, costUsd);
		}
	}

	Prompt promptFor(AiProviderPayload request) {
		return new Prompt(List.of(
			new SystemMessage(V1_INSTRUCTION),
			new UserMessage(userText(request))), optionsFor(request));
	}

	OpenAiChatOptions optionsFor(AiProviderPayload request) {
		return OpenAiChatOptions.builder()
			.model(settings.model())
			.timeout(settings.timeout())
			.maxRetries(0)
			.maxCompletionTokens(settings.maxOutputTokens())
			.store(false)
			.toolCallbacks(List.of(INTENT_TOOL))
			.toolChoice("{\"type\":\"function\",\"function\":{\"name\":\"" + TOOL_NAME + "\"}}")
			.parallelToolCalls(false)
			.internalToolExecutionEnabled(false)
			.build();
	}

	private String userText(AiProviderPayload request) {
		return "instructionVersion=" + request.instructionVersion()
			+ "\nschemaVersion=" + request.schemaVersion()
			+ "\nreferenceZoneId=" + request.referenceZoneId()
			+ "\ncurrentUserSentence=" + request.currentUserSentence()
			+ "\nmissingFields=" + String.join(",", request.missingFields());
	}

	private boolean withinInputBudget(AiProviderPayload request) {
		int promptBytes = V1_INSTRUCTION.getBytes(StandardCharsets.UTF_8).length
			+ TOOL_SCHEMA.getBytes(StandardCharsets.UTF_8).length
			+ userText(request).getBytes(StandardCharsets.UTF_8).length;
		return promptBytes <= settings.maxInputTokens();
	}

	private String toolArguments(Generation generation) {
		if (generation == null || generation.getOutput() == null) {
			return null;
		}
		AssistantMessage output = generation.getOutput();
		if (output.getToolCalls().size() != 1) {
			return null;
		}
		AssistantMessage.ToolCall toolCall = output.getToolCalls().getFirst();
		if (!TOOL_NAME.equals(toolCall.name())) {
			return null;
		}
		return toolCall.arguments();
	}

	private boolean isValidOutput(JsonNode output) {
		if (output == null || !output.isObject() || output.size() != 2) {
			return false;
		}
		JsonNode action = output.get("action");
		JsonNode gameStyles = output.get("gameStyles");
		if (action == null || !action.isTextual() || gameStyles == null || !gameStyles.isArray()) {
			return false;
		}
		if (gameStyles.size() < 1 || gameStyles.size() > 8) {
			return false;
		}
		Set<String> seenGameStyles = new HashSet<>();
		for (JsonNode gameStyle : gameStyles) {
			if (!gameStyle.isTextual()
				|| !ALLOWED_GAME_STYLES.contains(gameStyle.asText())
				|| !seenGameStyles.add(gameStyle.asText())) {
				return false;
			}
		}
		return "RECOMMEND".equals(action.asText());
	}

	private List<String> styles(JsonNode gameStyles) {
		List<String> styles = new ArrayList<>();
		for (JsonNode gameStyle : gameStyles) {
			styles.add(gameStyle.asText());
		}
		return styles;
	}

	private int tokenCount(Integer tokens) {
		return tokens == null || tokens < 0 ? 0 : tokens;
	}

	private BigDecimal costEstimate(int inputTokens, int outputTokens) {
		BigDecimal inputCost = settings.inputTokenPriceUsdPerMillion()
			.multiply(BigDecimal.valueOf(inputTokens));
		BigDecimal outputCost = settings.outputTokenPriceUsdPerMillion()
			.multiply(BigDecimal.valueOf(outputTokens));
		return inputCost.add(outputCost).divide(TOKENS_PER_MILLION, 8, RoundingMode.CEILING);
	}

	private AiProviderFailure failureFor(Throwable exception) {
		for (Throwable current = exception; current != null; current = current.getCause()) {
			if (current instanceof OpenAIServiceException serviceException) {
				return serviceException.statusCode() == 429
					? AiProviderFailure.RATE_LIMITED
					: AiProviderFailure.SERVICE_UNAVAILABLE;
			}
			String type = current.getClass().getName().toLowerCase(Locale.ROOT);
			String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
			if (type.contains("ratelimit") || type.contains("toomany") || message.contains("429")) {
				return AiProviderFailure.RATE_LIMITED;
			}
			if (type.contains("timeout") || message.contains("timeout")) {
				return AiProviderFailure.TIMEOUT;
			}
		}
		return AiProviderFailure.SERVICE_UNAVAILABLE;
	}
}
