package cloud.bamsongi.albammate.infra.search;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;

final class PgVectorSemanticIndexRepository {

	private static final int INITIAL_ROW_COUNT = 1000;
	private static final long CUTOVER_LOCK_KEY = 0x53454152434834L;

	private final JdbcTemplate jdbcTemplate;
	private final DataSource dataSource;

	PgVectorSemanticIndexRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
		this.dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource());
	}

	UUID createBuilding(ApprovedSearchRelease release, EmbeddingProvenance provenance) {
		UUID versionId = UUID.randomUUID();
		jdbcTemplate.update("""
			insert into semantic_search_index_versions
			(id, release_id, field_version, manifest_sha256, search_text_checksum, provider, model, embedding_mode,
			dimension, l2_normalized, status, active)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'BUILDING', false)
			""", versionId, release.releaseId(), release.fieldVersion(), release.manifestSha256(),
			release.searchTextChecksum(), provenance.provider(), provenance.model(), provenance.mode(),
			provenance.dimension(), provenance.l2Normalized());
		return versionId;
	}

	void insertRows(UUID versionId, List<ApprovedEmbeddingArtifact.Row> rows) {
		jdbcTemplate.batchUpdate(
			"insert into semantic_game_embeddings (index_version_id, game_id, embedding) values (?, ?, cast(? as vector))",
			rows, rows.size(), (statement, row) -> {
				statement.setObject(1, versionId);
				statement.setLong(2, row.gameId());
				statement.setString(3, vectorLiteral(row.embedding()));
			});
	}

	void activate(UUID versionId) {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				lockCutover(connection);
				if (update(connection,
					"update semantic_search_index_versions set status = 'READY' where id = ? and status = 'BUILDING'",
					versionId) != 1) {
					throw new IllegalStateException("semantic index activation target must be BUILDING");
				}
				update(connection, "update semantic_search_index_versions set active = false where active = true");
				if (update(connection,
					"update semantic_search_index_versions set active = true where id = ? and status = 'READY' and active = false",
					versionId) != 1) {
					throw new IllegalStateException("semantic index activation target was not updated");
				}
				connection.commit();
			} catch (RuntimeException | SQLException exception) {
				connection.rollback();
				throw exception;
			} finally {
				connection.setAutoCommit(autoCommit);
			}
		} catch (SQLException exception) {
			throw new DataAccessResourceFailureException("semantic index activation failed", exception);
		}
	}

	void fail(UUID versionId) {
		jdbcTemplate.update("update semantic_search_index_versions set status = 'FAILED', active = false where id = ?",
			versionId);
	}

	List<DenseCandidateSource.Candidate> findActiveCandidates(double[] queryVector, ApprovedSearchRelease release,
		EmbeddingProvenance provenance, int limit) {
		return jdbcTemplate.query("""
			with matching_active_version as (
				select version.id
				from semantic_search_index_versions version
				where version.active = true and version.status = 'READY'
				  and version.release_id = ? and version.field_version = ?
				  and version.manifest_sha256 = ? and version.search_text_checksum = ?
				  and version.provider = ? and version.model = ? and version.embedding_mode = ?
				  and version.dimension = ? and version.l2_normalized = ?
				  and (select count(*) from semantic_game_embeddings
					where index_version_id = version.id) = ?
			)
			select embedding.game_id, 1 - (embedding.embedding <=> cast(? as vector)) as relevance
			from semantic_game_embeddings embedding
			join matching_active_version version on version.id = embedding.index_version_id
			order by embedding.embedding <=> cast(? as vector), embedding.game_id
			limit ?
			""", (resultSet, rowNum) -> new DenseCandidateSource.Candidate(resultSet.getLong("game_id"),
			resultSet.getDouble("relevance")), release.releaseId(), release.fieldVersion(), release.manifestSha256(),
			release.searchTextChecksum(), provenance.provider(), provenance.model(), provenance.mode(),
			provenance.dimension(),
			provenance.l2Normalized(), INITIAL_ROW_COUNT, vectorLiteral(queryVector), vectorLiteral(queryVector),
			limit);
	}

	void rollbackTo(UUID versionId) {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				lockCutover(connection);
				if (!isReady(connection, versionId)) {
					throw new IllegalArgumentException("rollback target must be READY");
				}
				update(connection, "update semantic_search_index_versions set active = false where active = true");
				if (update(connection,
					"update semantic_search_index_versions set active = true where id = ? and status = 'READY' and active = false",
					versionId) != 1) {
					throw new IllegalStateException("semantic index rollback target was not updated");
				}
				connection.commit();
			} catch (RuntimeException | SQLException exception) {
				connection.rollback();
				throw exception;
			} finally {
				connection.setAutoCommit(autoCommit);
			}
		} catch (SQLException exception) {
			throw new DataAccessResourceFailureException("semantic index rollback failed", exception);
		}
	}

	boolean hasActiveMatchingIndex(ApprovedSearchRelease release, EmbeddingProvenance provenance) {
		Boolean active = jdbcTemplate.queryForObject("""
			select exists (
				select 1 from semantic_search_index_versions
				where active = true and status = 'READY'
				  and release_id = ? and field_version = ? and manifest_sha256 = ? and search_text_checksum = ?
				  and provider = ? and model = ? and embedding_mode = ? and dimension = ? and l2_normalized = ?
				  and (select count(*) from semantic_game_embeddings
					where index_version_id = semantic_search_index_versions.id) = ?
			)
			""", Boolean.class, release.releaseId(), release.fieldVersion(), release.manifestSha256(),
			release.searchTextChecksum(), provenance.provider(), provenance.model(), provenance.mode(),
			provenance.dimension(), provenance.l2Normalized(), INITIAL_ROW_COUNT);
		return Boolean.TRUE.equals(active);
	}

	private String vectorLiteral(double[] vector) {
		return Arrays.stream(vector).mapToObj(value -> String.format(Locale.ROOT, "%.17g", value))
			.collect(java.util.stream.Collectors.joining(",", "[", "]"));
	}

	/**
	 * active pointer 전환(activate/rollback)을 하나씩만 진행하도록 직렬화한다. partial unique index
	 * (active=true 1건 제한)에 동시에 두 트랜잭션이 부딪혀 정상 release가 constraint violation으로
	 * FAILED 처리되는 경쟁을 막는다. 트랜잭션 종료 시 자동 해제된다.
	 */
	private void lockCutover(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_xact_lock(?)")) {
			statement.setLong(1, CUTOVER_LOCK_KEY);
			statement.execute();
		}
	}

	private int update(Connection connection, String sql, Object... values) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) {
				statement.setObject(index + 1, values[index]);
			}
			return statement.executeUpdate();
		}
	}

	private boolean isReady(Connection connection, UUID versionId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"select 1 from semantic_search_index_versions where id = ? and status = 'READY'")) {
			statement.setObject(1, versionId);
			try (java.sql.ResultSet resultSet = statement.executeQuery()) {
				return resultSet.next();
			}
		}
	}
}
