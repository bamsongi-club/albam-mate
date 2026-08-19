package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentProposal;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;

/** provider 호출 전 정책·quota를 확인하고 구조화 결과만 반환하는 adapter다. */
public final class AiProviderIntentExtractor implements AssistantIntentExtractor {

	private static final String INSTRUCTION_VERSION = "AI-02-INSTRUCTION-V1";
	private static final String SCHEMA_VERSION = "AI-02-SCHEMA-V1";
	private static final String TOOL_NAME = "propose_game_room_intent";
	private static final String REFERENCE_ZONE = "Asia/Seoul";
	private static final Set<String> ALLOWED_MISSING_FIELDS = Set.of(
		"GAME_STYLE", "GAME", "PLAYER_COUNT", "STARTS_AT", "REGION");
	private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern PHONE = Pattern.compile(
		"(?<![\\d+])(?:01[016789][ .-]?\\d{3,4}[ .-]?\\d{4}"
			+ "|(?:02|031|032|033|041|042|043|051|052|053|054|055|061|062|063|064|070)[ .-]?\\d{3,4}[ .-]?\\d{4}"
			+ "|\\+82[ .-]?0?(?:1[016789]|2|3[123]|4[123]|5[12345]|6[1234]|70)[ .-]?\\d{3,4}[ .-]?\\d{4})(?!\\d)");
	private static final Pattern LONG_NUMBER = Pattern.compile("(?<!\\d)\\d{6,}(?!\\d)");
	private static final Pattern UUID = Pattern.compile(
		"(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}(?![0-9a-f])");
	private static final Pattern API_CREDENTIAL = Pattern.compile(
		"(?i)(?<![a-z0-9])(?:sk-[a-z0-9_-]{16,}|gh[pousr]_[a-z0-9_]{20,}|github_pat_[a-z0-9_]{20,}|akia[0-9a-z]{16}|AIza[a-z0-9_-]{20,}|xox[baprs]-[a-z0-9-]{10,}|eyJ[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,}\\.[a-z0-9_-]{10,})(?![a-z0-9])");
	private static final Pattern PEM_PRIVATE_KEY = Pattern.compile(
		"(?is)-----BEGIN\\s+(?:[a-z0-9 ]+\\s+)?PRIVATE KEY-----");
	private static final Pattern KOREAN_STREET_ADDRESS = Pattern.compile(
		"(?<![가-힣])(?:(?:[가-힣]+(?:특별시|광역시|시|도)\\s+)?(?:[가-힣]+(?:구|군)\\s+))?[가-힣0-9]+(?:로|길)\\s*\\d+(?:-\\d+)?");

	private final AiProviderClient provider;
	private final AiQuotaLedger quotaLedger;
	private final AssistantUsageEventSink usageEventSink;
	private final AiProviderSettings settings;
	private final Clock clock;

