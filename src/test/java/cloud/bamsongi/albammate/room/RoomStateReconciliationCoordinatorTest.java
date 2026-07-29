package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import jakarta.persistence.OptimisticLockException;

class RoomStateReconciliationCoordinatorTest {

	private static final Long ROOM_ID = 10L;
	private static final Instant REQUEST_TIME = Instant.parse("2026-07-27T00:00:00Z");

	@Test
	void 낙관락_충돌_후_성공하면_한_번만_재시도한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		OptimisticLockException conflict = new OptimisticLockException();
		doThrow(conflict).doNothing().when(executor).reconcileRoom(ROOM_ID, REQUEST_TIME);

		coordinator.reconcileRoom(ROOM_ID, REQUEST_TIME);

		verify(executor, times(2)).reconcileRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 세_번_충돌하면_오류코드와_마지막_cause를_보존한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		OptimisticLockException first = new OptimisticLockException("first");
		OptimisticLockException second = new OptimisticLockException("second");
		OptimisticLockException third = new OptimisticLockException("third");
		doThrow(first)
			.doThrow(second)
			.doThrow(third)
			.when(executor)
			.reconcileRoom(ROOM_ID, REQUEST_TIME);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> coordinator.reconcileRoom(ROOM_ID, REQUEST_TIME));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		assertSame(third, exception.getCause());
		verify(executor, times(3)).reconcileRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void Spring_낙관락_충돌_후_성공하면_동일한_요청시각으로_재시도한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		ObjectOptimisticLockingFailureException conflict = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		doThrow(conflict).doNothing().when(executor).reconcileRoom(ROOM_ID, REQUEST_TIME);

		coordinator.reconcileRoom(ROOM_ID, REQUEST_TIME);

		ArgumentCaptor<Instant> requestTimes = ArgumentCaptor.forClass(Instant.class);
		verify(executor, times(2)).reconcileRoom(eq(ROOM_ID), requestTimes.capture());
		requestTimes.getAllValues().forEach(requestTime -> assertSame(REQUEST_TIME, requestTime));
	}

	@Test
	void Spring_낙관락이_세_번_충돌하면_오류와_마지막_cause를_보존한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		ObjectOptimisticLockingFailureException first = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		ObjectOptimisticLockingFailureException second = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		ObjectOptimisticLockingFailureException third = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		doThrow(first)
			.doThrow(second)
			.doThrow(third)
			.when(executor)
			.reconcileRoom(ROOM_ID, REQUEST_TIME);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> coordinator.reconcileRoom(ROOM_ID, REQUEST_TIME));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		assertSame(third, exception.getCause());
		verify(executor, times(3)).reconcileRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 낙관락이_아닌_업무_예외는_재시도하지_않고_그대로_전달한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		BusinessException businessException = new BusinessException(ErrorCode.ROOM_NOT_FOUND);
		doThrow(businessException).when(executor).reconcileRoom(ROOM_ID, REQUEST_TIME);

		BusinessException thrown = assertThrows(
			BusinessException.class,
			() -> coordinator.reconcileRoom(ROOM_ID, REQUEST_TIME));

		assertSame(businessException, thrown);
		verify(executor).reconcileRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 재시도마다_동일한_요청_시각을_전달한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		doThrow(new OptimisticLockException())
			.doNothing()
			.when(executor)
			.reconcileDueRooms(REQUEST_TIME);

		coordinator.reconcileDueRooms(REQUEST_TIME);

		ArgumentCaptor<Instant> requestTimes = ArgumentCaptor.forClass(Instant.class);
		verify(executor, times(2)).reconcileDueRooms(requestTimes.capture());
		requestTimes.getAllValues().forEach(requestTime -> assertSame(REQUEST_TIME, requestTime));
		verify(executor, times(2)).reconcileDueRooms(eq(REQUEST_TIME));
	}

	@Test
	void 스케줄러_재시도_hook은_충돌_뒤_두번째와_세번째_시도_전에만_호출된다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		IntConsumer beforeRetry = mock(IntConsumer.class);
		doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.when(executor)
			.reconcileDueRooms(REQUEST_TIME);

		assertThrows(
			BusinessException.class,
			() -> coordinator.reconcileDueRooms(REQUEST_TIME, beforeRetry));

		verify(beforeRetry).accept(2);
		verify(beforeRetry).accept(3);
		verify(executor, times(3)).reconcileDueRooms(REQUEST_TIME);
	}

	@Test
	void 공개_due_보정은_지연_hook_없이_기존처럼_세_번_즉시_재시도한다() {
		RoomStateReconciliationExecutor executor = mock(RoomStateReconciliationExecutor.class);
		RoomStateReconciliationCoordinator coordinator = new RoomStateReconciliationCoordinator(executor);
		doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.when(executor)
			.reconcileDueRooms(REQUEST_TIME);

		assertThrows(BusinessException.class, () -> coordinator.reconcileDueRooms(REQUEST_TIME));

		verify(executor, times(3)).reconcileDueRooms(REQUEST_TIME);
	}
}
