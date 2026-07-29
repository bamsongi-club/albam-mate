package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import jakarta.persistence.OptimisticLockException;

@ExtendWith(MockitoExtension.class)
class RoomStatusChangeServiceTest {

	private static final long USER_ID = 42L;
	private static final long ROOM_ID = 7L;
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomStatusChangeExecutor executor;

	private RoomStatusChangeService service;

	@BeforeEach
	void setUp() {
		service = new RoomStatusChangeService(executor, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void 취소는_첫_시도_성공_응답을_반환한다() {
		RoomStatusResponse expected = new RoomStatusResponse(ROOM_ID, RoomStatus.CANCELED);
		when(executor.cancelRoom(USER_ID, ROOM_ID, NOW)).thenReturn(expected);

		RoomStatusResponse actual = service.cancelRoom(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 취소는_낙관_락_충돌_뒤_같은_시각으로_재시도한다() {
		RoomStatusResponse expected = new RoomStatusResponse(ROOM_ID, RoomStatus.CANCELED);
		when(executor.cancelRoom(USER_ID, ROOM_ID, NOW))
			.thenThrow(new ObjectOptimisticLockingFailureException(getClass(), ROOM_ID))
			.thenReturn(expected);

		RoomStatusResponse actual = service.cancelRoom(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor, org.mockito.Mockito.times(2)).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 취소가_세_번_낙관_락_충돌하면_동시_변경_오류를_반환한다() {
		doThrow(new OptimisticLockException())
			.when(executor)
			.cancelRoom(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());

		BusinessException exception = assertThrows(BusinessException.class, () -> service.cancelRoom(USER_ID, ROOM_ID));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		verify(executor, org.mockito.Mockito.times(3)).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 종료는_낙관_락_충돌_뒤_같은_시각으로_재시도한다() {
		RoomStatusResponse expected = new RoomStatusResponse(ROOM_ID, RoomStatus.FINISHED);
		when(executor.finishRoom(USER_ID, ROOM_ID, NOW))
			.thenThrow(new ObjectOptimisticLockingFailureException(getClass(), ROOM_ID))
			.thenReturn(expected);

		RoomStatusResponse actual = service.finishRoom(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor, org.mockito.Mockito.times(2)).finishRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 업무_오류는_재시도하지_않는다() {
		BusinessException expected = new BusinessException(ErrorCode.FORBIDDEN);
		doThrow(expected)
			.when(executor)
			.cancelRoom(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());

		BusinessException actual = assertThrows(BusinessException.class, () -> service.cancelRoom(USER_ID, ROOM_ID));

		assertSame(expected, actual);
		verify(executor).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 세_번_낙관_락_충돌하면_동시_변경_오류를_반환한다() {
		doThrow(new OptimisticLockException())
			.when(executor)
			.finishRoom(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());

		BusinessException exception = assertThrows(BusinessException.class, () -> service.finishRoom(USER_ID, ROOM_ID));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		verify(executor, org.mockito.Mockito.times(3)).finishRoom(USER_ID, ROOM_ID, NOW);
	}
}
