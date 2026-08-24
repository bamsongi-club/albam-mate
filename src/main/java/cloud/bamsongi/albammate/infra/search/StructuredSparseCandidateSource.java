package cloud.bamsongi.albammate.infra.search;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;

/**
 * mechanism/category/theme/name/alias/description 계열의 기존 catalog 테이블을 직접 조회해
 * 구조화된 sparse 후보를 만든다. Dense와 달리 외부 embedding provider 없이, 기존 검색 인덱스 계약으로 동작한다.
 *
 * candidate 개수 상한과 field 가중치는 실험값이며, 근거는
 * docs/measurements/search-04e-hybrid-rrf-regression.md를 따른다.
 */
final class StructuredSparseCandidateSource implements SparseCandidateSource.DeadlineAware {

	private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{Nd}]+");
	private static final int MIN_TOKEN_LENGTH = 2;
	private static final int CANDIDATE_LIMIT = 200;
	private static final double NAME_FIELD_WEIGHT = 3.0;
	private static final double STRUCTURED_FIELD_WEIGHT = 2.0;
	private static final double DESCRIPTION_FIELD_WEIGHT = 1.0;
	private static final ScheduledExecutorService QUERY_CANCELLER = Executors
		.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "semantic-search-sparse-query-canceller");
			thread.setDaemon(true);
			return thread;
		});

	private final JdbcTemplate jdbcTemplate;

	StructuredSparseCandidateSource(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<DenseCandidateSource.Candidate> findCandidates(String rawQuery) {
		return findCandidates(rawQuery, Duration.ofSeconds(6));
	}

	@Override
	public List<DenseCandidateSource.Candidate> findCandidates(String rawQuery, Duration remainingTimeout) {
		List<String> tokens = tokenize(rawQuery);
		if (tokens.isEmpty() || remainingTimeout.isZero() || remainingTimeout.isNegative()) {
			throw new SemanticSearchUnavailableException();
		}
		long deadlineNanos = System.nanoTime() + remainingTimeout.toNanos();
		try {
			Object[] arguments = queryArguments(tokens);
			AtomicReference<ScheduledFuture<?>> cancellation = new AtomicReference<>();
			List<DenseCandidateSource.Candidate> candidates;
			try {
				candidates = jdbcTemplate.query(sql(tokens.size()), statement -> {
					long remainingNanos = deadlineNanos - System.nanoTime();
					if (remainingNanos <= 0) {
						throw new SemanticSearchUnavailableException();
					}
					statement.setQueryTimeout(queryTimeoutSeconds(Duration.ofNanos(remainingNanos)));
					for (int index = 0; index < arguments.length; index++) {
						statement.setObject(index + 1, arguments[index]);
					}
					long executionRemainingNanos = deadlineNanos - System.nanoTime();
					if (executionRemainingNanos <= 0) {
						throw new SemanticSearchUnavailableException();
					}
					cancellation.set(QUERY_CANCELLER.schedule(() -> cancel(statement), executionRemainingNanos,
						TimeUnit.NANOSECONDS));
				},
					(resultSet, rowNum) -> new DenseCandidateSource.Candidate(resultSet.getLong("game_id"),
						resultSet.getDouble("score")));
			} finally {
				ScheduledFuture<?> scheduledCancellation = cancellation.get();
				if (scheduledCancellation != null) {
					scheduledCancellation.cancel(false);
				}
			}
			if (candidates.isEmpty()) {
				throw new SemanticSearchUnavailableException();
			}
			return candidates;
		} catch (SemanticSearchUnavailableException exception) {
			throw exception;
		} catch (DataAccessException exception) {
			throw new SemanticSearchUnavailableException();
		}
	}

	private void cancel(Statement statement) {
		try {
			statement.cancel();
		} catch (SQLException ignored) {
			// JDBC query timeout이 이미 취소한 경우다.
		}
	}

	private int queryTimeoutSeconds(Duration remainingTimeout) {
		long seconds = remainingTimeout.toSeconds();
		if (remainingTimeout.minusSeconds(seconds).isPositive()) {
			seconds++;
		}
		return (int)Math.min(Math.max(seconds, 1), Integer.MAX_VALUE);
	}

	private Object[] queryArguments(List<String> tokens) {
		Object[] arguments = new Object[tokens.size() + 1];
		for (int index = 0; index < tokens.size(); index++) {
			arguments[index] = tokens.get(index);
		}
		arguments[tokens.size()] = CANDIDATE_LIMIT;
		return arguments;
	}

	private String sql(int tokenCount) {
		String tokenValues = String.join(", ", java.util.Collections.nCopies(tokenCount, "(?)"));
		return """
			with tokens(token) as (
				values %s
			),
			short_tokens as (
				select token from tokens where char_length(token) = 2
			),
			long_tokens as (
				select token from tokens where char_length(token) >= 3
			),
			name_matches as (
				select matched.game_id, count(distinct matched.token) * %s as weight
				from (
					select g.id as game_id, t.token
					from games g
					join short_tokens t on game_search_bigrams(g.name) @> array[t.token]::text[]
					union
					select g.id as game_id, t.token
					from games g
					join long_tokens t on lower(g.name) like '%%' || t.token || '%%'
					union
					select g.id as game_id, t.token
					from games g
					join short_tokens t on game_search_bigrams(g.english_name) @> array[t.token]::text[]
					union
					select g.id as game_id, t.token
					from games g
					join long_tokens t on lower(g.english_name) like '%%' || t.token || '%%'
					union
					select g.id as game_id, t.token
					from games g
					join short_tokens t on game_search_bigrams(g.alias) @> array[t.token]::text[]
					union
					select g.id as game_id, t.token
					from games g
					join long_tokens t on lower(g.alias) like '%%' || t.token || '%%'
				) matched
				group by matched.game_id
			),
			description_matches as (
				select matched.game_id, count(distinct matched.token) * %s as weight
				from (
					select g.id as game_id, t.token
					from games g
					join short_tokens t on game_search_bigrams(g.description) @> array[t.token]::text[]
					union
					select g.id as game_id, t.token
					from games g
					join long_tokens t on lower(g.description) like '%%' || t.token || '%%'
				) matched
				group by matched.game_id
			),
			mechanism_matches as (
				select r.game_id as game_id, count(distinct t.token) * %s as weight
				from game_mechanism_relations r
				join game_mechanisms m on m.id = r.mechanism_id and m.is_public = true
				join tokens t on (lower(m.name_ko) like '%%' || t.token || '%%'
					or lower(m.name_en) like '%%' || t.token || '%%')
				group by r.game_id
			),
			category_matches as (
				select r.game_id as game_id, count(distinct t.token) * %s as weight
				from game_category_relations r
				join game_categories c on c.id = r.category_id
				join tokens t on (lower(c.name_ko) like '%%' || t.token || '%%'
					or lower(c.name_en) like '%%' || t.token || '%%')
				group by r.game_id
			),
			theme_matches as (
				select r.game_id as game_id, count(distinct t.token) * %s as weight
				from game_theme_relations r
				join game_themes th on th.id = r.theme_id
				join tokens t on (lower(th.name_ko) like '%%' || t.token || '%%'
					or lower(th.name_en) like '%%' || t.token || '%%')
				group by r.game_id
			),
			combined as (
				select game_id, weight from name_matches
				union all select game_id, weight from description_matches
				union all select game_id, weight from mechanism_matches
				union all select game_id, weight from category_matches
				union all select game_id, weight from theme_matches
			)
			select game_id, sum(weight) as score
			from combined
			group by game_id
			order by score desc, game_id asc
			limit ?
			""".formatted(tokenValues, NAME_FIELD_WEIGHT, DESCRIPTION_FIELD_WEIGHT, STRUCTURED_FIELD_WEIGHT,
			STRUCTURED_FIELD_WEIGHT, STRUCTURED_FIELD_WEIGHT);
	}

	private List<String> tokenize(String rawQuery) {
		if (rawQuery == null) {
			return List.of();
		}
		Set<String> tokens = new LinkedHashSet<>();
		for (String token : TOKEN_SPLIT.split(rawQuery.toLowerCase(Locale.ROOT))) {
			if (token.length() >= MIN_TOKEN_LENGTH) {
				tokens.add(escapeLikePattern(token));
			}
		}
		return tokens.stream().collect(Collectors.toUnmodifiableList());
	}

	private String escapeLikePattern(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
