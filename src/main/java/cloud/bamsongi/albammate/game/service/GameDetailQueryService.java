package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameCategorySummary;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.dto.GameThemeSummary;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameCategoryRelationRepository;
import cloud.bamsongi.albammate.game.repository.GamePlayerPreferenceRepository;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.GameThemeRelationRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 게임 상세 조회 유스케이스를 담당한다.
 *
 * <p>게임 상세 조립, 카테고리·테마 메타데이터, 인원 선호도, 해 본 게임 상태와 예정 모임 수를 조립한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameDetailQueryService {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;
	@NonNull private final UserPlayedGameRepository userPlayedGameRepository;
	@NonNull private final GameCategoryRelationRepository gameCategoryRelationRepository;
	@NonNull private final GameThemeRelationRepository gameThemeRelationRepository;
	@NonNull private final GamePlayerPreferenceRepository gamePlayerPreferenceRepository;

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
		var categories = gameCategoryRelationRepository.findSummariesByGameIdIn(List.of(gameId)).stream()
			.map(GameCategorySummary::from).toList();
		var themes = gameThemeRelationRepository.findSummariesByGameIdIn(List.of(gameId)).stream()
			.map(GameThemeSummary::from).toList();
		var preferences = gamePlayerPreferenceRepository.findByGameIdOrderByIdPlayerCountAsc(gameId);
		return GameDetail.from(game, upcomingRoomCount, playedByMe, categories, themes,
			preferences.stream().filter(p -> p.isRecommended()).map(p -> p.getPlayerCount()).toList(),
			preferences.stream().filter(p -> p.isBest()).map(p -> p.getPlayerCount()).toList());
	}

	private Boolean playedByMe(Long currentUserId, Long gameId) {
		return currentUserId == null
			? null
			: userPlayedGameRepository.existsByUserIdAndGameId(currentUserId, gameId);
	}
}
