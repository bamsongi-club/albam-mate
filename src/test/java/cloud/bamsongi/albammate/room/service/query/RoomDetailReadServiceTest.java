package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@ExtendWith(MockitoExtension.class)
class RoomDetailReadServiceTest {

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private ParticipationRepository participationRepository;

	private RoomDetailReadService roomDetailReadService;

	@BeforeEach
	void setUp() {
		roomDetailReadService = new RoomDetailReadService(roomRepository, participationRepository);
	}

	@Test
	void 방과_joinedAt_ID_순서의_ACTIVE_참가관계를_함께_읽는다() {
		Room room = org.mockito.Mockito.mock(Room.class);
		Participation first = org.mockito.Mockito.mock(Participation.class);
		Participation second = org.mockito.Mockito.mock(Participation.class);
		when(roomRepository.findById(7L)).thenReturn(Optional.of(room));
		when(participationRepository.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			7L, ParticipationStatus.ACTIVE))
			.thenReturn(List.of(first, second));

		RoomDetailReadService.RoomDetailReadResult result = roomDetailReadService.findRoomDetail(7L);

		assertSame(room, result.room());
		assertEquals(List.of(first, second), result.activeParticipations());
		verify(roomRepository).findById(7L);
		verify(participationRepository)
			.findByRoomIdAndStatusOrderByJoinedAtAscIdAsc(7L, ParticipationStatus.ACTIVE);
	}
}
