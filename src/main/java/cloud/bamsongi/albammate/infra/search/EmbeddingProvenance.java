package cloud.bamsongi.albammate.infra.search;

record EmbeddingProvenance(String provider, String model, String mode, int dimension, boolean l2Normalized) {

	static EmbeddingProvenance cloudflareBgeM3() {
		return new EmbeddingProvenance(CloudflareEmbeddingProperties.PROVIDER, CloudflareEmbeddingProperties.MODEL,
			CloudflareEmbeddingProperties.MODE, CloudflareEmbeddingProperties.DIMENSION, true);
	}
}
