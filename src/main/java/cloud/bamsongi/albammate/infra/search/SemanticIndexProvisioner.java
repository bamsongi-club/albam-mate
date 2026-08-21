package cloud.bamsongi.albammate.infra.search;

import java.util.HashSet;
import java.util.UUID;

final class SemanticIndexProvisioner {

	private static final int INITIAL_ROW_COUNT = 1000;

	private final PgVectorSemanticIndexRepository repository;
	private final ApprovedSearchRelease expectedRelease;
	private final EmbeddingProvenance expectedProvenance;

	SemanticIndexProvisioner(PgVectorSemanticIndexRepository repository, ApprovedSearchRelease expectedRelease,
		EmbeddingProvenance expectedProvenance) {
		this.repository = repository;
		this.expectedRelease = expectedRelease;
		this.expectedProvenance = expectedProvenance;
	}

	UUID activate(ApprovedEmbeddingArtifact artifact) {
		UUID versionId = repository.createBuilding(artifact.release(), artifact.provenance());
		try {
			validate(artifact);
			repository.insertRows(versionId, artifact.rows());
			repository.activate(versionId);
			return versionId;
		} catch (RuntimeException exception) {
			repository.fail(versionId);
			throw exception;
		}
	}

	void rollbackTo(UUID versionId) {
		repository.rollbackTo(versionId);
	}

	private void validate(ApprovedEmbeddingArtifact artifact) {
		if (!expectedRelease.isComplete() || !expectedRelease.matches(artifact.release())
			|| !expectedProvenance.equals(artifact.provenance())
			|| artifact.rows().size() != INITIAL_ROW_COUNT) {
			throw new IllegalArgumentException("approved semantic artifact does not match the release contract");
		}
		HashSet<Long> gameIds = new HashSet<>();
		for (ApprovedEmbeddingArtifact.Row row : artifact.rows()) {
			if (row.gameId() <= 0 || !gameIds.add(row.gameId()) || !validVector(row.embedding())) {
				throw new IllegalArgumentException("approved semantic artifact contains invalid embeddings");
			}
		}
	}

	private boolean validVector(double[] vector) {
		if (vector == null || vector.length != CloudflareEmbeddingProperties.DIMENSION) {
			return false;
		}
		double squaredNorm = 0;
		for (double value : vector) {
			if (!Double.isFinite(value)) {
				return false;
			}
			squaredNorm += value * value;
		}
		if (!Double.isFinite(squaredNorm) || squaredNorm == 0) {
			return false;
		}
		return !expectedProvenance.l2Normalized() || Math.abs(Math.sqrt(squaredNorm) - 1.0) <= 0.000001;
	}
}
