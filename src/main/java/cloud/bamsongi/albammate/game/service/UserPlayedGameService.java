package cloud.bamsongi.albammate.game.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.game.dto.PlayedGameStateResponse;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 사용자가 특정 게임을 '해 본 게임'으로 등록하거나 해제하는 서비스다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserPlayedGameService {

	@NonNull private final UserPlayedGameCommandExecutor commandExecutor;

	/** 같은 게임을 동시에 등록해도 이미 등록된 상태라면 성공으로 처리한다. */
	public PlayedGameStateResponse markPlayed(long userId, long gameId) {
		try {
			commandExecutor.markPlayed(userId, gameId);
		} catch (DataIntegrityViolationException exception) {
			UserPlayedGameCommandExecutor.RecoveryState recoveryState = commandExecutor.inspectAfterMarkFailure(
				userId, gameId);
			if (recoveryState == UserPlayedGameCommandExecutor.RecoveryState.RELATION_EXISTS) {
				// 동시 요청으로 같은 사용자-게임 관계가 이미 생성됐다면 원하는 최종 상태와 같으므로 성공 처리한다.
			} else if (recoveryState == UserPlayedGameCommandExecutor.RecoveryState.GAME_MISSING) {
				throw new BusinessException(ErrorCode.GAME_NOT_FOUND);
			} else {
				throw exception;
			}
		}
		PlayedGameStateResponse response = new PlayedGameStateResponse(gameId, true);
		log.atInfo()
			.addKeyValue("event", "game_played_state_changed")
			.addKeyValue("gameId", gameId)
			.addKeyValue("action", "mark")
			.addKeyValue("outcome", "played")
			.log("game played state changed");
		return response;
	}

	public PlayedGameStateResponse unmarkPlayed(long userId, long gameId) {
		commandExecutor.unmarkPlayed(userId, gameId);
		PlayedGameStateResponse response = new PlayedGameStateResponse(gameId, false);
		log.atInfo()
			.addKeyValue("event", "game_played_state_changed")
			.addKeyValue("gameId", gameId)
			.addKeyValue("action", "unmark")
			.addKeyValue("outcome", "not_played")
			.log("game played state changed");
		return response;
	}
}
