package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameRepository;

@ExtendWith(MockitoExtension.class)
class GameQueryServiceListTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Mock
	private GameRepository gameRepository;

	@Mock
	private UpcomingRoomCountQuery upcomingRoomCountQuery;

	private GameQueryService gameQueryService;

	@BeforeEach
	void setUp() {
		gameQueryService = new GameQueryService(
			gameRepository, Clock.fixed(NOW, ZoneOffset.UTC), upcomingRoomCountQuery);
	}

	@Test
	void 검색어를_strip하고_이름_부분검색_결과에_예정_모임_수를_매핑한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		GameListRow game = gameListRow(1L, "카탄");
		when(gameRepository.findListRowsByNameContainingIgnoreCase("카탄", pageable))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW))
			.thenReturn(Map.of(1L, 2L));

		Page<GameListItem> result = gameQueryService.findPage("  카탄  ", false, 0, 10);

		assertEquals(1, result.getTotalElements());
		assertEquals("카탄", result.getContent().getFirst().name());
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
		verify(gameRepository).findListRowsByNameContainingIgnoreCase("카탄", pageable);
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(List.of(1L), NOW);
	}

	@Test
	void 전각_공백이_포함된_검색어를_strip해_repository_검색_인자로_전달한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		GameListRow game = gameListRow(1L, "카탄");
		when(gameRepository.findListRowsByNameContainingIgnoreCase("카탄", pageable))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of());

		Page<GameListItem> result = gameQueryService.findPage("\u3000카탄\u3000", false, 0, 10);

		assertEquals("카탄", result.getContent().getFirst().name());
		verify(gameRepository).findListRowsByNameContainingIgnoreCase("카탄", pageable);
	}

	@Test
	void 검색어가_없으면_전체_페이지를_조회한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		when(gameRepository.findAllListRows(pageable)).thenReturn(Page.empty(pageable));

		Page<GameListItem> result = gameQueryService.findPage("  ", false, 0, 10);

		assertEquals(0, result.getTotalElements());
		verify(gameRepository).findAllListRows(pageable);
		verifyNoInteractions(upcomingRoomCountQuery);
	}

	@Test
	void count가_없는_게임은_예정_모임_수를_0으로_채운다() {
		Pageable pageable = fixedPageRequest(0, 10);
		GameListRow game = gameListRow(1L, "카탄");
		when(gameRepository.findAllListRows(pageable))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of());

		Page<GameListItem> result = gameQueryService.findPage(null, false, 0, 10);

		assertEquals(0L, result.getContent().getFirst().upcomingRoomCount());
	}

	@Test
	void 예정_모임_필터는_전체_집계의_게임_ID로_페이지를_조회하고_count를_재사용한다() {
		Pageable pageable = fixedPageRequest(0, 1);
		Map<Long, Long> upcomingRoomCounts = Map.of(1L, 2L, 2L, 1L);
		GameListRow game = gameListRow(1L, "카탄");
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(upcomingRoomCounts);
		when(gameRepository.findListRowsByIdIn(upcomingRoomCounts.keySet(), pageable))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 2));

		Page<GameListItem> result = gameQueryService.findPage(null, true, 0, 1);

		assertEquals(2, result.getTotalElements());
		assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(NOW);
		verify(gameRepository).findListRowsByIdIn(upcomingRoomCounts.keySet(), pageable);
	}

	@Test
	void 예정_모임_필터와_검색어를_함께_사용하면_strip한_검색어를_같이_적용한다() {
		Pageable pageable = fixedPageRequest(0, 10);
		Map<Long, Long> upcomingRoomCounts = Map.of(1L, 2L);
		GameListRow game = gameListRow(1L, "카탄");
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(upcomingRoomCounts);
		when(
			gameRepository.findListRowsByIdInAndNameContainingIgnoreCase(
				upcomingRoomCounts.keySet(), "카탄", pageable))
			.thenReturn(new PageImpl<>(List.of(game), pageable, 1));

		Page<GameListItem> result = gameQueryService.findPage("  카탄  ", true, 0, 10);

		assertEquals("카탄", result.getContent().getFirst().name());
		verify(gameRepository)
			.findListRowsByIdInAndNameContainingIgnoreCase(upcomingRoomCounts.keySet(), "카탄", pageable);
	}

	@Test
	void 예정_모임_게임이_없으면_IN_조회없이_요청_페이지_기준_빈_결과를_반환한다() {
		Pageable pageable = fixedPageRequest(2, 10);
		when(upcomingRoomCountQuery.findUpcomingRoomCounts(NOW)).thenReturn(Map.of());

		Page<GameListItem> result = gameQueryService.findPage(null, true, 2, 10);

		assertEquals(0, result.getTotalElements());
		assertEquals(2, result.getNumber());
		verify(upcomingRoomCountQuery).findUpcomingRoomCounts(NOW);
		verifyNoInteractions(gameRepository);
	}

	private GameListRow gameListRow(Long id, String name) {
		return new GameListRow(id, 1001L, name, "Catan", null, "3~4명", "전략", "60~90분", null);
	}

	private Pageable fixedPageRequest(int page, int size) {
		return PageRequest.of(page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
	}
}
