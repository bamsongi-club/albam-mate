package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import jakarta.persistence.OptimisticLockException;

@ExtendWith(MockitoExtension.class)
class RoomParticipationServiceUnitTest {

	private static final long ROOM_ID = 7L;
	private static final long USER_ID = 42L;
	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomParticipationExecutor executor;

	@Test
	void 낙관_락_충돌_뒤_다음_독립_시도가_성공하면_응답을_반환한다() {
		RoomParticipationService service = service();
		OptimisticLockException conflict = new OptimisticLockException();
		RoomParticipationResponse expected = response();
		when(executor.participate(USER_ID, ROOM_ID, REQUEST_TIME))
			.thenThrow(conflict)
			.thenReturn(expected);

		RoomParticipationResponse actual = service.participate(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor, times(2)).participate(USER_ID, ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 세_번_모두_낙관_락_충돌이면_마지막_원인을_보존한_409를_반환한다() {
		RoomParticipationService service = service();
		OptimisticLockException first = new OptimisticLockException("first");
		OptimisticLockException second = new OptimisticLockException("second");
		OptimisticLockException third = new OptimisticLockException("third");
		when(executor.participate(USER_ID, ROOM_ID, REQUEST_TIME))
			.thenThrow(first)
			.thenThrow(second)
			.thenThrow(third);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> service.participate(USER_ID, ROOM_ID));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		assertSame(third, exception.getCause());
		verify(executor, times(3)).participate(USER_ID, ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 스프링_낙관_락_예외도_재시도한다() {
		RoomParticipationService service = service();
		RoomParticipationResponse expected = response();
		when(executor.participate(USER_ID, ROOM_ID, REQUEST_TIME))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, ROOM_ID))
			.thenReturn(expected);

		assertSame(expected, service.participate(USER_ID, ROOM_ID));

		verify(executor, times(2)).participate(USER_ID, ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 업무_오류는_재시도하지_않고_그대로_전달한다() {
		RoomParticipationService service = service();
		BusinessException expected = new BusinessException(ErrorCode.CAPACITY_EXCEEDED);
		when(executor.participate(USER_ID, ROOM_ID, REQUEST_TIME)).thenThrow(expected);

		BusinessException actual = assertThrows(BusinessException.class, () -> service.participate(USER_ID, ROOM_ID));

		assertSame(expected, actual);
		verify(executor).participate(USER_ID, ROOM_ID, REQUEST_TIME);
	}

	private RoomParticipationService service() {
		return new RoomParticipationService(
			executor,
			new RoomCommandExecutionCoordinator(
				Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), new RoomOptimisticLockRetrier()));
	}

	private RoomParticipationResponse response() {
		return new RoomParticipationResponse(
			ROOM_ID, ParticipationStatus.ACTIVE, RoomStatus.RECRUITING, 2, 1);
	}
}
