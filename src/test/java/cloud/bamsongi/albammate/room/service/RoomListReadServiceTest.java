package cloud.bamsongi.albammate.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

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
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@ExtendWith(MockitoExtension.class)
class RoomListReadServiceTest {

	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

	@Mock
	private RoomRepository roomRepository;

	private RoomListReadService roomListReadService;

	@BeforeEach
	void setUp() {
		roomListReadService = new RoomListReadService(roomRepository);
	}

	@Test
	void 비로그인_요청은_방이_있어도_ACTIVE_참가를_조회하지_않는다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = new PageImpl<>(List.of(mock(Room.class)), pageable, 1);
		when(roomRepository.findPublicRoomsWithoutKeyword(
			RoomType.PERSON_FOCUSED, null, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, null, pageable, null);

		assertEquals(Set.of(), result.activeParticipationRoomIds());
		verify(roomRepository)
			.findPublicRoomsWithoutKeyword(
				RoomType.PERSON_FOCUSED, null, PUBLIC_STATUSES, pageable);
		verify(roomRepository, never()).findActiveParticipationRoomIds(anyLong(), any());
	}

	@Test
	void 빈_페이지는_로그인_사용자가_있어도_ACTIVE_참가를_조회하지_않는다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = Page.empty(pageable);
		when(roomRepository.findPublicRoomsWithoutKeyword(
			RoomType.PERSON_FOCUSED, null, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, null, pageable, 42L);

		assertEquals(Set.of(), result.activeParticipationRoomIds());
		verify(roomRepository)
			.findPublicRoomsWithoutKeyword(
				RoomType.PERSON_FOCUSED, null, PUBLIC_STATUSES, pageable);
		verify(roomRepository, never()).findActiveParticipationRoomIds(anyLong(), any());
	}

	@Test
	void 로그인_사용자의_현재_페이지_방_ID로_ACTIVE_참가를_한번_조회한다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = new PageImpl<>(List.of(room(10L), room(20L)), pageable, 2);
		when(roomRepository.findPublicRoomsWithoutKeyword(
			RoomType.PERSON_FOCUSED, null, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);
		when(roomRepository.findActiveParticipationRoomIds(42L, List.of(10L, 20L)))
			.thenReturn(List.of(10L));

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, null, pageable, 42L);

		assertEquals(Set.of(10L), result.activeParticipationRoomIds());
		verify(roomRepository)
			.findPublicRoomsWithoutKeyword(
				RoomType.PERSON_FOCUSED, null, PUBLIC_STATUSES, pageable);
		verify(roomRepository).findActiveParticipationRoomIds(42L, List.of(10L, 20L));
	}

	@Test
	void 검색어가_있으면_제목_검색_Repository_경로를_사용한다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = Page.empty(pageable);
		when(roomRepository.findPublicRoomsByTitleContainingIgnoreCase(
			RoomType.PERSON_FOCUSED, null, "모임", PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		roomListReadService.findPublicRooms(RoomType.PERSON_FOCUSED, null, "모임", pageable, null);

		verify(roomRepository)
			.findPublicRoomsByTitleContainingIgnoreCase(
				RoomType.PERSON_FOCUSED, null, "모임", PUBLIC_STATUSES, pageable);
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
	}

	private Room room(Long id) {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(id);
		return room;
	}
}
