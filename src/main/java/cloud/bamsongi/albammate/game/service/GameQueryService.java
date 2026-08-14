package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
	 * 게임 목록 조건을 하나의 저장소 동적 조회에 적용하고 예정 모임 수를 조립한다.
	 *
	 * <p>{@code playedFilter}가 있으면 인증 사용자가 필요하므로 {@code currentUserId}가 {@code null}이면 거절한다.
	 *
	 * @param request HTTP 목록 요청
	 * @param currentUserId 인증 사용자 ID. 비로그인 요청이면 {@code null}
	 * @return 예정 모임 수가 포함된 게임 목록 페이지
	 */
	public Page<GameListItem> findPage(GameListRequest request, Long currentUserId) {
		Instant referenceTime = Instant.now(clock);
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
		return findPage(criteria, request.getPage(), request.getSize(), currentUserId, referenceTime);
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
			page,
			size,
			Sort.by(
				Sort.Order.desc("popularityScore"),
				Sort.Order.asc("name"),
				Sort.Order.asc("id")));
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
			// 조립할 게임이 없으므로 예정 모임 수와 해 본 게임 조회를 건너뛰고 페이지 메타데이터만 그대로 전달한다.
			return new PageImpl<>(List.of(), pageable, games.getTotalElements());
		}

		if (!criteria.isUpcomingOnly()) {
			upcomingRoomCounts = upcomingRoomCountQuery.findUpcomingRoomCounts(
				games.getContent().stream().map(Game::getId).toList(),
				referenceTime);
		}

		Map<Long, Long> counts = upcomingRoomCounts;
		Set<Long> playedGameIds = currentUserId == null || pageCriteria.getPlayedFilter() != null
			? Set.of()
			: new HashSet<>(
				userPlayedGameRepository.findGameIdsByUserIdAndGameIdIn(
					currentUserId,
					games.getContent().stream().map(Game::getId).toList()));
		return games.map(
			game -> GameListItem.from(
				game,
				counts.getOrDefault(game.getId(), 0L),
				playedByMe(pageCriteria, currentUserId, game.getId(), playedGameIds)));
	}

	private Boolean playedByMe(
		GameListSearchCriteria criteria, Long currentUserId, Long gameId, Set<Long> playedGameIds) {
		if (currentUserId == null) {
			return null;
		}
		if (criteria.getPlayedFilter() != null) {
			return criteria.getPlayedFilter() == PlayedFilter.PLAYED_ONLY;
		}
		return playedGameIds.contains(gameId);
	}
}
