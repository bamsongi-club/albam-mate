package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;

@ExtendWith(MockitoExtension.class)
class RoomWaitlistRegistrationCoordinatorTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");

	@Mock
	private RoomWaitlistRegistrationExecutor executor;

	@Test
	void T5_ROOM_충돌은_같은_기준시각으로_세번까지만_재시도하고_409로_끝난다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void 대기_순번_UNIQUE_충돌_소진은_내부_상세없이_500으로_끝난다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new DataIntegrityViolationException("uq_room_waitlists_waiting_room_queue_order"));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void 비대상_DB_오류는_재시도하지_않는다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new DataIntegrityViolationException("다른 제약"));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verify(executor).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void 메시지_없는_DB_오류는_재시도하지_않는다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new DataIntegrityViolationException((String)null));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verify(executor).register(7L, 1L, REQUEST_TIME);
	}
}
