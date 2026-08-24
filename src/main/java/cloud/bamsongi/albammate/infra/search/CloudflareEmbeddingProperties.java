package cloud.bamsongi.albammate.infra.search;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.search.cloudflare")
public record CloudflareEmbeddingProperties(
	boolean enabled,
	String accountId,
	String apiToken,
	Duration timeout,
	String releaseId,
	String fieldVersion,
	String manifestSha256,
	String searchTextChecksum) {

	static final int DIMENSION = 1024;
	static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
	static final String PROVIDER = "cloudflare-workers-ai";
	static final String MODEL = "@cf/baai/bge-m3";
	static final String MODE = "text";

	public CloudflareEmbeddingProperties {
		timeout = REQUEST_TIMEOUT;
	}

	boolean isConfigured() {
		return enabled && hasText(accountId) && hasText(apiToken) && expectedRelease() != null;
	}

	ApprovedSearchRelease expectedRelease() {
		ApprovedSearchRelease release = new ApprovedSearchRelease(releaseId, fieldVersion, manifestSha256,
			searchTextChecksum);
		return release.isComplete() ? release : null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
