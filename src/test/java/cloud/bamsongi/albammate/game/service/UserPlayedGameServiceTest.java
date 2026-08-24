package cloud.bamsongi.albammate.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.game.dto.PlayedGameStateResponse;
import cloud.bamsongi.albammate.game.repository.GameRepository;
import cloud.bamsongi.albammate.game.repository.UserPlayedGameRepository;
import cloud.bamsongi.albammate.game.service.UserPlayedGameCommandExecutor.RecoveryState;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

@ExtendWith(MockitoExtension.class)
class UserPlayedGameServiceTest {

	@Mock
	private UserPlayedGameCommandExecutor commandExecutor;
	@Mock
	private GameRepository gameRepository;
	@Mock
	private UserPlayedGameRepository userPlayedGameRepository;
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
	void T3_플레이_상태_변경_성공은_허용된_구조화_key_value로_기록한다() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(UserPlayedGameService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			userPlayedGameService.markPlayed(1L, 2L);
			userPlayedGameService.unmarkPlayed(1L, 2L);

			assertEquals(2, appender.list.size());
			assertPlayedStateChange(appender.list.get(0), "mark", "played");
			assertPlayedStateChange(appender.list.get(1), "unmark", "not_played");
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
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

		assertSame(expected,
			assertThrows(DataIntegrityViolationException.class, () -> userPlayedGameService.markPlayed(1L, 2L)));
	}

	@Test
	void 무결성오류뒤_새읽기경계는_관계와_게임존재상태를_구분한다() {
		UserPlayedGameCommandExecutor executor = new UserPlayedGameCommandExecutor(
			gameRepository,
			userPlayedGameRepository,
			Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
		when(userPlayedGameRepository.existsByUserIdAndGameId(1L, 2L)).thenReturn(true, false, false);
		when(gameRepository.existsById(2L)).thenReturn(true, false);

		assertEquals(RecoveryState.RELATION_EXISTS, executor.inspectAfterMarkFailure(1L, 2L));
		assertEquals(RecoveryState.GAME_EXISTS, executor.inspectAfterMarkFailure(1L, 2L));
		assertEquals(RecoveryState.GAME_MISSING, executor.inspectAfterMarkFailure(1L, 2L));
	}

	private void assertPlayedStateChange(ILoggingEvent event, String action, String outcome) {
		Map<String, Object> fields = event.getKeyValuePairs().stream()
			.collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));

		assertEquals("game_played_state_changed", fields.get("event"));
		assertEquals(2L, fields.get("gameId"));
		assertEquals(action, fields.get("action"));
		assertEquals(outcome, fields.get("outcome"));
	}
}
