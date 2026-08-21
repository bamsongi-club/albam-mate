package cloud.bamsongi.albammate.infra.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgVectorSemanticIndexPostgresTest {

	private static final String SHA = "a".repeat(64);

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
		cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages.postgres18())
		.withDatabaseName("semantic_index_test");

	private JdbcTemplate jdbcTemplate;
	private PgVectorSemanticIndexRepository repository;
	private SemanticIndexProvisioner provisioner;
	private ApprovedSearchRelease release;

	@BeforeAll
	void setUp() {
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration", "classpath:db/vendor-migration/postgresql")
			.load()
			.migrate();
		jdbcTemplate = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
			POSTGRES.getPassword()));
		repository = new PgVectorSemanticIndexRepository(jdbcTemplate);
		release = new ApprovedSearchRelease("release-1", "field-v1", SHA, SHA);
		provisioner = new SemanticIndexProvisioner(repository, release, EmbeddingProvenance.cloudflareBgeM3());
	}

	@BeforeEach
	void clearSemanticIndex() {
		jdbcTemplate.execute("truncate table semantic_game_embeddings, semantic_search_index_versions");
	}

	@Test
	void T3_READY_Cloudflare_provenance와_일치하는_pgvector_exact_cosine_후보만_반환한다() {
		AtomicBoolean providerCalled = new AtomicBoolean();
		assertThrows(SemanticSearchUnavailableException.class,
			() -> denseCandidateSource(() -> providerCalled.set(true)).findCandidates("READY 없음"));
		assertFalse(providerCalled.get());
		UUID ready = provisioner.activate(artifact(release, EmbeddingProvenance.cloudflareBgeM3(), 1));
		ApprovedEmbeddingArtifact mismatched = artifact(release,
			new EmbeddingProvenance("other", "other", "text", 1024, true), 2001);
		assertThrows(IllegalArgumentException.class, () -> provisioner.activate(mismatched));

		List<DenseCandidateSource.Candidate> candidates = repository.findActiveCandidates(vector(1, 0),
			release, EmbeddingProvenance.cloudflareBgeM3(), 10);

		assertEquals(ready.toString(), jdbcTemplate.queryForObject(
			"select id::text from semantic_search_index_versions where active", String.class));
		assertEquals(1L, candidates.getFirst().gameId());
		assertEquals(1.0, candidates.getFirst().relevance(), 0.000001);
		jdbcTemplate.update("delete from semantic_game_embeddings where index_version_id = ? and game_id = ?", ready,
			1L);
		assertThrows(SemanticSearchUnavailableException.class,
			() -> denseCandidateSource().findCandidates("부분 backfill"));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from semantic_search_index_versions where status = 'FAILED'", Integer.class));
		assertEquals(0, repository.findActiveCandidates(vector(1, 0),
			release, new EmbeddingProvenance("other", "other", "text", 1024, true), 10).size());
	}

	@Test
	void T3_다른_release와_preflight_후_부분_backfill은_후보를_반환하지_않는다() {
		ApprovedSearchRelease staleRelease = new ApprovedSearchRelease("stale-release", "field-v1", SHA, SHA);
		UUID stale = repository.createBuilding(staleRelease, EmbeddingProvenance.cloudflareBgeM3());
		repository.insertRows(stale, artifact(staleRelease, EmbeddingProvenance.cloudflareBgeM3(), 1).rows());
		repository.activate(stale);

		assertThrows(SemanticSearchUnavailableException.class,
			() -> denseCandidateSource().findCandidates("이전 release"));
		UUID scoreMode = repository.createBuilding(release,
			new EmbeddingProvenance("cloudflare-workers-ai", "@cf/baai/bge-m3", "score", 1024, true));
		repository.insertRows(scoreMode, artifact(release,
			new EmbeddingProvenance("cloudflare-workers-ai", "@cf/baai/bge-m3", "score", 1024, true), 1).rows());
		repository.activate(scoreMode);

		assertThrows(SemanticSearchUnavailableException.class,
			() -> denseCandidateSource().findCandidates("다른 embedding mode"));

		UUID ready = provisioner.activate(artifact(release, EmbeddingProvenance.cloudflareBgeM3(), 1001));
		PgVectorDenseCandidateSource source = denseCandidateSource(() -> jdbcTemplate.update(
			"delete from semantic_game_embeddings where index_version_id = ? and game_id = ?", ready, 1001L));

		assertThrows(SemanticSearchUnavailableException.class, () -> source.findCandidates("경합 부분 backfill"));
	}

	@Test
	void T4_1000개_release_artifact만_READY로_cutover하고_실패시_이전_READY를_보존하고_rollback한다() {
		UUID first = provisioner.activate(artifact(release, EmbeddingProvenance.cloudflareBgeM3(), 1));
		ApprovedSearchRelease stale = new ApprovedSearchRelease("stale-release", "field-v1", SHA, SHA);

		assertThrows(IllegalArgumentException.class,
			() -> provisioner.activate(artifact(stale, EmbeddingProvenance.cloudflareBgeM3(), 1001)));
		assertThrows(IllegalArgumentException.class, () -> provisioner.activate(artifact(release,
			new EmbeddingProvenance("cloudflare-workers-ai", "@cf/baai/bge-m3", "score", 1024, true), 1001)));

		assertEquals(first.toString(), jdbcTemplate.queryForObject(
			"select id::text from semantic_search_index_versions where active", String.class));
		assertEquals(1000, jdbcTemplate.queryForObject(
			"select count(*) from semantic_game_embeddings where index_version_id = ?", Integer.class, first));
		assertThrows(IllegalArgumentException.class,
			() -> provisioner.activate(artifact(release, EmbeddingProvenance.cloudflareBgeM3(), 1001, 2, 0)));
		assertEquals(first.toString(), jdbcTemplate.queryForObject(
			"select id::text from semantic_search_index_versions where active", String.class));
		assertThrows(IllegalStateException.class, () -> repository.activate(UUID.randomUUID()));
		assertEquals(first.toString(), jdbcTemplate.queryForObject(
			"select id::text from semantic_search_index_versions where active", String.class));
		UUID second = provisioner.activate(artifact(release, EmbeddingProvenance.cloudflareBgeM3(), 2001));
		jdbcTemplate.execute("""
			create function reject_semantic_rollback_target() returns trigger language plpgsql as $$
			begin
				if new.id = '%s'::uuid and new.active then
					raise exception 'reject rollback target';
				end if;
				return new;
			end;
			$$;
			create trigger reject_semantic_rollback_target before update on semantic_search_index_versions
			for each row execute function reject_semantic_rollback_target();
			""".formatted(first));
		try {
			assertThrows(RuntimeException.class, () -> provisioner.rollbackTo(first));
			assertEquals(second.toString(), jdbcTemplate.queryForObject(
				"select id::text from semantic_search_index_versions where active", String.class));
		} finally {
			jdbcTemplate.execute("drop trigger reject_semantic_rollback_target on semantic_search_index_versions");
			jdbcTemplate.execute("drop function reject_semantic_rollback_target()");
		}
		provisioner.rollbackTo(first);

		assertEquals(first.toString(), jdbcTemplate.queryForObject(
			"select id::text from semantic_search_index_versions where active", String.class));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from semantic_search_index_versions where id = ? and active", Integer.class, second));
	}

	private ApprovedEmbeddingArtifact artifact(ApprovedSearchRelease artifactRelease, EmbeddingProvenance provenance,
		long firstGameId) {
		return artifact(artifactRelease, provenance, firstGameId, 1, 0);
	}

	private ApprovedEmbeddingArtifact artifact(ApprovedSearchRelease artifactRelease, EmbeddingProvenance provenance,
		long firstGameId, double firstValue, double secondValue) {
		List<ApprovedEmbeddingArtifact.Row> rows = new ArrayList<>();
		for (int index = 0; index < 1000; index++) {
			rows.add(new ApprovedEmbeddingArtifact.Row(firstGameId + index,
				index == 0 ? vector(firstValue, secondValue) : vector(0, 1)));
		}
		return new ApprovedEmbeddingArtifact(artifactRelease, provenance, rows);
	}

	private PgVectorDenseCandidateSource denseCandidateSource() {
		return denseCandidateSource(() -> {});
	}

	private PgVectorDenseCandidateSource denseCandidateSource(Runnable beforeResponse) {
		CloudflareEmbeddingClient client = new CloudflareEmbeddingClient(
			new CloudflareEmbeddingProperties(true, "account", "token", java.time.Duration.ofSeconds(5),
				release.releaseId(), release.fieldVersion(), release.manifestSha256(), release.searchTextChecksum()),
			(uri, token, body, timeout) -> {
				beforeResponse.run();
				String response = new ObjectMapper().writeValueAsString(
					java.util.Map.of("result", java.util.Map.of("data", List.of(vector(1, 0)))));
				return new CloudflareEmbeddingTransport.EmbeddingHttpResponse(200, response);
			}, new ObjectMapper());
		return new PgVectorDenseCandidateSource(client, repository, release);
	}

	private double[] vector(double first, double second) {
		double[] vector = new double[CloudflareEmbeddingProperties.DIMENSION];
		vector[0] = first;
		vector[1] = second;
		return vector;
	}
}
