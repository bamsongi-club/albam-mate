package cloud.bamsongi.albammate.game.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameQuery;
import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.RequiredArgsConstructor;

/** DB 후보 projection을 읽은 뒤 Java 정규화로 유일 정식명만 판정한다. */
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
		var matches = gameRepository.findAssistantRecommendationCandidates().stream()
			.filter(candidate -> normalize(candidate.name()).equals(normalizedMessage))
			.toList();
		return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
	}

	private String normalize(String value) {
		return Normalizer.normalize(value, Normalizer.Form.NFKC)
			.trim()
			.replaceAll("\\s+", " ")
			.toLowerCase(Locale.ROOT);
	}
}
