package cloud.bamsongi.albammate.game.service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameQueryService implements GameQuery {

    private final GameRepository gameRepository;

    @Override
    public Optional<GameSummary> findSummaryById(Long gameId) {
        return gameRepository.findSummaryById(gameId);
    }

    @Override
    public Map<Long, GameSummary> findSummariesByIds(Collection<Long> gameIds) {
        if (gameIds.isEmpty()) {
            return Map.of();
        }
        return gameRepository.findSummariesByIds(gameIds).stream()
                .collect(Collectors.toMap(GameSummary::id, Function.identity()));
    }
}
