package cloud.bamsongi.albammate.infra.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import tools.jackson.databind.ObjectMapper;

class CloudflareEmbeddingClientTest {

	private static final String SECRET = "cf-test-token";
	private static final String QUERY = "가볍게 웃으면서 할 게임";
	private static final String SHA = "a".repeat(64);

	@Test
	void T1_Cloudflare_1024차원_응답을_L2_정규화하고_입력과_secret을_노출하지_않는다() {
		CloudflareEmbeddingClient client = client((uri, token, body, timeout) -> {
			assertTrue(uri.toString().endsWith("/ai/run/@cf/baai/bge-m3"));
			assertEquals(SECRET, token);
			assertTrue(body.contains(QUERY));
			assertEquals(Duration.ofSeconds(5), timeout);
			return new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200, response(vector(3, 4)));
		});

		double[] embedding = client.embed(QUERY);

		assertEquals(CloudflareEmbeddingProperties.DIMENSION, embedding.length);
		assertEquals(0.6, embedding[0], 0.000001);
		assertEquals(0.8, embedding[1], 0.000001);
		assertEquals(1.0, norm(embedding), 0.000001);
	}

	@Test
	void T1_외부_timeout_설정과_무관하게_Cloudflare_요청은_5초로_고정한다() {
		for (Duration configuredTimeout : List.of(Duration.ZERO, Duration.ofSeconds(30))) {
			CloudflareEmbeddingProperties properties = new CloudflareEmbeddingProperties(true, "account", SECRET,
				configuredTimeout, "release-1", "field-v1", SHA, SHA);
			assertEquals(Duration.ofSeconds(5), properties.timeout());
			CloudflareEmbeddingClient client = new CloudflareEmbeddingClient(properties,
				(uri, token, body, timeout) -> {
					assertEquals(Duration.ofSeconds(5), timeout);
					return new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200, response(vector(3, 4)));
				}, new ObjectMapper());

			client.embed(QUERY);
		}
	}

	@Test
	void T2_전송_응답_형식_벡터_오류는_비밀값_없는_UNAVAILABLE로_수렴한다() {
		for (CloudflareEmbeddingTransport transport : List.<CloudflareEmbeddingTransport>of(
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(429, "{}"),
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(503, "{}"),
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200, "{malformed"),
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200,
				response(new double[1023])),
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200,
				response(new double[1024])),
			(uri, token, body, timeout) -> {
				throw new java.net.http.HttpTimeoutException("timeout");
			})) {
			SemanticSearchUnavailableException exception = assertThrows(SemanticSearchUnavailableException.class,
				() -> client(transport).embed(QUERY));
			assertFalse(String.valueOf(exception.getMessage()).contains(SECRET));
			assertFalse(String.valueOf(exception.getMessage()).contains(QUERY));
		}
		assertThrows(SemanticSearchUnavailableException.class, () -> client(
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200,
				responseWithStringValues()))
			.embed(QUERY));
	}

	@Test
	void T2_명시적으로_거절된_4xx는_lexical_fallback으로_숨기지_않는다() {
		assertThrows(IllegalStateException.class, () -> client(
			(uri, token, body, timeout) -> new CloudflareEmbeddingTransport.EmbeddingHttpResponse(401, "{}"))
			.embed(QUERY));
	}

	@Test
	void T5_빈_secret_또는_승인_release_설정이_불완전하면_Cloudflare_source를_구성하지_않는다() {
		assertFalse(properties(false, "account", SECRET).isConfigured());
		assertFalse(properties(true, "", SECRET).isConfigured());
		assertFalse(properties(true, "account", "").isConfigured());
		new ApplicationContextRunner()
			.withUserConfiguration(SemanticSearchConfiguration.class)
			.withBean(javax.sql.DataSource.class, () -> new DriverManagerDataSource("jdbc:h2:mem:unused"))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withPropertyValues("app.search.cloudflare.enabled=true", "app.search.cloudflare.account-id=",
				"app.search.cloudflare.api-token=")
			.run(context -> assertTrue(
				context.getBean(DenseCandidateSource.class) instanceof UnavailableSemanticGameCandidateSource));
		new ApplicationContextRunner()
			.withUserConfiguration(SemanticSearchConfiguration.class)
			.withBean(javax.sql.DataSource.class, () -> new DriverManagerDataSource("jdbc:h2:mem:unused-release"))
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withPropertyValues("app.search.cloudflare.enabled=true", "app.search.cloudflare.account-id=account",
				"app.search.cloudflare.api-token=" + SECRET, "app.search.cloudflare.release-id=release-1",
				"app.search.cloudflare.field-version=field-v1",
				"app.search.cloudflare.manifest-sha256=" + "a".repeat(64))
			.run(context -> assertTrue(
				context.getBean(DenseCandidateSource.class) instanceof UnavailableSemanticGameCandidateSource));
	}

	@Test
	void T5_0초와_30초_timeout_설정에서도_5초_Cloudflare_source를_시작한다() {
		for (String configuredTimeout : List.of("0s", "30s")) {
			new ApplicationContextRunner()
				.withUserConfiguration(SemanticSearchConfiguration.class)
				.withBean(javax.sql.DataSource.class,
					() -> new DriverManagerDataSource("jdbc:h2:mem:configured-timeout-" + configuredTimeout))
				.withBean(ObjectMapper.class, ObjectMapper::new)
				.withPropertyValues("app.search.cloudflare.enabled=true", "app.search.cloudflare.account-id=account",
					"app.search.cloudflare.api-token=" + SECRET, "app.search.cloudflare.timeout=" + configuredTimeout,
					"app.search.cloudflare.release-id=release-1", "app.search.cloudflare.field-version=field-v1",
					"app.search.cloudflare.manifest-sha256=" + SHA, "app.search.cloudflare.search-text-checksum=" + SHA)
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertTrue(context.getBean(DenseCandidateSource.class) instanceof PgVectorDenseCandidateSource);
				});
		}
	}

	private CloudflareEmbeddingClient client(CloudflareEmbeddingTransport transport) {
		return new CloudflareEmbeddingClient(
			properties(true, "account", SECRET),
			transport, new ObjectMapper());
	}

	private CloudflareEmbeddingProperties properties(boolean enabled, String accountId, String apiToken) {
		return new CloudflareEmbeddingProperties(enabled, accountId, apiToken, Duration.ofSeconds(5), "release-1",
			"field-v1", SHA, SHA);
	}

	private String response(double[] vector) {
		try {
			return new ObjectMapper()
				.writeValueAsString(java.util.Map.of("result", java.util.Map.of("data", List.of(vector))));
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private String responseWithStringValues() {
		try {
			List<String> values = new java.util.ArrayList<>(CloudflareEmbeddingProperties.DIMENSION);
			values.add("1");
			for (int index = 1; index < CloudflareEmbeddingProperties.DIMENSION; index++) {
				values.add("0");
			}
			return new ObjectMapper()
				.writeValueAsString(java.util.Map.of("result", java.util.Map.of("data", List.of(values))));
		} catch (Exception exception) {
			throw new AssertionError(exception);
		}
	}

	private double[] vector(double first, double second) {
		double[] vector = new double[CloudflareEmbeddingProperties.DIMENSION];
		vector[0] = first;
		vector[1] = second;
		return vector;
	}

	private double norm(double[] vector) {
		return Math.sqrt(Arrays.stream(vector).map(value -> value * value).sum());
	}
}
