package cloud.bamsongi.albammate.infra.search;

import java.util.List;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;

/**
 * 실제 의미 검색 index가 아직 준비되지 않은 개발·배포 환경에서 쓰는 임시 구현체다.
 *
 * 임의의 후보를 만들지 않고 의미 검색을 지금 사용할 수 없다고 알려, 서비스가 안전하게 키워드 검색으로 대체하게 한다.
 */
public class UnavailableSemanticGameCandidateSource implements DenseCandidateSource {

	@Override
	public List<Candidate> findCandidates(String rawQuery) {
		throw new SemanticSearchUnavailableException();
	}
}
