package cloud.bamsongi.albammate.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import java.math.BigDecimal;
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

@ExtendWith(MockitoExtension.class)
class GameListQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

    @Mock private GameRepository gameRepository;

    @Mock private UpcomingRoomCountQuery upcomingRoomCountQuery;

    private GameListQueryService gameListQueryService;

    @BeforeEach
    void setUp() {
        gameListQueryService =
                new GameListQueryService(
                        gameRepository, Clock.fixed(NOW, ZoneOffset.UTC), upcomingRoomCountQuery);
    }

    @Test
    void 검색어를_strip하고_이름_부분검색_결과에_예정_모임_수를_매핑한다() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("name", "id"));
        Game game = mockGame(1L, "카탄");
        when(gameRepository.findByNameContainingIgnoreCase("카탄", pageable))
                .thenReturn(new PageImpl<>(List.of(game), pageable, 1));
        when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW))
                .thenReturn(Map.of(1L, 2L));

        Page<GameListItem> result = gameListQueryService.findPage("  카탄  ", pageable);

        assertEquals(1, result.getTotalElements());
        assertEquals("카탄", result.getContent().getFirst().name());
        assertEquals(2L, result.getContent().getFirst().upcomingRoomCount());
        verify(gameRepository).findByNameContainingIgnoreCase("카탄", pageable);
        verify(upcomingRoomCountQuery).findUpcomingRoomCounts(List.of(1L), NOW);
    }

    @Test
    void 전각_공백이_포함된_검색어를_strip해_repository_검색_인자로_전달한다() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("name", "id"));
        Game game = mockGame(1L, "카탄");
        when(gameRepository.findByNameContainingIgnoreCase("카탄", pageable))
                .thenReturn(new PageImpl<>(List.of(game), pageable, 1));
        when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of());

        Page<GameListItem> result = gameListQueryService.findPage("\u3000카탄\u3000", pageable);

        assertEquals("카탄", result.getContent().getFirst().name());
        verify(gameRepository).findByNameContainingIgnoreCase("카탄", pageable);
    }

    @Test
    void 검색어가_없으면_전체_페이지를_조회한다() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("name", "id"));
        when(gameRepository.findAll(pageable)).thenReturn(Page.empty(pageable));

        Page<GameListItem> result = gameListQueryService.findPage("  ", pageable);

        assertEquals(0, result.getTotalElements());
        verify(gameRepository).findAll(pageable);
        verifyNoInteractions(upcomingRoomCountQuery);
    }

    @Test
    void count가_없는_게임은_예정_모임_수를_0으로_채운다() {
        Pageable pageable = PageRequest.of(0, 10, Sort.by("name", "id"));
        Game game = mockGame(1L, "카탄");
        when(gameRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(game), pageable, 1));
        when(upcomingRoomCountQuery.findUpcomingRoomCounts(List.of(1L), NOW)).thenReturn(Map.of());

        Page<GameListItem> result = gameListQueryService.findPage(null, pageable);

        assertEquals(0L, result.getContent().getFirst().upcomingRoomCount());
    }

    private Game mockGame(Long id, String name) {
        Game game = mock(Game.class);
        when(game.getId()).thenReturn(id);
        when(game.getBggId()).thenReturn(1001L);
        when(game.getName()).thenReturn(name);
        when(game.getEnglishName()).thenReturn("Catan");
        when(game.getImageUrl()).thenReturn(null);
        when(game.getRecommendedPlayerCount()).thenReturn("3~4명");
        when(game.getTag()).thenReturn("전략");
        when(game.getEstimatedPlayTime()).thenReturn("60~90분");
        when(game.getComplexity()).thenReturn(new BigDecimal("2.00"));
        return game;
    }
}
