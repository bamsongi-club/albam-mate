package cloud.bamsongi.albammate.infra.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticSearchUnavailableException;
import cloud.bamsongi.albammate.game.contract.SparseCandidateSource;

/**
 * mechanism/category/theme/name/alias/description 계열의 기존 catalog 테이블을 직접 조회해
 * 구조화된 sparse 후보를 만든다. Dense와 달리 외부 embedding provider 없이, 새 컬럼·인덱스 없이 동작한다.
 *
 * candidate 개수 상한과 field 가중치는 실험값이며, 근거는
 * docs/measurements/search-04e-hybrid-rrf-regression.md를 따른다.
 */
final class StructuredSparseCandidateSource implements SparseCandidateSource {

	private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{Nd}]+");
	private static final int MIN_TOKEN_LENGTH = 2;
	private static final int CANDIDATE_LIMIT = 200;
	private static final double NAME_FIELD_WEIGHT = 3.0;
	private static final double STRUCTURED_FIELD_WEIGHT = 2.0;
	private static final double DESCRIPTION_FIELD_WEIGHT = 1.0;

	private final JdbcTemplate jdbcTemplate;

	StructuredSparseCandidateSource(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<DenseCandidateSource.Candidate> findCandidates(String rawQuery) {
		List<String> tokens = tokenize(rawQuery);
		if (tokens.isEmpty()) {
			throw new SemanticSearchUnavailableException();
		}
		try {
			List<DenseCandidateSource.Candidate> candidates = jdbcTemplate.query(sql(tokens.size()),
				(resultSet, rowNum) -> new DenseCandidateSource.Candidate(resultSet.getLong("game_id"),
					resultSet.getDouble("score")),
				queryArguments(tokens));
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
			name_matches as (
				select g.id as game_id, count(distinct t.token) * %s as weight
				from games g
				join tokens t on (lower(g.name) like '%%' || t.token || '%%'
					or lower(g.english_name) like '%%' || t.token || '%%'
					or lower(coalesce(g.alias, '')) like '%%' || t.token || '%%')
				group by g.id
			),
			description_matches as (
				select g.id as game_id, count(distinct t.token) * %s as weight
				from games g
				join tokens t on lower(g.description) like '%%' || t.token || '%%'
				group by g.id
			),
			mechanism_matches as (
				select r.game_id as game_id, count(distinct t.token) * %s as weight
				from game_mechanism_relations r
				join game_mechanisms m on m.id = r.mechanism_id
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
