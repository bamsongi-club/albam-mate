package cloud.bamsongi.albammate.infra.search;

import java.util.List;

import org.springframework.dao.DataAccessException;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;

final class PgVectorDenseCandidateSource implements DenseCandidateSource {

	private static final int CANDIDATE_LIMIT = 100;

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
