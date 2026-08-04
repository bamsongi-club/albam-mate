package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
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

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameQueryService implements GameQuery {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;
	@NonNull private final GameMechanismRepository gameMechanismRepository;
	@NonNull private final UserPlayedGameRepository userPlayedGameRepository;

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
		return findPage(GameListSearchCriteria.keywordOnly(keyword), pageable.getPageNumber(), pageable.getPageSize(), null);
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
		GameListRequest request = new GameListRequest();
		request.setKeyword(keyword);
		request.setUpcomingOnly(upcomingOnly);
		return findPage(request, page, size, null);
	}

	/**
	 * 게임 목록 조건을 하나의 저장소 동적 조회에 적용하고 예정 모임 수를 조립한다.
	 *
	 * @param request HTTP 목록 요청
	 * @return 예정 모임 수가 포함된 게임 목록 페이지
	 */
	public Page<GameListItem> findPage(GameListRequest request) {
		return findPage(request, null);
	}

	public Page<GameListItem> findPage(GameListRequest request, Long currentUserId) {
		return findPage(request, request.getPage(), request.getSize(), currentUserId);
	}

	private Page<GameListItem> findPage(GameListRequest request, int page, int size, Long currentUserId) {
		PlayedFilter playedFilter = request.getPlayedFilter();
		if (playedFilter != null && currentUserId == null) {
			throw new UnauthenticatedException();
		}
		validatePublicMechanismCodes(request.getMechanism());
		GameListSearchCriteria criteria = GameListSearchCriteria.from(request);
		if (playedFilter != null) {
			criteria = criteria.withPlayedFilter(currentUserId);
		}
		return findPage(criteria, page, size, currentUserId);
	}

	private void validatePublicMechanismCodes(List<String> requestedCodes) {
		List<String> codes = requestedCodes == null ? List.of() : requestedCodes.stream().distinct().toList();
		if (!codes.isEmpty() && gameMechanismRepository.countByCodeInAndIsPublicTrue(codes) != codes.size()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private Page<GameListItem> findPage(
		GameListSearchCriteria criteria, int page, int size, Long currentUserId) {
		Pageable pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
		Map<Long, Long> upcomingRoomCounts = Map.of();
		if (criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(Instant.now(clock));
			if (upcomingRoomCounts.isEmpty()) {
				return Page.empty(pageable);
			}
			criteria = criteria.withUpcomingGameIds(upcomingRoomCounts.keySet());
		}

		Page<Game> games = gameRepository.findAll(criteria.toSpecification(), pageable);
		if (games.isEmpty()) {
			return games.map(game -> GameListItem.from(GameListRow.from(game), 0L, playedByMe(currentUserId, game.getId())));
		}

		if (!criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(
				games.getContent().stream().map(Game::getId).toList(),
				Instant.now(clock));
		}

		Map<Long, Long> counts = upcomingRoomCounts;
		var playedGameIds = currentUserId == null
			? java.util.Set.<Long>of()
			: new HashSet<>(
				userPlayedGameRepository.findGameIdsByUserIdAndGameIdIn(
					currentUserId,
					games.getContent().stream().map(Game::getId).toList()));
		return games.map(
			game -> GameListItem.from(
				GameListRow.from(game),
				counts.getOrDefault(game.getId(), 0L),
				currentUserId == null ? null : playedGameIds.contains(game.getId())));
	}

	/**
	 * 게임 상세와 조회 시각 기준 예정 모임 수를 조회한다.
	 *
	 * @param gameId 알밤메이트 내부 게임 ID
	 * @return 예정 모임 수가 포함된 게임 상세
	 * @throws BusinessException 게임이 없으면 {@link ErrorCode#GAME_NOT_FOUND}
	 */
	public GameDetail findById(Long gameId) {
		return findById(gameId, null);
	}

	public GameDetail findById(Long gameId, Long currentUserId) {
		Game game = gameRepository
			.findById(gameId)
			.orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
		long upcomingRoomCount = upcomingRoomCountQuery
			.findUpcomingRoomCounts(List.of(game.getId()), Instant.now(clock))
			.getOrDefault(game.getId(), 0L);

		Boolean playedByMe = playedByMe(currentUserId, gameId);
		return GameDetail.from(game, upcomingRoomCount, playedByMe);
	}

	private Boolean playedByMe(Long currentUserId, Long gameId) {
		return currentUserId == null
			? null
			: userPlayedGameRepository.existsByUserIdAndGameId(currentUserId, gameId);
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
