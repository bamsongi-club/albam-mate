package cloud.bamsongi.albammate.game.contract;

import java.util.List;

/**
 * 의미 검색 모델이 찾아낸 게임 후보를 받아오는 창구다.
 *
 * 이 단계에서는 후보의 순서만 전달한다. 공개 범위와 P1 필터를 확인해 최종 결과로 거르는 일은 서비스가 한다.
 */
public interface DenseCandidateSource {

	List<Candidate> findCandidates(String rawQuery);

	record Candidate(long gameId, double relevance) {

		public Candidate {
			if (gameId <= 0 || !Double.isFinite(relevance)) {
				throw new IllegalArgumentException("gameId must be positive and relevance must be finite");
			}
		}
	}
}
