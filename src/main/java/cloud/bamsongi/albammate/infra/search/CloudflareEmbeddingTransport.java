package cloud.bamsongi.albammate.infra.search;

import java.net.URI;
import java.time.Duration;

interface CloudflareEmbeddingTransport {

	EmbeddingHttpResponse post(URI uri, String apiToken, String requestBody, Duration timeout) throws Exception;

	record EmbeddingHttpResponse(int statusCode, String body) {
	}
}
