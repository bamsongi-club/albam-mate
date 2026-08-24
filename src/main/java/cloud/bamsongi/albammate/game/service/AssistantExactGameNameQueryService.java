package cloud.bamsongi.albammate.game.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameMatch;
import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameQuery;
import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.RequiredArgsConstructor;

/** DB 후보 projection을 읽은 뒤 Java 정규화로 문장 안 유일 정식명만 판정한다. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssistantExactGameNameQueryService implements AssistantExactGameNameQuery {

	private final GameRepository gameRepository;

	@Override
	public Optional<AssistantRecommendationCandidate> findUniqueByNormalizedName(String message) {
		if (message == null || message.isBlank()) {
			return Optional.empty();
		}
		String normalizedMessage = normalize(message);
		var matches = gameRepository.findAssistantExactGameNameMatches().stream()
			.filter(match -> containsUniqueNameToken(normalizedMessage, normalize(match.name())))
			.toList();
		var matchedGameIds = matches.stream().map(AssistantExactGameNameMatch::id).distinct().toList();
		if (matchedGameIds.size() != 1) {
			return Optional.empty();
		}
		return gameRepository.findAssistantRecommendationCandidateById(matchedGameIds.getFirst());
	}

	private boolean containsUniqueNameToken(String message, String gameName) {
		if (gameName.isBlank()) {
			return false;
		}
		int fromIndex = 0;
		while (fromIndex <= message.length() - gameName.length()) {
			int matchIndex = message.indexOf(gameName, fromIndex);
			if (matchIndex < 0) {
				return false;
			}
			int endIndex = matchIndex + gameName.length();
			if (isWhitespaceBoundary(message, matchIndex - 1) && isWhitespaceBoundary(message, endIndex)) {
				return true;
			}
			fromIndex = matchIndex + 1;
		}
		return false;
	}

	private boolean isWhitespaceBoundary(String value, int index) {
		return index < 0 || index >= value.length() || Character.isWhitespace(value.charAt(index));
	}

	private String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC)
			.trim()
			.replaceAll("\\s+", " ")
			.toLowerCase(Locale.ROOT);
	}
}
