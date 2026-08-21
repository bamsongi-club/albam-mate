package cloud.bamsongi.albammate.infra.search;

import java.util.List;

record ApprovedEmbeddingArtifact(ApprovedSearchRelease release, EmbeddingProvenance provenance, List<Row> rows) {

	record Row(long gameId, double[] embedding) {
	}
}
