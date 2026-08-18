package cloud.bamsongi.albammate.game.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GamePlayerRange;
import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link GameQuery} 공개 요약 계약을 구현한다.
 *
 * <p>{@code room} 등 타 모듈은 이 구현 클래스가 아닌 {@link GameQuery} 인터페이스만 참조한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameSummaryQueryService implements GameQuery {

	@NonNull private final GameRepository gameRepository;

	@Override
	public Optional<GameSummary> findSummaryById(Long gameId) {
		return gameRepository.findSummaryById(gameId);
	}

	@Override
	public Optional<GamePlayerRange> findPlayerRangeById(Long gameId) {
		return gameRepository.findPlayerRangeById(gameId);
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
