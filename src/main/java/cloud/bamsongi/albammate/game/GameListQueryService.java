package cloud.bamsongi.albammate.game;

import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class GameListQueryService {

    private final GameRepository gameRepository;
    private final Clock clock;
    private final UpcomingRoomCountQuery upcomingRoomCountQuery;

    public GameListQueryService(
            GameRepository gameRepository,
            Clock clock,
            UpcomingRoomCountQuery upcomingRoomCountQuery) {
        this.gameRepository = gameRepository;
        this.clock = clock;
        this.upcomingRoomCountQuery = upcomingRoomCountQuery;
    }

    public Page<GameListItem> findPage(String keyword, Pageable pageable) {
        Page<Game> games =
                StringUtils.hasText(keyword)
                        ? gameRepository.findByNameContainingIgnoreCase(keyword.trim(), pageable)
                        : gameRepository.findAll(pageable);
        if (games.isEmpty()) {
            return games.map(game -> toListItem(game, 0L));
        }

        Map<Long, Long> upcomingRoomCounts =
                upcomingRoomCountQuery.findUpcomingRoomCounts(
                        games.getContent().stream().map(Game::getId).toList(), Instant.now(clock));

        return games.map(
                game -> toListItem(game, upcomingRoomCounts.getOrDefault(game.getId(), 0L)));
    }

    private GameListItem toListItem(Game game, long upcomingRoomCount) {
        return new GameListItem(
                game.getId(),
                game.getBggId(),
                game.getName(),
                game.getEnglishName(),
                game.getImageUrl(),
                game.getRecommendedPlayerCount(),
                game.getTag(),
                game.getEstimatedPlayTime(),
                game.getComplexity(),
                upcomingRoomCount);
    }
}
