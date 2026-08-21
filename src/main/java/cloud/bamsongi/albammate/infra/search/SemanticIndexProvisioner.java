package cloud.bamsongi.albammate.infra.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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
		if (!expectedRelease.manifestSha256().equalsIgnoreCase(gameIdMembershipSha256(gameIds))) {
			throw new IllegalArgumentException(
				"approved semantic artifact game id membership does not match the approved manifest checksum");
		}
	}

	/**
	 * 승인 manifest가 고정한 게임 ID 구성원 checksum이다. 라벨만 일치하는 다른 1,000개 게임 집합이
	 * 같은 release/provenance 라벨을 달고 READY로 승격되는 것을 막는다. embedding 값은 Cloudflare가
	 * 매 호출마다 새로 계산하는 비결정적 출력이라 이 checksum에 포함하지 않는다.
	 */
	static String gameIdMembershipSha256(java.util.Set<Long> gameIds) {
		List<Long> sorted = new ArrayList<>(gameIds);
		sorted.sort(Comparator.naturalOrder());
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
		for (Long gameId : sorted) {
			digest.update(Long.toString(gameId).getBytes(StandardCharsets.UTF_8));
			digest.update((byte)',');
		}
		return HexFormat.of().formatHex(digest.digest());
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
