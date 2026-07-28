package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class RoomParticipationCancelServiceTest {

    private static final long ROOM_ID = 7L;
    private static final long USER_ID = 42L;
    private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");

    @Mock private RoomParticipationCancelExecutor executor;

    @Test
    void 낙관_락_충돌_뒤_다음_독립_시도가_성공하면_취소_응답을_반환한다() {
        RoomParticipationCancelService service = service();
        RoomParticipationResponse expected = response();
        when(executor.cancelParticipation(USER_ID, ROOM_ID, REQUEST_TIME))
                .thenThrow(new OptimisticLockException())
                .thenReturn(expected);

        assertSame(expected, service.cancelParticipation(USER_ID, ROOM_ID));

        verify(executor, times(2)).cancelParticipation(USER_ID, ROOM_ID, REQUEST_TIME);
    }

    @Test
    void 세_번_모두_낙관_락_충돌이면_마지막_원인을_보존한_409를_반환한다() {
        RoomParticipationCancelService service = service();
        OptimisticLockException third = new OptimisticLockException("third");
        when(executor.cancelParticipation(USER_ID, ROOM_ID, REQUEST_TIME))
                .thenThrow(new OptimisticLockException("first"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Room.class, ROOM_ID))
                .thenThrow(third);

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> service.cancelParticipation(USER_ID, ROOM_ID));

        assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
        assertSame(third, exception.getCause());
        verify(executor, times(3)).cancelParticipation(USER_ID, ROOM_ID, REQUEST_TIME);
    }

    @Test
    void 업무_오류는_재시도하지_않고_그대로_전달한다() {
        RoomParticipationCancelService service = service();
        BusinessException expected = new BusinessException(ErrorCode.PARTICIPATION_NOT_FOUND);
        when(executor.cancelParticipation(USER_ID, ROOM_ID, REQUEST_TIME)).thenThrow(expected);

        assertSame(
                expected,
                assertThrows(
                        BusinessException.class,
                        () -> service.cancelParticipation(USER_ID, ROOM_ID)));

        verify(executor).cancelParticipation(USER_ID, ROOM_ID, REQUEST_TIME);
    }

    private RoomParticipationCancelService service() {
        return new RoomParticipationCancelService(
                executor, Clock.fixed(REQUEST_TIME, ZoneOffset.UTC));
    }

    private RoomParticipationResponse response() {
        return new RoomParticipationResponse(
                ROOM_ID, ParticipationStatus.CANCELED, RoomStatus.RECRUITING, 1, 2);
    }
}
