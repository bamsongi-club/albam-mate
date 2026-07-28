package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@ExtendWith(MockitoExtension.class)
class RoomUpdateServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Mock private RoomUpdateExecutor executor;

    private RoomUpdateService roomUpdateService;

    @BeforeEach
    void setUp() {
        roomUpdateService = new RoomUpdateService(executor, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 첫_시도_성공_응답을_반환한다() {
        ParticipantRoomResponse expected = org.mockito.Mockito.mock(ParticipantRoomResponse.class);
        when(executor.updateRoom(
                        anyLong(), anyLong(), any(RoomUpdateRequest.class), any(Instant.class)))
                .thenReturn(expected);

        ParticipantRoomResponse actual =
                roomUpdateService.updateRoom(42L, 7L, new RoomUpdateRequest());

        assertSame(expected, actual);
        verify(executor)
                .updateRoom(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        any(RoomUpdateRequest.class),
                        org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void 업무_오류는_재시도하지_않는다() {
        BusinessException expected = new BusinessException(ErrorCode.FORBIDDEN);
        doThrow(expected)
                .when(executor)
                .updateRoom(anyLong(), anyLong(), any(RoomUpdateRequest.class), any(Instant.class));

        BusinessException actual =
                assertThrows(
                        BusinessException.class,
                        () -> roomUpdateService.updateRoom(42L, 7L, new RoomUpdateRequest()));

        assertSame(expected, actual);
        verify(executor)
                .updateRoom(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        any(RoomUpdateRequest.class),
                        any(Instant.class));
    }

    @Test
    void 세_번_낙관_락_충돌하면_동시_변경_오류를_반환한다() {
        doThrow(new OptimisticLockException())
                .when(executor)
                .updateRoom(anyLong(), anyLong(), any(RoomUpdateRequest.class), any(Instant.class));

        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> roomUpdateService.updateRoom(42L, 7L, new RoomUpdateRequest()));

        assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
        verify(executor, org.mockito.Mockito.times(3))
                .updateRoom(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        any(RoomUpdateRequest.class),
                        any(Instant.class));
    }

    @Test
    void 첫_낙관_락_충돌_뒤_다음_시도_성공_응답을_반환한다() {
        ParticipantRoomResponse expected = org.mockito.Mockito.mock(ParticipantRoomResponse.class);
        when(executor.updateRoom(
                        anyLong(), anyLong(), any(RoomUpdateRequest.class), any(Instant.class)))
                .thenThrow(new OptimisticLockException())
                .thenReturn(expected);

        ParticipantRoomResponse actual =
                roomUpdateService.updateRoom(42L, 7L, new RoomUpdateRequest());

        assertSame(expected, actual);
        verify(executor, org.mockito.Mockito.times(2))
                .updateRoom(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        any(RoomUpdateRequest.class),
                        org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void ObjectOptimisticLockingFailureException도_재시도한다() {
        ParticipantRoomResponse expected = org.mockito.Mockito.mock(ParticipantRoomResponse.class);
        when(executor.updateRoom(
                        anyLong(), anyLong(), any(RoomUpdateRequest.class), any(Instant.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(RoomUpdateService.class, 7L))
                .thenReturn(expected);

        ParticipantRoomResponse actual =
                roomUpdateService.updateRoom(42L, 7L, new RoomUpdateRequest());

        assertSame(expected, actual);
        verify(executor, org.mockito.Mockito.times(2))
                .updateRoom(
                        org.mockito.ArgumentMatchers.eq(42L),
                        org.mockito.ArgumentMatchers.eq(7L),
                        any(RoomUpdateRequest.class),
                        org.mockito.ArgumentMatchers.eq(NOW));
    }
}
