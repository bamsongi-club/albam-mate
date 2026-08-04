package cloud.bamsongi.albammate.game.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.game.dto.PlayedGameStateResponse;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 본인 해 본 게임 관계를 목표 상태로 수렴시키는 공개 유스케이스다. */
@Service
@RequiredArgsConstructor
public class UserPlayedGameService {

	@NonNull private final UserPlayedGameCommandExecutor commandExecutor;

	/** 유일 제약 경합은 이미 다른 요청이 같은 목표 상태를 만든 것으로 처리한다. */
	public PlayedGameStateResponse markPlayed(long userId, long gameId) {
		try {
			commandExecutor.markPlayed(userId, gameId);
		} catch (DataIntegrityViolationException exception) {
			switch (commandExecutor.inspectAfterMarkFailure(userId, gameId)) {
				case RELATION_EXISTS -> {
					// 같은 사용자·게임 관계의 동시 생성은 성공 상태로 수렴한다.
				}
				case GAME_MISSING -> throw new BusinessException(ErrorCode.GAME_NOT_FOUND);
				case GAME_EXISTS -> throw exception;
			}
		}
		return new PlayedGameStateResponse(gameId, true);
	}

	public PlayedGameStateResponse unmarkPlayed(long userId, long gameId) {
		commandExecutor.unmarkPlayed(userId, gameId);
		return new PlayedGameStateResponse(gameId, false);
	}
}
