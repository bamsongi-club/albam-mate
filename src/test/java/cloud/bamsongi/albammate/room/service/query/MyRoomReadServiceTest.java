package cloud.bamsongi.albammate.room.service.query;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@ExtendWith(MockitoExtension.class)
class MyRoomReadServiceTest {

	@Mock
	private RoomRepository roomRepository;

	private MyRoomReadService myRoomReadService;

	@BeforeEach
	void setUp() {
		myRoomReadService = new MyRoomReadService(roomRepository);
	}

	@Test
	void 역할별로_주최와_ACTIVE_참가_범위를_요청시각_조회에_전달한다() {
		PageRequest pageable = pageable();
		Page<Room> result = new PageImpl<>(List.of(), pageable, 0);
		Instant requestTime = Instant.parse("2026-07-28T00:00:00Z");
		when(roomRepository.findMyRoomsAt(42L, true, true, pageable)).thenReturn(result);
		when(roomRepository.findMyRoomsAt(42L, false, true, pageable)).thenReturn(result);
		when(roomRepository.findMyRoomsAt(42L, true, false, pageable)).thenReturn(result);

		myRoomReadService.findMyRoomsAt(42L, MyRoomRole.ALL, pageable, requestTime);
		myRoomReadService.findMyRoomsAt(42L, MyRoomRole.JOINED, pageable, requestTime);
		myRoomReadService.findMyRoomsAt(42L, MyRoomRole.HOSTED, pageable, requestTime);

		verify(roomRepository).findMyRoomsAt(42L, true, true, pageable);
		verify(roomRepository).findMyRoomsAt(42L, false, true, pageable);
		verify(roomRepository).findMyRoomsAt(42L, true, false, pageable);
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
	}
}
