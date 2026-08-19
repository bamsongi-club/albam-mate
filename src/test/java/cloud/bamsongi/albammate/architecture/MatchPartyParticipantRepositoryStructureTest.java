package cloud.bamsongi.albammate.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;

class MatchPartyParticipantRepositoryStructureTest {

	private static final Set<String> MATCH_ENTITY_SOURCES = Set.of("MatchParty", "MatchPartyParticipant");
	private static final Pattern FROM_CLAUSE_PATTERN = Pattern.compile(
		"(?is)\\bfrom\\s+(.+?)(?=\\b(?:where|group\\s+by|order\\s+by|having)\\b|$)");
	private static final Pattern ENTITY_SOURCE_PATTERN = Pattern.compile(
		"(?i)(?:^|\\bjoin\\s+|,)\\s*(?:fetch\\s+)?([A-Za-z_][A-Za-z0-9_$.]*)");

	@Test
	void MATCH_참가자_조회_JPQL은_MATCH_Entity만_FROM_JOIN_소스로_사용한다() {
		for (Method method : MatchPartyParticipantRepository.class.getDeclaredMethods()) {
			Query query = method.getAnnotation(Query.class);
			if (query == null) {
				continue;
			}
			assertUsesOnlyMatchEntitySources(method, query.value());
		}
	}

	private void assertUsesOnlyMatchEntitySources(Method method, String query) {
		Matcher fromClauseMatcher = FROM_CLAUSE_PATTERN.matcher(query);
		while (fromClauseMatcher.find()) {
			assertFromClauseUsesOnlyMatchEntitySources(method, fromClauseMatcher.group(1));
		}
	}

	private void assertFromClauseUsesOnlyMatchEntitySources(Method method, String fromClause) {
		Matcher entitySourceMatcher = ENTITY_SOURCE_PATTERN.matcher(fromClause);
		while (entitySourceMatcher.find()) {
			String entitySource = simpleName(entitySourceMatcher.group(1));
			if (!Character.isUpperCase(entitySource.charAt(0))) {
				continue;
			}
			assertTrue(
				MATCH_ENTITY_SOURCES.contains(entitySource),
				() -> method.getName() + "의 @Query가 MATCH 외 Entity를 직접 조인합니다: " + entitySource);
		}
	}

	private String simpleName(String entitySource) {
		int lastDotIndex = entitySource.lastIndexOf('.');
		return lastDotIndex == -1 ? entitySource : entitySource.substring(lastDotIndex + 1);
	}
}
