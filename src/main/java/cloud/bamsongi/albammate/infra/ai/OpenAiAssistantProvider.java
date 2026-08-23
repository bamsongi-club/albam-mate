package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(OpenAiAssistantProvider.class);
	private static final String TOOL_NAME = "propose_game_room_intent";
	private static final Set<String> ALLOWED_ACTIONS = Set.of("RECOMMEND", "NEEDS_INPUT", "UNSUPPORTED");
	private static final Set<String> PLAY_TIME_MAX_VALUES = Set.of(
		"UP_TO_10", "OVER_10_TO_20", "OVER_20_TO_30", "OVER_30_TO_60", "OVER_60_UNDER_90", "AT_LEAST_90");
	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
	private static final String V1_INSTRUCTION = """
		You extract a board-game room intent from exactly one current user sentence.
		Call propose_game_room_intent exactly once and return only arguments matching its schema.
		Use RECOMMEND when a category, mechanism, or theme is present, NEEDS_INPUT when no search condition is present,
		and UNSUPPORTED when the request is not a board-game recommendation.
		Never call any other tool, search for games, create rooms, execute SQL, or infer identifiers.
		Use only the current user sentence, the server-provided missing field names, and Asia/Seoul as reference zone.
		""";
	// action·조건 배열 개수 관계(RECOMMEND면 하나 이상, 아니면 모두 비어 있음)는 OpenAI가 top-level에서 금지하는
	// oneOf로 못 표현해 스키마에서는 빼고, isValidOutput()에서 응답을 받은 뒤 검증한다.
	// uniqueItems도 OpenAI function schema에서 지원하지 않으므로 중복 검증은 isValidOutput()에서 수행한다.
	private static final String TOOL_SCHEMA = """
		{
		  "type":"object",
		  "additionalProperties":false,
		  "properties":{
		    "action":{"type":"string","enum":["RECOMMEND","NEEDS_INPUT","UNSUPPORTED"]},
			"categories":{
			  "type":"array",
			  "minItems":0,
			  "maxItems":8,
			  "items":{"type":"string","pattern":"^[A-Z][A-Z0-9_]*$"}
			},
			"mechanisms":{
			  "type":"array",
			  "minItems":0,
			  "maxItems":8,
			  "items":{"type":"string","pattern":"^[A-Z][A-Z0-9_]*$"}
			},
			"themes":{
			  "type":"array",
			  "minItems":0,
			  "maxItems":8,
			  "items":{"type":"string","pattern":"^[A-Z][A-Z0-9_]*$"}
			},
			"complexityMax":{"type":["number","null"],"minimum":1.0,"maximum":5.0},
			"playTimeMax":{"type":["string","null"],"enum":["UP_TO_10","OVER_10_TO_20","OVER_20_TO_30","OVER_30_TO_60","OVER_60_UNDER_90","AT_LEAST_90",null]},
			"playerCount":{"type":["integer","null"],"minimum":2,"maximum":11}
		  },
		  "required":["action","categories","mechanisms","themes","complexityMax","playTimeMax","playerCount"]
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
		try {
			ChatResponse response = chatModel.call(promptFor(request));
			Usage usage = response.getMetadata().getUsage();
			int inputTokens = tokenCount(usage == null ? null : usage.getPromptTokens());
			int outputTokens = tokenCount(usage == null ? null : usage.getCompletionTokens());
			Generation generation = response.getResult();
			String arguments = toolArguments(generation);
			if (arguments == null) {
				return AiProviderResponse.failure(AiProviderFailure.INVALID_SCHEMA);
			}
			JsonNode output = objectMapper.readTree(arguments);
			if (!isValidOutput(output)) {
				return AiProviderResponse.failure(AiProviderFailure.INVALID_SCHEMA);
			}
			return AiProviderResponse.success(
				output.get("action").asText(),
				codes(output.get("categories")),
				codes(output.get("mechanisms")),
				codes(output.get("themes")),
				decimalOrNull(output.get("complexityMax")),
				textOrNull(output.get("playTimeMax")),
				integerOrNull(output.get("playerCount")),
				inputTokens,
				outputTokens,
				costEstimate(inputTokens, outputTokens));
		} catch (JacksonException exception) {
			log.warn("assistant provider returned an unparseable tool payload", exception);
			return AiProviderResponse.failure(AiProviderFailure.INVALID_SCHEMA);
		} catch (RuntimeException exception) {
			log.warn("assistant provider call failed", exception);
			return AiProviderResponse.failure(failureFor(exception));
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
			.store(false)
			.toolCallbacks(List.of(INTENT_TOOL))
			.toolChoice("{\"type\":\"function\",\"function\":{\"name\":\"" + TOOL_NAME + "\"}}")
			.parallelToolCalls(false)
			.internalToolExecutionEnabled(false)
			.reasoningEffort("none")
			.build();
	}

	private String userText(AiProviderPayload request) {
		return "instructionVersion=" + request.instructionVersion()
			+ "\nschemaVersion=" + request.schemaVersion()
			+ "\nreferenceZoneId=" + request.referenceZoneId()
			+ "\ncurrentUserSentence=" + request.currentUserSentence()
			+ "\nmissingFields=" + String.join(",", request.missingFields());
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
		if (output == null || !output.isObject() || output.size() != 7) {
			return false;
		}
		JsonNode action = output.get("action");
		JsonNode categories = output.get("categories");
		JsonNode mechanisms = output.get("mechanisms");
		JsonNode themes = output.get("themes");
		JsonNode complexityMax = output.get("complexityMax");
		JsonNode playTimeMax = output.get("playTimeMax");
		JsonNode playerCount = output.get("playerCount");
		if (action == null || !action.isTextual()
			|| !isValidCodes(categories) || !isValidCodes(mechanisms) || !isValidCodes(themes)
			|| !isValidComplexity(complexityMax) || !isValidPlayTime(playTimeMax) || !isValidPlayerCount(playerCount)) {
			return false;
		}
		String actionValue = action.asText();
		boolean hasSearchCondition = !categories.isEmpty() || !mechanisms.isEmpty() || !themes.isEmpty();
		boolean hasRefinement = !complexityMax.isNull() || !playTimeMax.isNull() || !playerCount.isNull();
		if (!ALLOWED_ACTIONS.contains(actionValue)
			|| ("RECOMMEND".equals(actionValue) && !hasSearchCondition)
			|| (!"RECOMMEND".equals(actionValue) && hasSearchCondition)
			|| ("UNSUPPORTED".equals(actionValue) && hasRefinement)) {
			return false;
		}
		return true;
	}

	private boolean isValidCodes(JsonNode codes) {
		if (codes == null || !codes.isArray() || codes.size() > 8) {
			return false;
		}
		Set<String> seenCodes = new HashSet<>();
		for (JsonNode code : codes) {
			if (!code.isTextual() || !CODE.matcher(code.asText()).matches() || !seenCodes.add(code.asText())) {
				return false;
			}
		}
		return true;
	}

	private boolean isValidComplexity(JsonNode complexityMax) {
		return complexityMax != null && (complexityMax.isNull()
			|| (complexityMax.isNumber() && complexityMax.decimalValue().compareTo(new BigDecimal("1.00")) >= 0
				&& complexityMax.decimalValue().compareTo(new BigDecimal("5.00")) <= 0));
	}

	private boolean isValidPlayTime(JsonNode playTimeMax) {
		return playTimeMax != null && (playTimeMax.isNull()
			|| (playTimeMax.isTextual() && PLAY_TIME_MAX_VALUES.contains(playTimeMax.asText())));
	}

	private boolean isValidPlayerCount(JsonNode playerCount) {
		return playerCount != null && (playerCount.isNull()
			|| (playerCount.isIntegralNumber() && playerCount.canConvertToInt()
				&& playerCount.intValue() >= 2 && playerCount.intValue() <= 11));
	}

	private List<String> codes(JsonNode codes) {
		List<String> values = new ArrayList<>();
		for (JsonNode code : codes) {
			values.add(code.asText());
		}
		return values;
	}

	private BigDecimal decimalOrNull(JsonNode value) {
		return value.isNull() ? null : value.decimalValue();
	}

	private String textOrNull(JsonNode value) {
		return value.isNull() ? null : value.asText();
	}

	private Integer integerOrNull(JsonNode value) {
		return value.isNull() ? null : value.intValue();
	}

	private int tokenCount(Integer tokens) {
		return tokens == null || tokens < 0 ? 0 : tokens;
	}

	private BigDecimal costEstimate(int inputTokens, int outputTokens) {
		return settings.reservationCostUsd();
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
