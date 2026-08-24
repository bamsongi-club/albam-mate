package cloud.bamsongi.albammate.infra.search;

import java.net.http.HttpClient;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(CloudflareEmbeddingProperties.class)
class SemanticSearchConfiguration {

	@Bean
	@ConditionalOnMissingBean(DenseCandidateSource.class)
	DenseCandidateSource unavailableSemanticGameCandidateSource() {
		return new UnavailableSemanticGameCandidateSource();
	}

	@Bean
	@ConditionalOnMissingBean(SparseCandidateSource.class)
	SparseCandidateSource structuredSparseCandidateSource(DataSource dataSource) {
		return new StructuredSparseCandidateSource(new JdbcTemplate(dataSource));
	}

	@Bean
	@Primary
	@ConditionalOnProperty(prefix = "app.search.cloudflare", name = "enabled", havingValue = "true")
	DenseCandidateSource cloudflareDenseCandidateSource(CloudflareEmbeddingProperties properties, DataSource dataSource,
		ObjectMapper objectMapper) {
		if (!properties.isConfigured()) {
			return new UnavailableSemanticGameCandidateSource();
		}
		CloudflareEmbeddingClient client = new CloudflareEmbeddingClient(properties,
			new JdkCloudflareEmbeddingTransport(HttpClient.newBuilder().connectTimeout(properties.timeout()).build()),
			objectMapper);
		return new PgVectorDenseCandidateSource(client,
			new PgVectorSemanticIndexRepository(new JdbcTemplate(dataSource)), properties.expectedRelease());
	}
}
