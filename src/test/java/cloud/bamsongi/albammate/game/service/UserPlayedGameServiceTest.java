package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import cloud.bamsongi.albammate.game.dto.PlayedGameStateResponse;
import cloud.bamsongi.albammate.game.service.UserPlayedGameCommandExecutor.RecoveryState;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class UserPlayedGameServiceTest {

	@Mock
	private UserPlayedGameCommandExecutor commandExecutor;
	@InjectMocks
	private UserPlayedGameService userPlayedGameService;

	@Test
	void 동시등록_유일제약_오류는_새_읽기경계에서_관계가_확인될때만_성공으로_수렴한다() {
		DataIntegrityViolationException exception = new DataIntegrityViolationException("unique");
		doThrow(exception).when(commandExecutor).markPlayed(1L, 2L);
		when(commandExecutor.inspectAfterMarkFailure(1L, 2L)).thenReturn(RecoveryState.RELATION_EXISTS);

		assertEquals(new PlayedGameStateResponse(2L, true), userPlayedGameService.markPlayed(1L, 2L));
		verify(commandExecutor).inspectAfterMarkFailure(1L, 2L);
	}

	@Test
	void 무결성오류뒤_관계와_게임이_없으면_GAME_NOT_FOUND를_반환한다() {
		doThrow(new DataIntegrityViolationException("foreign key")).when(commandExecutor).markPlayed(1L, 2L);
		when(commandExecutor.inspectAfterMarkFailure(1L, 2L)).thenReturn(RecoveryState.GAME_MISSING);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> userPlayedGameService.markPlayed(1L, 2L));

		assertEquals(ErrorCode.GAME_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 무결성오류뒤_관계는없고_게임이있으면_원오류를_재전파한다() {
		DataIntegrityViolationException expected = new DataIntegrityViolationException("foreign key");
		doThrow(expected).when(commandExecutor).markPlayed(1L, 2L);
		when(commandExecutor.inspectAfterMarkFailure(1L, 2L)).thenReturn(RecoveryState.GAME_EXISTS);

		assertSame(expected, assertThrows(DataIntegrityViolationException.class, () -> userPlayedGameService.markPlayed(1L, 2L)));
	}
}
