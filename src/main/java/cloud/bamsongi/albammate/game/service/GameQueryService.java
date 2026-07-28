package cloud.bamsongi.albammate.game.service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class GameQueryService implements GameQuery {

    private final GameRepository gameRepository;

    public GameQueryService(GameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    @Override
    public boolean existsById(Long gameId) {
        return gameRepository.existsById(gameId);
    }

    @Override
    public Optional<GameSummary> findSummaryById(Long gameId) {
        return gameRepository.findSummaryById(gameId);
    }

    @Override
    public Map<Long, GameSummary> findSummariesById(Collection<Long> gameIds) {
        if (gameIds.isEmpty()) {
            return Map.of();
        }
        return gameRepository.findSummariesById(gameIds).stream()
                .collect(Collectors.toMap(GameSummary::id, Function.identity()));
    }
}
