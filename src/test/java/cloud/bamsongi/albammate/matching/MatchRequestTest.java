package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.matching.entity.MatchRequest;

class MatchRequestTest {

	private static final Instant OPERATION_TIME = Instant.parse("2026-08-20T00:00:00Z");

	@Test
	void 게임_ID_없이_요청을_생성하고_gameId_의존을_노출하지_않는다() throws Exception {
		Method create = MatchRequest.class.getMethod(
			"create", long.class, int.class, int.class, MatchRequestStatus.class, Instant.class);
		MatchRequest request = (MatchRequest)create.invoke(null, 1L, 1, Short.MAX_VALUE, MatchRequestStatus.WAITING,
			OPERATION_TIME);

		assertEquals(1, request.getMinPartySize());
		assertEquals(Short.MAX_VALUE, request.getMaxPartySize());
		assertThrows(NoSuchFieldException.class, () -> MatchRequest.class.getDeclaredField("gameId"));
		assertThrows(NoSuchMethodException.class, () -> MatchRequest.class.getMethod("getGameId"));
	}

	@Test
	void 인원_범위는_저장_타입을_넘거나_유효하지_않으면_거절한다() {
		assertThrows(
			IllegalArgumentException.class,
			() -> create(1L, 0, 2, MatchRequestStatus.WAITING));
		assertThrows(
			IllegalArgumentException.class,
			() -> create(1L, 3, 2, MatchRequestStatus.WAITING));
		assertThrows(
			IllegalArgumentException.class,
			() -> create(1L, 1, Short.MAX_VALUE + 1, MatchRequestStatus.WAITING));
	}

	private MatchRequest create(long userId, int minPartySize, int maxPartySize, MatchRequestStatus status) {
		try {
			Method create = MatchRequest.class.getMethod(
				"create", long.class, int.class, int.class, MatchRequestStatus.class, Instant.class);
			return (MatchRequest)create.invoke(null, userId, minPartySize, maxPartySize, status, OPERATION_TIME);
		} catch (java.lang.reflect.InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new AssertionError(exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
