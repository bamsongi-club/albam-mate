package cloud.bamsongi.albammate.infra.search;

import java.net.URI;
import java.util.List;
import java.util.Map;

import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class CloudflareEmbeddingClient {

	private final CloudflareEmbeddingProperties properties;
	private final CloudflareEmbeddingTransport transport;
	private final ObjectMapper objectMapper;

	CloudflareEmbeddingClient(CloudflareEmbeddingProperties properties, CloudflareEmbeddingTransport transport,
		ObjectMapper objectMapper) {
		this.properties = properties;
		this.transport = transport;
		this.objectMapper = objectMapper;
	}

	double[] embed(String query) {
		try {
			String body = objectMapper.writeValueAsString(Map.of("text", List.of(query)));
			CloudflareEmbeddingTransport.EmbeddingHttpResponse response = transport.post(endpoint(),
				properties.apiToken(), body, properties.timeout());
			if (response.statusCode() == 429 || response.statusCode() >= 500) {
				throw unavailable();
			}
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("Cloudflare embedding request was rejected: " + response.statusCode());
			}
			return parseAndNormalize(response.body());
		} catch (SemanticSearchUnavailableException exception) {
			throw exception;
		} catch (IllegalStateException exception) {
			throw exception;
		} catch (Exception exception) {
			throw unavailable();
		}
	}

	private URI endpoint() {
		return URI.create("https://api.cloudflare.com/client/v4/accounts/" + properties.accountId()
			+ "/ai/run/" + CloudflareEmbeddingProperties.MODEL);
	}

	private double[] parseAndNormalize(String responseBody) throws Exception {
		JsonNode vector = objectMapper.readTree(responseBody).path("result").path("data");
		if (!vector.isArray() || vector.size() != 1 || !vector.get(0).isArray()
			|| vector.get(0).size() != CloudflareEmbeddingProperties.DIMENSION) {
			throw unavailable();
		}
		double[] values = new double[CloudflareEmbeddingProperties.DIMENSION];
		double squaredNorm = 0;
		for (int index = 0; index < values.length; index++) {
			JsonNode valueNode = vector.get(0).get(index);
			if (!valueNode.isNumber()) {
				throw unavailable();
			}
			double value = valueNode.asDouble();
			if (!Double.isFinite(value)) {
				throw unavailable();
			}
			values[index] = value;
			squaredNorm += value * value;
		}
		if (!Double.isFinite(squaredNorm) || squaredNorm == 0) {
			throw unavailable();
		}
		double norm = Math.sqrt(squaredNorm);
		for (int index = 0; index < values.length; index++) {
			values[index] /= norm;
		}
		return values;
	}

	private SemanticSearchUnavailableException unavailable() {
		return new SemanticSearchUnavailableException();
	}
}
