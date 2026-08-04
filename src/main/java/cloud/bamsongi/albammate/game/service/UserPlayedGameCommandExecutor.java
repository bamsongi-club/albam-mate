package cloud.bamsongi.albammate.game.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.entity.UserPlayedGame;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 해 본 게임 관계 한 건을 독립된 쓰기 트랜잭션에서 변경한다. */
@Service
@RequiredArgsConstructor
class UserPlayedGameCommandExecutor {

	@NonNull private final GameRepository gameRepository;
	@NonNull private final UserPlayedGameRepository userPlayedGameRepository;
	@NonNull private final Clock clock;

	/** 게임 존재를 먼저 확인하고 없는 관계만 저장한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPlayed(long userId, long gameId) {
		requireGame(gameId);
		if (userPlayedGameRepository.existsByUserIdAndGameId(userId, gameId)) {
			return;
		}
		userPlayedGameRepository.saveAndFlush(UserPlayedGame.create(userId, gameId, Instant.now(clock)));
	}

	/** 유일 제약 오류 뒤 새 읽기 트랜잭션에서 실제 저장 상태를 확인한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public RecoveryState inspectAfterMarkFailure(long userId, long gameId) {
		if (userPlayedGameRepository.existsByUserIdAndGameId(userId, gameId)) {
			return RecoveryState.RELATION_EXISTS;
		}
		return gameRepository.existsById(gameId)
			? RecoveryState.GAME_EXISTS
			: RecoveryState.GAME_MISSING;
	}

	/** 게임 존재를 먼저 확인한 뒤 현재 사용자의 관계만 삭제한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void unmarkPlayed(long userId, long gameId) {
		requireGame(gameId);
		userPlayedGameRepository.deleteByUserIdAndGameId(userId, gameId);
	}

	private void requireGame(long gameId) {
		if (!gameRepository.existsById(gameId)) {
			throw new BusinessException(ErrorCode.GAME_NOT_FOUND);
		}
	}

	enum RecoveryState {
		RELATION_EXISTS,
		GAME_EXISTS,
		GAME_MISSING
	}
}
