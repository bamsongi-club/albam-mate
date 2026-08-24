package cloud.bamsongi.albammate.game.contract;

import java.time.Duration;
import java.util.List;

/**
 * mechanism/category/theme/name/alias/description 계열에서 구조화된 sparse 후보를 만드는 창구다.
 *
 * {@link DenseCandidateSource}와 같은 {@code Candidate} 형태를 재사용해 SemanticGameSearchService가
 * 두 후보를 같은 방식으로 병합·재검증하게 한다. 이 단계에서도 후보의 순서만 전달하며, 공개 범위와 P1
 * hard filter를 확인해 최종 결과로 거르는 일은 서비스가 한다.
 */
public interface SparseCandidateSource {

	List<DenseCandidateSource.Candidate> findCandidates(String rawQuery);

	/**
	 * 공통 candidate deadline을 JDBC 같은 동기 I/O 경계까지 전달할 수 있는 sparse source다.
	 */
	interface DeadlineAware extends SparseCandidateSource {

		List<DenseCandidateSource.Candidate> findCandidates(String rawQuery, Duration remainingTimeout);
	}
}
