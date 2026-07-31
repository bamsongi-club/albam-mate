package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameQueryService implements GameQuery {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;

	/**
	 * 게임 이름 검색 결과를 페이지로 조회하고 조회 시각 기준 예정 모임 수를 결합한다.
	 *
	 * <p>{@code keyword}가 {@code null}이거나 공백이면 전체 게임을 조회한다.
	 *
	 * @param keyword 게임 이름 검색어
	 * @param pageable 페이지 번호와 크기(정렬은 서비스의 이름, ID 오름차순으로 고정)
	 * @return 예정 모임 수가 포함된 게임 목록 페이지
	 */
	public Page<GameListItem> findPage(String keyword, Pageable pageable) {
		return findPage(keyword, false, pageable.getPageNumber(), pageable.getPageSize());
	}

	/**
	 * 게임 이름 검색과 예정 모임 존재 여부를 적용해 이름, ID 오름차순으로 게임 목록을 페이지로 조회한다.
	 *
	 * <p>{@code upcomingOnly}가 참이면 전체 예정 모임 집계를 먼저 조회해 해당 게임만 페이징한다.
	 *
	 * @param keyword 게임 이름 검색어
	 * @param upcomingOnly 예정 모임이 있는 게임만 조회할지 여부
	 * @param page 페이지 번호
	 * @param size 페이지 크기
	 * @return 예정 모임 수가 포함된 게임 목록 페이지
	 */
	public Page<GameListItem> findPage(String keyword, boolean upcomingOnly, int page, int size) {
		Pageable pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
		String normalizedKeyword = keyword == null ? null : keyword.strip();
		if (upcomingOnly) {
			return findUpcomingOnlyPage(normalizedKeyword, pageable);
		}

		Page<GameListRow> games = StringUtils.hasText(normalizedKeyword)
			? gameRepository.findListRowsByNameContainingIgnoreCase(normalizedKeyword, pageable)
			: gameRepository.findAllListRows(pageable);
		if (games.isEmpty()) {
			return games.map(game -> GameListItem.from(game, 0L));
		}

		Map<Long, Long> upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(
			games.getContent().stream().map(GameListRow::id).toList(),
			Instant.now(clock));

		return games.map(
			game -> GameListItem.from(game, upcomingRoomCounts.getOrDefault(game.id(), 0L)));
	}

	private Page<GameListItem> findUpcomingOnlyPage(String normalizedKeyword, Pageable pageable) {
		Instant now = Instant.now(clock);
		Map<Long, Long> upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(now);
		if (upcomingRoomCounts.isEmpty()) {
			return Page.empty(pageable);
		}

		Page<GameListRow> games = StringUtils.hasText(normalizedKeyword)
			? gameRepository.findListRowsByIdInAndNameContainingIgnoreCase(
				upcomingRoomCounts.keySet(), normalizedKeyword, pageable)
			: gameRepository.findListRowsByIdIn(upcomingRoomCounts.keySet(), pageable);
		return games.map(
			game -> GameListItem.from(game, upcomingRoomCounts.getOrDefault(game.id(), 0L)));
	}

	/**
	 * 게임 상세와 조회 시각 기준 예정 모임 수를 조회한다.
	 *
	 * @param gameId 알밤메이트 내부 게임 ID
	 * @return 예정 모임 수가 포함된 게임 상세
	 * @throws BusinessException 게임이 없으면 {@link ErrorCode#GAME_NOT_FOUND}
	 */
	public GameDetail findById(Long gameId) {
		Game game = gameRepository
			.findById(gameId)
			.orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
		long upcomingRoomCount = upcomingRoomCountQuery
			.findUpcomingRoomCounts(List.of(game.getId()), Instant.now(clock))
			.getOrDefault(game.getId(), 0L);

		return GameDetail.from(game, upcomingRoomCount);
	}

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