	public AiProviderIntentExtractor(
		AiProviderClient provider,
		AiQuotaLedger quotaLedger,
		AssistantUsageEventSink usageEventSink,
		AiProviderSettings settings,
		Clock clock) {
		this.provider = Objects.requireNonNull(provider, "provider");
		this.quotaLedger = Objects.requireNonNull(quotaLedger, "quotaLedger");
		this.usageEventSink = Objects.requireNonNull(usageEventSink, "usageEventSink");
		this.settings = Objects.requireNonNull(settings, "settings");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public AssistantIntentExtraction extract(AssistantIntentRequest request) {
		Objects.requireNonNull(request, "request");
		if (!request.externalProcessingConsented()) {
			return failure(AssistantIntentStatus.CONSENT_REQUIRED);
		}
		if (!settings.readyForCall()) {
			return failure(AssistantIntentStatus.NOT_ENABLED);
		}
		if (!hasAllowedMissingFields(request)
			|| containsSensitiveInput(request.currentUserSentence())
			|| request.missingFields().stream().anyMatch(this::containsSensitiveInput)) {
			return failure(AssistantIntentStatus.SENSITIVE_INPUT_REJECTED);
		}
		Instant startedAt = clock.instant();
		AiQuotaReservation reservation = quotaLedger.reserve(
			request.quotaSubject(), startedAt, settings.reservationCostUsd());
		if (reservation.status() != AiQuotaReservationStatus.ACQUIRED) {
			return failure(statusFor(reservation.status()));
		}
		AiProviderResponse response;
		try {
			response = provider.propose(new AiProviderPayload(
				INSTRUCTION_VERSION,
				TOOL_NAME,
				SCHEMA_VERSION,
				REFERENCE_ZONE,
				request.currentUserSentence(),
				request.missingFields()));
		} catch (RuntimeException exception) {
			response = AiProviderResponse.failure(AiProviderFailure.SERVICE_UNAVAILABLE);
		}
		BigDecimal chargedCostUsd = response.costUsd().signum() > 0
			? response.costUsd()
			: reservation.reservedCostUsd();
		AssistantIntentExtraction result = response.succeeded()
			? success(response, startedAt)
			: failure(statusFor(response.failure()), response, startedAt, chargedCostUsd);
		AiQuotaCompletionStatus completionStatus = completeOnce(reservation, chargedCostUsd);
		if (result.usage() != null) {
			usageEventSink.record(result.usage());
		}
		if (completionStatus != AiQuotaCompletionStatus.COMPLETED) {
			if (completionStatus == AiQuotaCompletionStatus.UNAVAILABLE) {
				quotaLedger.scheduleCompletionRetry(reservation, chargedCostUsd);
			}
			return failure(AssistantIntentStatus.SERVICE_UNAVAILABLE);
		}
		return result;
	}

	private AiQuotaCompletionStatus completeOnce(AiQuotaReservation reservation, BigDecimal costUsd) {
		try {
			return quotaLedger.complete(reservation, costUsd);
		} catch (RuntimeException exception) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
	}

	private AssistantIntentExtraction success(AiProviderResponse response, Instant startedAt) {
		AssistantUsageEvent usage = usage(response, "SUCCESS", startedAt, response.costUsd());
		return new AssistantIntentExtraction(AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal(response.action(), response.gameStyles()), usage, false);
	}

	private AssistantIntentExtraction failure(AssistantIntentStatus status) {
		return new AssistantIntentExtraction(status, null, null, false);
	}

	private AssistantIntentExtraction failure(AssistantIntentStatus status, AiProviderResponse response,
		Instant startedAt, BigDecimal costUsd) {
		return new AssistantIntentExtraction(status, null, usage(response, status.name(), startedAt, costUsd), false);
	}

	private AssistantUsageEvent usage(
		AiProviderResponse response,
		String status,
		Instant startedAt,
		BigDecimal costUsd) {
		return new AssistantUsageEvent(provider.providerName(), settings.model(), "AI-02", INSTRUCTION_VERSION,
			SCHEMA_VERSION,
			response.inputTokens(), response.outputTokens(), response.inputTokens() + response.outputTokens(),
			Duration.between(startedAt, clock.instant()), status, costUsd);
	}

	private AssistantIntentStatus statusFor(AiQuotaReservationStatus status) {
		return switch (status) {
			case UNAVAILABLE -> AssistantIntentStatus.SERVICE_UNAVAILABLE;
			case USER_LIMIT_REACHED, CONCURRENT_LIMIT_REACHED -> AssistantIntentStatus.QUOTA_EXCEEDED;
			case COST_CAP_REACHED -> AssistantIntentStatus.COST_CAP_REACHED;
			case ACQUIRED -> throw new IllegalArgumentException("acquired reservation is not a failure");
		};
	}

	private AssistantIntentStatus statusFor(AiProviderFailure failure) {
		return switch (failure) {
			case TIMEOUT -> AssistantIntentStatus.PROVIDER_TIMEOUT;
			case RATE_LIMITED -> AssistantIntentStatus.PROVIDER_RATE_LIMITED;
			case INPUT_TOO_LARGE -> AssistantIntentStatus.PROVIDER_INPUT_TOO_LARGE;
			case INVALID_SCHEMA -> AssistantIntentStatus.INVALID_PROVIDER_SCHEMA;
			case SERVICE_UNAVAILABLE -> AssistantIntentStatus.SERVICE_UNAVAILABLE;
		};
	}

	private boolean hasAllowedMissingFields(AssistantIntentRequest request) {
		return request.missingFields().stream().allMatch(ALLOWED_MISSING_FIELDS::contains);
	}

	private boolean containsSensitiveInput(String sentence) {
		String normalized = sentence.toLowerCase(java.util.Locale.ROOT);
		return normalized.contains("secret")
			|| normalized.contains("token")
			|| normalized.contains("api key")
			|| normalized.contains("apikey")
			|| normalized.contains("api-key")
			|| normalized.contains("credential")
			|| normalized.contains("authorization")
			|| normalized.contains("bearer")
			|| normalized.contains("password")
			|| normalized.contains("passcode")
			|| normalized.contains("비밀번호")
			|| normalized.contains("패스워드")
			|| normalized.contains("암호")
			|| normalized.contains("인증번호")
			|| normalized.contains("시크릿")
			|| EMAIL.matcher(sentence).find()
			|| PHONE.matcher(sentence).find()
			|| LONG_NUMBER.matcher(sentence).find()
			|| UUID.matcher(sentence).find()
			|| API_CREDENTIAL.matcher(sentence).find()
			|| PEM_PRIVATE_KEY.matcher(sentence).find()
			|| KOREAN_STREET_ADDRESS.matcher(sentence).find();
	}
}
