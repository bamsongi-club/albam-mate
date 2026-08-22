package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.game.contract.DenseCandidateSource;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchQuery;
import cloud.bamsongi.albammate.game.contract.SemanticGameSearchResult;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.entity.GameMechanism;
import cloud.bamsongi.albammate.game.entity.GameMechanismRelation;
import cloud.bamsongi.albammate.game.repository.GameMechanismRelationRepository;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.service.GameListSearchCriteria;
import cloud.bamsongi.albammate.game.service.SemanticGameSearchService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * #983 이슈에 명시된 4개 회귀 질의(Stone Age 표현 변형)로 Hybrid/RRF 결과·순위·hard-filter 준수·
 * latency evidence를 만든다. 실행 결과는 {@code docs/measurements/search-04e-hybrid-rrf-regression.md}에
 * 그대로 옮겨 candidate K·RRF {@code k}·timeout 근거로 쓴다(#983 T6, ADR-0088).
 *
 * dense candidate는 실제 Cloudflare 호출 없이, ADR-0088이 이미 승인한 실측 Dense-only 순위(38/15/12/97)를
 * 재현하는 합성 목록으로 대신한다. sparse candidate는 이 구현의 실제 {@code StructuredSparseCandidateSource}로
 * PostgreSQL에서 그대로 계산한다.
 */
@Testcontainers
@SpringBootTest
class HybridSemanticGameSearchRegressionPostgresTest extends SharedPostgresIntegrationSupport {

	private static final int FILLER_GAME_COUNT = 100;
	private static final int MEASURE_ROUNDS = 5;

	@Autowired
	private SemanticGameSearchService semanticGameSearchService;
	@Autowired
	private GameRepository gameRepository;
	@Autowired
	private GameMechanismRepository gameMechanismRepository;
	@Autowired
	private GameMechanismRelationRepository gameMechanismRelationRepository;
	@MockitoBean
	private DenseCandidateSource candidateSource;

	private List<Game> createdGames = new ArrayList<>();
	private List<GameMechanism> createdMechanisms = new ArrayList<>();
	private List<GameMechanismRelation> createdRelations = new ArrayList<>();

	@AfterEach
	void cleanUpFixture() {
		gameMechanismRelationRepository.deleteAllInBatch(createdRelations);
		gameRepository.deleteAllInBatch(createdGames);
		gameMechanismRepository.deleteAllInBatch(createdMechanisms);
	}

	@Test
	void T6_이슈_명시_4개_회귀_질의의_Hybrid_결과와_latency를_기록한다() throws IOException {
		List<Game> fillerGames = fillerGames();
		GameMechanism workerPlacement = mechanism("REGRESSION_WORKER_PLACEMENT");
		Game stoneAge = stoneAge();
		link(stoneAge, workerPlacement);

		Map<String, Integer> denseOnlyRankByQuery = Map.of(
			"일꾼 놓고 밥 먹이는 게임", 38,
			"일꾼 배치하고 식량으로 부족을 부양하는 게임", 15,
			"place workers and feed your population", 12,
			"worker placement and food management game", 97);

		List<Map<String, Object>> evidence = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : denseOnlyRankByQuery.entrySet()) {
			String rawQuery = entry.getKey();
			int denseOnlyRank = entry.getValue();
			List<DenseCandidateSource.Candidate> denseCandidates = syntheticDenseCandidates(fillerGames, stoneAge,
				denseOnlyRank);
			when(candidateSource.findCandidates(eq(rawQuery))).thenReturn(denseCandidates);

			List<Long> latenciesNanos = new ArrayList<>();
			SemanticGameSearchResult lastResult = null;
			int hybridRank = -1;
			for (int round = 0; round < MEASURE_ROUNDS; round++) {
				long startedAtNanos = System.nanoTime();
				lastResult = semanticGameSearchService
					.search(new SemanticGameSearchQuery(rawQuery, GameListSearchCriteria.from(new GameListRequest()),
						0, denseCandidates.size() + FILLER_GAME_COUNT));
				latenciesNanos.add(System.nanoTime() - startedAtNanos);
				hybridRank = rankOf(lastResult, stoneAge.getId());
			}

			Map<String, Object> row = new LinkedHashMap<>();
			row.put("query", rawQuery);
			row.put("mode", lastResult.mode().name());
			row.put("denseOnlyRank", denseOnlyRank);
			row.put("hybridRank", hybridRank);
			row.put("latencyMillis", percentileSummaryMillis(latenciesNanos));
			evidence.add(row);

			assertTrue(lastResult.content().stream().noneMatch(item -> item.id() > 1_000_000_000L),
				"hard filter를 우회한 존재하지 않는 game id가 없어야 한다");
		}

		Path outputPath = Path.of(System.getProperty("java.io.tmpdir"), "search-04e-t6-evidence.json");
		Files.writeString(outputPath, toJson(evidence));
		System.out.println("SEARCH-04e T6 evidence written to " + outputPath);
		System.out.println(toJson(evidence));
	}

	private int rankOf(SemanticGameSearchResult result, long gameId) {
		for (int index = 0; index < result.content().size(); index++) {
			if (result.content().get(index).id() == gameId) {
				return index + 1;
			}
		}
		return -1;
	}

	private List<DenseCandidateSource.Candidate> syntheticDenseCandidates(
		List<Game> fillerGames, Game stoneAge, int stoneAgeRank) {
		List<DenseCandidateSource.Candidate> candidates = new ArrayList<>();
		double relevance = 1.0;
		int fillerIndex = 0;
		for (int rank = 1; rank <= stoneAgeRank; rank++) {
			if (rank == stoneAgeRank) {
				candidates.add(new DenseCandidateSource.Candidate(stoneAge.getId(), relevance));
			} else {
				candidates.add(new DenseCandidateSource.Candidate(fillerGames.get(fillerIndex).getId(), relevance));
				fillerIndex++;
			}
			relevance -= 0.001;
		}
		return candidates;
	}

	private Map<String, Object> percentileSummaryMillis(List<Long> latenciesNanos) {
		List<Long> sorted = new ArrayList<>(latenciesNanos);
		sorted.sort(java.util.Comparator.naturalOrder());
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("p50", toMillis(percentile(sorted, 50)));
		summary.put("p95", toMillis(percentile(sorted, 95)));
		summary.put("p99", toMillis(percentile(sorted, 99)));
		summary.put("max", toMillis(sorted.get(sorted.size() - 1)));
		return summary;
	}

	private long percentile(List<Long> sortedNanos, int percentile) {
		int index = Math.min(sortedNanos.size() - 1,
			(int)Math.ceil(percentile / 100.0 * sortedNanos.size()) - 1);
		return sortedNanos.get(Math.max(0, index));
	}

	private double toMillis(long nanos) {
		return Math.round(nanos / 1_000.0) / 1_000.0;
	}

	private String toJson(List<Map<String, Object>> evidence) {
		StringBuilder builder = new StringBuilder("[\n");
		for (int i = 0; i < evidence.size(); i++) {
			builder.append("  ").append(evidence.get(i));
			builder.append(i < evidence.size() - 1 ? ",\n" : "\n");
		}
		builder.append("]");
		return builder.toString();
	}

	private List<Game> fillerGames() {
		List<Game> games = new ArrayList<>();
		for (int i = 0; i < FILLER_GAME_COUNT; i++) {
			Game filler = new Game(984_000L + i, "무관작품" + i, "UnrelatedTitle" + i, "2~4명", "전략", "30분",
				"카드를 모아 점수를 겨루는 활동입니다.", "상세 설명");
			ReflectionTestUtils.setField(filler, "complexity", new BigDecimal("2.00"));
			games.add(filler);
		}
		List<Game> saved = gameRepository.saveAllAndFlush(games);
		createdGames.addAll(saved);
		return saved;
	}

	private GameMechanism mechanism(String code) {
		GameMechanism saved = gameMechanismRepository.saveAndFlush(
			new GameMechanism(
				700_000L + Math.abs(code.hashCode()), code, "일꾼 놓기", "Worker Placement", code + " 방식을 활용해요.",
				null, true, "#983", "reviewer", java.time.Instant.parse("2026-08-22T00:00:00Z")));
		createdMechanisms.add(saved);
		return saved;
	}

	private Game stoneAge() {
		Game game = new Game(984_635L, "돌의 시대", "Stone Age",
			"2~4명", "전략", "60분",
			"이 게임은 일꾼 놓기 방식을 사용합니다. Players compete for food to feed their populations.",
			"상세 설명");
		ReflectionTestUtils.setField(game, "complexity", new BigDecimal("2.50"));
		Game saved = gameRepository.saveAndFlush(game);
		createdGames.add(saved);
		return saved;
	}

	private void link(Game game, GameMechanism mechanism) {
		createdRelations.add(gameMechanismRelationRepository.saveAndFlush(new GameMechanismRelation(game, mechanism)));
	}
}
