package cloud.bamsongi.albammate.game.contract;

import java.util.Optional;

/** 요청 문장 안 유일한 정식 게임명을 provider 전에 조회하는 game 경계다. */
public interface AssistantExactGameNameQuery {

	Optional<AssistantRecommendationCandidate> findUniqueByNormalizedName(String message);
}
