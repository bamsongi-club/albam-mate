package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
	private static final Set<String> ALLOWED_GAME_STYLES = Set.of(
		"STRATEGY", "ABSTRACT_STRATEGY", "COLLECTIBLE", "FAMILY",
		"CHILDREN", "THEMATIC", "PARTY", "WARGAME");
	private static final String V1_INSTRUCTION = """
		You extract a board-game room intent from exactly one current user sentence.
		Call propose_game_room_intent exactly once and return only arguments matching its schema.
		Use RECOMMEND when a game style is present, NEEDS_INPUT when a required style is absent,
		and UNSUPPORTED when the request is not a board-game recommendation.
		Never call any other tool, search for games, create rooms, execute SQL, or infer identifiers.
		Use only the current user sentence, the server-provided missing field names, and Asia/Seoul as reference zone.
		""";
	// action·gameStyles 개수 관계(RECOMMEND면 1개 이상, 아니면 0개)는 OpenAI가 top-level에서 금지하는
	// oneOf로 못 표현해 스키마에서는 빼고, isValidOutput()에서 응답을 받은 뒤 검증한다.
	// uniqueItems도 OpenAI function schema에서 지원하지 않으므로 중복 검증은 isValidOutput()에서 수행한다.
	private static final String TOOL_SCHEMA = """
		{
		  "type":"object",
		  "additionalProperties":false,
		  "properties":{
		    "action":{"type":"string","enum":["RECOMMEND","NEEDS_INPUT","UNSUPPORTED"]},
		    "gameStyles":{
		      "type":"array",
		      "minItems":0,
		      "maxItems":8,
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
				styles(output.get("gameStyles")),
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
		if (output == null || !output.isObject() || output.size() != 2) {
			return false;
		}
		JsonNode action = output.get("action");
		JsonNode gameStyles = output.get("gameStyles");
		if (action == null || !action.isTextual() || gameStyles == null || !gameStyles.isArray()) {
			return false;
		}
		String actionValue = action.asText();
		if (!ALLOWED_ACTIONS.contains(actionValue)
			|| ("RECOMMEND".equals(actionValue) && (gameStyles.size() < 1 || gameStyles.size() > 8))
			|| (!"RECOMMEND".equals(actionValue) && !gameStyles.isEmpty())) {
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
		return true;
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
