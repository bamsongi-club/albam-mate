package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.UpcomingRoomCountQuery;
import cloud.bamsongi.albammate.game.dto.GameDetail;
import cloud.bamsongi.albammate.game.entity.Game;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GameDetailQueryService {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final Clock clock;
	@NonNull private final UpcomingRoomCountQuery upcomingRoomCountQuery;

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
}
