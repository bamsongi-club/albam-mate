package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameListItem;
import cloud.bamsongi.albammate.game.dto.GameListRequest;
import cloud.bamsongi.albammate.game.dto.PlayedFilter;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameCategoryRepository;
import cloud.bamsongi.albammate.game.repository.GameListRow;
import cloud.bamsongi.albammate.game.repository.GameListSpecification;
import cloud.bamsongi.albammate.game.repository.GameMechanismRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 게임 목록 조회 유스케이스를 담당한다.
 *
 * <p>검색 조건 검증, 페이지네이션, 예정 모임 수 집계, 해 본 게임 표시를 하나의 저장소 동적 조회 경계로 조립한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameQueryService {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;
	@NonNull private final GameMechanismRepository gameMechanismRepository;
	@NonNull private final UserPlayedGameRepository userPlayedGameRepository;
	@NonNull private final GameCategoryRepository gameCategoryRepository;
	@NonNull private final GameThemeRepository gameThemeRepository;

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
		return findPage(GameListSearchCriteria.keywordOnly(keyword), pageable.getPageNumber(), pageable.getPageSize(),
			null, Instant.now(clock));
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
		return findPage(request, page, size, null, Instant.now(clock));
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
		return findPage(request, request.getPage(), request.getSize(), currentUserId, Instant.now(clock));
	}

	private Page<GameListItem> findPage(
		GameListRequest request, int page, int size, Long currentUserId, Instant referenceTime) {
		PlayedFilter playedFilter = request.getPlayedFilter();
		if (playedFilter != null && currentUserId == null) {
			throw new UnauthenticatedException();
		}
		validatePublicMechanismCodes(request.getMechanism());
		validateCategoryCodes(request.getCategory());
		validateThemeCodes(request.getTheme());
		GameListSearchCriteria criteria = GameListSearchCriteria.from(request);
		if (playedFilter != null) {
			criteria = criteria.withPlayedFilter(currentUserId);
		}
		return findPage(criteria, page, size, currentUserId, referenceTime);
	}

	private void validateCategoryCodes(List<String> requestedCodes) {
		validateCodes(requestedCodes, gameCategoryRepository::countByCodeIn);
	}

	private void validateThemeCodes(List<String> requestedCodes) {
		validateCodes(requestedCodes, gameThemeRepository::countByCodeIn);
	}

	private void validatePublicMechanismCodes(List<String> requestedCodes) {
		validateCodes(requestedCodes, gameMechanismRepository::countByCodeInAndIsPublicTrue);
	}

	private void validateCodes(List<String> requestedCodes, Function<List<String>, Long> countByCodes) {
		List<String> codes = requestedCodes == null
			? List.of()
			: requestedCodes.stream().filter(Objects::nonNull).distinct().toList();
		if (!codes.isEmpty() && countByCodes.apply(codes) != codes.size()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private Page<GameListItem> findPage(
		GameListSearchCriteria criteria, int page, int size, Long currentUserId, Instant referenceTime) {
		Pageable pageable = PageRequest.of(
			page, size, Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")));
		Map<Long, Long> upcomingRoomCounts = Map.of();
		if (criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(referenceTime);
			if (upcomingRoomCounts.isEmpty()) {
				return Page.empty(pageable);
			}
			criteria = criteria.withUpcomingGameIds(upcomingRoomCounts.keySet());
		}

		GameListSearchCriteria pageCriteria = criteria;
		Page<Game> games = gameRepository.findAll(GameListSpecification.from(pageCriteria), pageable);
		if (games.isEmpty()) {
			return games
				.map(game -> GameListItem.from(GameListRow.from(game), 0L,
					playedByMe(pageCriteria, currentUserId, game.getId(),
						java.util.Set.of())));
		}

		if (!criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(
				games.getContent().stream().map(Game::getId).toList(),
				referenceTime);
		}

		Map<Long, Long> counts = upcomingRoomCounts;
		var playedGameIds = currentUserId == null || pageCriteria.getPlayedFilter() != null
			? java.util.Set.<Long>of()
			: new HashSet<>(
				userPlayedGameRepository.findGameIdsByUserIdAndGameIdIn(
					currentUserId,
					games.getContent().stream().map(Game::getId).toList()));
		return games.map(
			game -> GameListItem.from(
				GameListRow.from(game),
				counts.getOrDefault(game.getId(), 0L),
				playedByMe(pageCriteria, currentUserId, game.getId(), playedGameIds)));
	}

	private Boolean playedByMe(
		GameListSearchCriteria criteria, Long currentUserId, Long gameId, java.util.Set<Long> playedGameIds) {
		if (currentUserId == null) {
			return null;
		}
		if (criteria.getPlayedFilter() != null) {
			return criteria.getPlayedFilter() == PlayedFilter.PLAYED_ONLY;
		}
		return playedGameIds.contains(gameId);
	}
}
