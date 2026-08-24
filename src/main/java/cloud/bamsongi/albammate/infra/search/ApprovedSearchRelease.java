package cloud.bamsongi.albammate.infra.search;

record ApprovedSearchRelease(String releaseId, String fieldVersion, String manifestSha256, String searchTextChecksum) {

	boolean matches(ApprovedSearchRelease other) {
		return releaseId.equals(other.releaseId) && fieldVersion.equals(other.fieldVersion)
			&& manifestSha256.equals(other.manifestSha256) && searchTextChecksum.equals(other.searchTextChecksum);
	}

	boolean isComplete() {
		return hasText(releaseId) && hasText(fieldVersion) && sha256(manifestSha256) && sha256(searchTextChecksum);
	}

	private boolean sha256(String value) {
		return value != null && value.matches("[0-9a-fA-F]{64}");
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
