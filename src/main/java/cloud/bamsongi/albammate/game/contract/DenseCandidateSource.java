package cloud.bamsongi.albammate.game.contract;

import java.util.List;

/** 승인된 dense index가 반환한 결정적 후보 순서를 읽는 game 포트다. */
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
