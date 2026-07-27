package cloud.bamsongi.albammate.game.service;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GameDetailQueryService {

    private final GameRepository gameRepository;
    private final Clock clock;
    private final UpcomingRoomCountQuery upcomingRoomCountQuery;

    public GameDetailQueryService(
            GameRepository gameRepository,
            Clock clock,
            UpcomingRoomCountQuery upcomingRoomCountQuery) {
        this.gameRepository = gameRepository;
        this.clock = clock;
        this.upcomingRoomCountQuery = upcomingRoomCountQuery;
    }

    public GameDetail findById(Long gameId) {
        Game game =
                gameRepository
                        .findById(gameId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
        long upcomingRoomCount =
                upcomingRoomCountQuery
                        .findUpcomingRoomCounts(List.of(game.getId()), Instant.now(clock))
                        .getOrDefault(game.getId(), 0L);

        return new GameDetail(
                game.getId(),
                game.getBggId(),
                game.getName(),
                game.getEnglishName(),
                game.getImageUrl(),
                game.getSupportedPlayerCount(),
                game.getTag(),
                game.getEstimatedPlayTime(),
                game.getComplexity(),
                upcomingRoomCount,
                game.getAlias(),
                game.getDescription(),
                game.getDetailDescription());
    }
}
