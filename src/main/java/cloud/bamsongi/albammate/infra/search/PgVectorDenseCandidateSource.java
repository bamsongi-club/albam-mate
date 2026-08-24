package cloud.bamsongi.albammate.infra.search;

import java.util.List;

import org.springframework.dao.DataAccessException;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;

final class PgVectorDenseCandidateSource implements DenseCandidateSource {

	/**
	 * 초기 corpus 전체(1,000건) 순서를 core에 넘긴다. hard filter·페이지 경계는
	 * SemanticGameSearchService가 이 목록 위에서 적용하므로, 일부만 잘라 보내면 101위 밖의
	 * 필터 통과 게임과 큰 페이지가 결과에서 사라진다.
	 */
	private static final int CANDIDATE_LIMIT = 1000;

	private final CloudflareEmbeddingClient embeddingClient;
	private final PgVectorSemanticIndexRepository repository;
	private final ApprovedSearchRelease expectedRelease;

	PgVectorDenseCandidateSource(CloudflareEmbeddingClient embeddingClient,
		PgVectorSemanticIndexRepository repository, ApprovedSearchRelease expectedRelease) {
		this.embeddingClient = embeddingClient;
		this.repository = repository;
		this.expectedRelease = expectedRelease;
	}

	@Override
	public List<Candidate> findCandidates(String rawQuery) {
		try {
			EmbeddingProvenance provenance = EmbeddingProvenance.cloudflareBgeM3();
			if (!repository.hasActiveMatchingIndex(expectedRelease, provenance)) {
				throw new SemanticSearchUnavailableException();
			}
			List<Candidate> candidates = repository.findActiveCandidates(embeddingClient.embed(rawQuery),
				expectedRelease,
				provenance, CANDIDATE_LIMIT);
			if (candidates.isEmpty()) {
				throw new SemanticSearchUnavailableException();
			}
			return candidates;
		} catch (SemanticSearchUnavailableException exception) {
			throw exception;
		} catch (DataAccessException exception) {
			throw new SemanticSearchUnavailableException();
		}
	}
}
