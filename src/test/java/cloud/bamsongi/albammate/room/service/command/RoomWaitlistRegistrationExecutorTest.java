package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@ExtendWith(MockitoExtension.class)
class RoomWaitlistRegistrationExecutorTest {

	private static final long CURRENT_USER_ID = 7L;
	private static final long ROOM_ID = 10L;
	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");

	@Mock
	private RoomRepository roomRepository;

	@Mock
	private ParticipationRepository participationRepository;

	@Mock
	private RoomWaitlistRepository roomWaitlistRepository;

	@Test
	void T5_ROOM_버전_선점_실패는_대기_순번_발급_전에_낙관_락_예외로_끝난다() {
		Room room = createFullRoom();
		when(roomRepository.findByIdForWrite(ROOM_ID)).thenReturn(Optional.of(room));
		when(participationRepository.findByRoomIdAndUserId(ROOM_ID, CURRENT_USER_ID)).thenReturn(Optional.empty());
		when(roomWaitlistRepository.findStateWithPositionByRoomIdAndUserId(ROOM_ID, CURRENT_USER_ID))
			.thenReturn(Optional.empty());
		when(roomRepository.claimVersion(ROOM_ID, room.getVersion())).thenReturn(0);

		RoomWaitlistRegistrationExecutor executor = new RoomWaitlistRegistrationExecutor(
			roomRepository, participationRepository, roomWaitlistRepository, new MockEnvironment());

		assertThrows(ObjectOptimisticLockingFailureException.class,
			() -> executor.register(CURRENT_USER_ID, ROOM_ID, REQUEST_TIME));

		verify(roomWaitlistRepository, never()).getNextQueueOrder();
	}

	private Room createFullRoom() {
		Room room = Room.create(1L, RoomType.PERSON_FOCUSED, "마감 방", null, null,
			ExperienceLevel.ALL_LEVELS, false, REQUEST_TIME.plusSeconds(60), "홍대", 1);
		room.addActiveParticipant();
		return room;
	}
}
