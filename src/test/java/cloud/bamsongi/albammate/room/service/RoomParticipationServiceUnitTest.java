package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.RoomStateReconciliationCoordinator;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoomParticipationServiceUnitTest {

    @Mock private RoomRepository roomRepository;
    @Mock private ParticipationRepository participationRepository;
    @Mock private RoomStateReconciliationCoordinator roomStateReconciliationCoordinator;
    @Mock private Room room;

    @Test
    void 취소되었거나_종료된_방에는_ROOM_NOT_RECRUITING을_반환한다() {
        long roomId = 7L;
        long userId = 42L;
        RoomParticipationService service =
                new RoomParticipationService(
                        roomRepository,
                        participationRepository,
                        roomStateReconciliationCoordinator,
                        Clock.fixed(Instant.parse("2026-07-28T00:00:00Z"), ZoneOffset.UTC));
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(participationRepository.findByRoomIdAndUserId(roomId, userId))
                .thenReturn(Optional.empty());

        for (RoomStatus status : List.of(RoomStatus.CANCELED, RoomStatus.FINISHED)) {
            when(room.getStatus()).thenReturn(status);

            BusinessException exception =
                    assertThrows(
                            BusinessException.class, () -> service.participate(userId, roomId));

            assertEquals(ErrorCode.ROOM_NOT_RECRUITING, exception.getErrorCode());
        }
    }
}
