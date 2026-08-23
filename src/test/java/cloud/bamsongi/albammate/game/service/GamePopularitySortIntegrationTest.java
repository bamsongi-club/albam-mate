package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Slice;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.fixture.GameFixture;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.config.JpaConfig;
import cloud.bamsongi.albammate.global.config.TimeConfig;
import cloud.bamsongi.albammate.room.service.query.RoomUpcomingRoomCountQuery;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	GameQueryService.class,
	GameFilterValidator.class,
	RoomUpcomingRoomCountQuery.class,
	JpaConfig.class,
	TimeConfig.class,
	GamePopularitySortIntegrationTest.FixedClockTestConfiguration.class
})
class GamePopularitySortIntegrationTest {

	@Autowired
	private GameRepository gameRepository;

	@Autowired
	private GameQueryService gameQueryService;

	@Test
	void 게임_목록_기본정렬은_인기점수_내림차순_이름_ID_오름차순이다() {
		Game firstTie = saveGame(1001L, "알파", "1.000000");
		Game secondTie = saveGame(1002L, "알파", "1.000000");
		Game lowerScore = saveGame(1003L, "가나다", "0.500000");

		Slice<GameListItem> result = gameQueryService.findPage(new GameListRequest(), null);

		assertEquals(List.of(firstTie.getId(), secondTie.getId(), lowerScore.getId()),
			result.getContent().stream().map(GameListItem::id).toList());
	}

	@Test
	// T6은 키워드 필터 뒤의 인기순 페이지 경계를 직접 검증한다.
	void 인기순_변경뒤에도_키워드_필터_페이지네이션과_기존_응답필드를_유지한다() {
		Game first = saveGame(2001L, "알파", "0.900000");
		saveGame(2002L, "알파", "0.800000");
		saveGame(2003L, "베타", "1.000000");
		GameListRequest request = new GameListRequest();
		request.setKeyword("알파");
		request.setPage(0);
		request.setSize(1);

		Slice<GameListItem> result = gameQueryService.findPage(request, null);

		assertEquals(true, result.hasNext());
		assertEquals(first.getId(), result.getContent().getFirst().id());
		assertEquals(2001L, result.getContent().getFirst().bggId());
		assertEquals("알파", result.getContent().getFirst().name());
		assertEquals("Catan", result.getContent().getFirst().englishName());
		assertEquals("3~4명", result.getContent().getFirst().supportedPlayerCount());
	}

	private Game saveGame(long bggId, String name, String popularityScore) {
		Game game = GameFixture.valid(bggId, name);
		ReflectionTestUtils.setField(game, "popularityScore", new java.math.BigDecimal(popularityScore));
		return gameRepository.saveAndFlush(game);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
		}
	}
}
