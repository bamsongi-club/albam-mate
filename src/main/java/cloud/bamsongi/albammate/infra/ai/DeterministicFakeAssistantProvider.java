package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 네트워크·secret·provider 상태에 의존하지 않는 기본 provider다. */
final class DeterministicFakeAssistantProvider implements AiProviderClient {

	private static final Pattern PLAYER_COUNT = Pattern.compile("(?<!\\d)([2-9]|1[01])\\s*(?:명|인)");

	@Override
	public String providerName() {
		return "fake";
	}

	@Override
	public AiProviderResponse propose(AiProviderPayload request) {
		String sentence = request.currentUserSentence().toLowerCase(Locale.ROOT);
		if (sentence.contains("지원하지 않는") || sentence.contains("unsupported")) {
			return response("UNSUPPORTED", List.of(), List.of(), List.of(), null, null, null);
		}
		List<String> categories = sentence.contains("전략") || sentence.contains("strategy")
			? List.of("STRATEGY") : List.of();
		List<String> mechanisms = sentence.contains("일꾼 배치") || sentence.contains("worker placement")
			? List.of("WORKER_PLACEMENT") : List.of();
		List<String> themes = sentence.contains("공포") || sentence.contains("horror")
			? List.of("HORROR") : List.of();
		if (!categories.isEmpty() || !mechanisms.isEmpty() || !themes.isEmpty()) {
			return response("RECOMMEND", categories, mechanisms, themes, complexityMax(sentence), playTimeMax(sentence),
				playerCount(sentence));
		}
		return response("NEEDS_INPUT", List.of(), List.of(), List.of(), null, null, null);
	}

	private AiProviderResponse response(
		String action,
		List<String> categories,
		List<String> mechanisms,
		List<String> themes,
		BigDecimal complexityMax,
		String playTimeMax,
		Integer playerCount) {
		return AiProviderResponse.success(action, categories, mechanisms, themes, complexityMax, playTimeMax,
			playerCount,
			7, 3, new BigDecimal("0.10"));
	}

	private BigDecimal complexityMax(String sentence) {
		if (sentence.contains("쉬운") || sentence.contains("easy")) {
			return new BigDecimal("2.00");
		}
		if (sentence.contains("어려운") || sentence.contains("hard")) {
			return new BigDecimal("4.00");
		}
		return null;
	}

	private String playTimeMax(String sentence) {
		if (sentence.contains("10분")) {
			return "UP_TO_10";
		}
		if (sentence.contains("20분")) {
			return "OVER_10_TO_20";
		}
		if (sentence.contains("30분")) {
			return "OVER_20_TO_30";
		}
		if (sentence.contains("60분")) {
			return "OVER_30_TO_60";
		}
		return null;
	}

	private Integer playerCount(String sentence) {
		Matcher matcher = PLAYER_COUNT.matcher(sentence);
		return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
	}
}
