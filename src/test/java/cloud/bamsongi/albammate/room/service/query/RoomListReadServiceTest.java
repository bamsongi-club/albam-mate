package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
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
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@ExtendWith(MockitoExtension.class)
class RoomListReadServiceTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");
	private static final Set<RoomStatus> PUBLIC_STATUSES = Set.of(RoomStatus.RECRUITING, RoomStatus.CLOSED);

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private RoomWaitlistRepository roomWaitlistRepository;

	private RoomListReadService roomListReadService;

	@BeforeEach
	void setUp() {
		roomListReadService = new RoomListReadService(roomRepository, roomWaitlistRepository);
	}

	@Test
	void 필터를_생략하면_요청시각_기준_공개_유효상태_조회에_전달한다() {
		PageRequest pageable = pageable();
		when(roomRepository.findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable))
			.thenReturn(Page.empty(pageable));

		roomListReadService.findPublicRoomsAt(criteria(null, null, null), pageable, null, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void 모집_상태와_P1_조건을_요청시각_기준_동적_조회에_전달한다() {
		PageRequest pageable = pageable();
		Instant startsAtFrom = Instant.parse("2099-01-01T00:00:00Z");
		Instant startsAtTo = Instant.parse("2099-01-02T00:00:00Z");
		Set<ExperienceLevel> experienceLevels = Set.of(ExperienceLevel.BEGINNER_WELCOME);
		RoomListSearchCriteria criteria = criteria(
			RoomType.PERSON_FOCUSED,
			RoomStatus.RECRUITING,
			null,
			"모임",
			startsAtFrom,
			startsAtTo,
			2,
			experienceLevels,
			true);
		when(roomRepository.findPublicRoomsAt(
			RoomType.PERSON_FOCUSED, true, true, false, REQUEST_TIME, null, true, "모임", true, startsAtFrom,
			true, startsAtTo, true, 2, experienceLevels, true, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable))
			.thenReturn(Page.empty(pageable));

		roomListReadService.findPublicRoomsAt(criteria, pageable, null, REQUEST_TIME);

		verify(roomRepository).findPublicRoomsAt(
			RoomType.PERSON_FOCUSED, true, true, false, REQUEST_TIME, null, true, "모임", true, startsAtFrom,
			true, startsAtTo, true, 2, experienceLevels, true, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable);
	}

	@Test
	void 비로그인_또는_빈_페이지는_현재_참가와_대기_관계를_조회하지_않는다() {
		PageRequest pageable = pageable();
		when(roomRepository.findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable))
			.thenReturn(Page.empty(pageable));

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRoomsAt(
			criteria(null, null, null), pageable, 42L, REQUEST_TIME);

		assertEquals(Set.of(), result.activeParticipationRoomIds());
		assertEquals(Set.of(), result.waitingRoomIds());
		verify(roomRepository, never()).findActiveParticipationRoomIds(anyLong(), any());
		verify(roomWaitlistRepository, never()).findWaitingRoomIdsByUserIdAndRoomIds(anyLong(), any());
	}

	@Test
	void 로그인_사용자의_현재_페이지_참가와_대기_관계를_한번에_읽는다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = new PageImpl<>(List.of(room(10L), room(20L)), pageable, 2);
		when(roomRepository.findPublicRoomsAt(
			null, false, false, false, REQUEST_TIME, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH,
			false, 0, Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES,
			REQUEST_TIME.minus(Room.AUTOMATIC_FINISH_AFTER_START), pageable))
			.thenReturn(rooms);
		when(roomRepository.findActiveParticipationRoomIds(42L, List.of(10L, 20L))).thenReturn(List.of(10L));
		when(roomWaitlistRepository.findWaitingRoomIdsByUserIdAndRoomIds(42L, List.of(10L, 20L)))
			.thenReturn(List.of(20L));

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRoomsAt(
			criteria(null, null, null), pageable, 42L, REQUEST_TIME);

		assertEquals(Set.of(10L), result.activeParticipationRoomIds());
		assertEquals(Set.of(20L), result.waitingRoomIds());
		verify(roomRepository).findActiveParticipationRoomIds(42L, List.of(10L, 20L));
		verify(roomWaitlistRepository).findWaitingRoomIdsByUserIdAndRoomIds(42L, List.of(10L, 20L));
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
	}

	private RoomListSearchCriteria criteria(RoomType roomType, RoomStatus status, String keyword) {
		return criteria(roomType, status, null, keyword, null, null, null, Set.of(), false);
	}

	private RoomListSearchCriteria criteria(
		RoomType roomType,
		RoomStatus status,
		Long gameId,
		String keyword,
		Instant startsAtFrom,
		Instant startsAtTo,
		Integer minRemainingSeats,
		Set<ExperienceLevel> experienceLevels,
		boolean rulemasterOnly) {
		return new RoomListSearchCriteria(
			roomType, status, gameId, keyword, startsAtFrom, startsAtTo, minRemainingSeats, experienceLevels,
			rulemasterOnly);
	}

	private Room room(Long id) {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(id);
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getStartAt()).thenReturn(REQUEST_TIME.plusSeconds(60));
		return room;
	}
}
