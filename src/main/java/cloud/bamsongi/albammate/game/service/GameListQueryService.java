package cloud.bamsongi.albammate.game.service;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameListQueryService {

    private final GameRepository gameRepository;
    private final Clock clock;
    private final UpcomingRoomCountQuery upcomingRoomCountQuery;

    public Page<GameListItem> findPage(String keyword, Pageable pageable) {
        String normalizedKeyword = keyword == null ? null : keyword.strip();
        Page<GameListRow> games =
                StringUtils.hasText(normalizedKeyword)
                        ? gameRepository.findListRowsByNameContainingIgnoreCase(
                                normalizedKeyword, pageable)
                        : gameRepository.findAllListRows(pageable);
        if (games.isEmpty()) {
            return games.map(game -> toListItem(game, 0L));
        }

        Map<Long, Long> upcomingRoomCounts =
                upcomingRoomCountQuery.findUpcomingRoomCounts(
                        games.getContent().stream().map(GameListRow::id).toList(),
                        Instant.now(clock));

        return games.map(game -> toListItem(game, upcomingRoomCounts.getOrDefault(game.id(), 0L)));
    }

    private GameListItem toListItem(GameListRow game, long upcomingRoomCount) {
        return new GameListItem(
                game.id(),
                game.bggId(),
                game.name(),
                game.englishName(),
                game.imageUrl(),
                game.supportedPlayerCount(),
                game.tag(),
                game.estimatedPlayTime(),
                game.complexity(),
                upcomingRoomCount);
    }
}
