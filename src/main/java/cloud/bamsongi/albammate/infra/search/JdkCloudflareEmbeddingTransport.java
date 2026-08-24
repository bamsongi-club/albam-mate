package cloud.bamsongi.albammate.infra.search;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JdkCloudflareEmbeddingTransport implements CloudflareEmbeddingTransport {

	private final HttpClient httpClient;

	JdkCloudflareEmbeddingTransport(HttpClient httpClient) {
		this.httpClient = httpClient;
	}

	@Override
	public EmbeddingHttpResponse post(URI uri, String apiToken, String requestBody, Duration timeout)
		throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(timeout)
			.header("Authorization", "Bearer " + apiToken)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(requestBody))
			.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		return new EmbeddingHttpResponse(response.statusCode(), response.body());
	}
}
