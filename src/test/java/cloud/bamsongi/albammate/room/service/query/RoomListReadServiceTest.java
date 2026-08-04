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
	void 필터를_생략하면_두_유형의_공개_방을_조회한다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = Page.empty(pageable);
		when(roomRepository.findPublicRooms(
			null, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		roomListReadService.findPublicRooms(criteria(null, null, null), pageable, null);

		verify(roomRepository).findPublicRooms(
			null, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable);
	}

	@Test
	void 비로그인_요청은_방이_있어도_ACTIVE_참가를_조회하지_않는다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = new PageImpl<>(List.of(mock(Room.class)), pageable, 1);
		when(roomRepository.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			criteria(RoomType.PERSON_FOCUSED, null, null), pageable, null);

		assertEquals(Set.of(), result.activeParticipationRoomIds());
		verify(roomRepository).findPublicRooms(
			RoomType.PERSON_FOCUSED, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable);
		verify(roomRepository, never()).findActiveParticipationRoomIds(anyLong(), any());
	}

	@Test
	void 빈_페이지는_로그인_사용자가_있어도_ACTIVE_참가를_조회하지_않는다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = Page.empty(pageable);
		when(roomRepository.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			criteria(RoomType.PERSON_FOCUSED, null, null), pageable, 42L);

		assertEquals(Set.of(), result.activeParticipationRoomIds());
		verify(roomRepository).findPublicRooms(
			RoomType.PERSON_FOCUSED, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable);
		verify(roomRepository, never()).findActiveParticipationRoomIds(anyLong(), any());
	}

	@Test
	void 로그인_사용자의_현재_페이지_방_ID로_ACTIVE_참가를_한번_조회한다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = new PageImpl<>(List.of(room(10L), room(20L)), pageable, 2);
		when(roomRepository.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);
		when(roomRepository.findActiveParticipationRoomIds(42L, List.of(10L, 20L)))
			.thenReturn(List.of(10L));

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			criteria(RoomType.PERSON_FOCUSED, null, null), pageable, 42L);

		assertEquals(Set.of(10L), result.activeParticipationRoomIds());
		verify(roomRepository).findPublicRooms(
			RoomType.PERSON_FOCUSED, null, false, "", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable);
		verify(roomRepository).findActiveParticipationRoomIds(42L, List.of(10L, 20L));
	}

	@Test
	void 검색어가_있으면_제목_검색_Repository_경로를_사용한다() {
		PageRequest pageable = pageable();
		Page<Room> rooms = Page.empty(pageable);
		when(roomRepository.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, true, "모임", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable))
			.thenReturn(rooms);

		roomListReadService.findPublicRooms(criteria(RoomType.PERSON_FOCUSED, null, "모임"), pageable, null);

		verify(roomRepository).findPublicRooms(
			RoomType.PERSON_FOCUSED, null, true, "모임", false, Instant.EPOCH, false, Instant.EPOCH, false, 0,
			Set.of(ExperienceLevel.values()), false, PUBLIC_STATUSES, pageable);
	}

	@Test
	void P1_조건은_하나의_동적_조회에_전달한다() {
		PageRequest pageable = pageable();
		Set<ExperienceLevel> experienceLevels = Set.of(ExperienceLevel.BEGINNER_WELCOME);
		when(roomRepository.findPublicRooms(
			RoomType.PERSON_FOCUSED,
			null,
			true,
			"모임",
			true,
			Instant.parse("2099-01-01T00:00:00Z"),
			true,
			Instant.parse("2099-01-02T00:00:00Z"),
			true,
			2,
			experienceLevels,
			true,
			PUBLIC_STATUSES,
			pageable))
			.thenReturn(Page.empty(pageable));

		roomListReadService.findPublicRooms(
			criteria(
				RoomType.PERSON_FOCUSED,
				null,
				"모임",
				Instant.parse("2099-01-01T00:00:00Z"),
				Instant.parse("2099-01-02T00:00:00Z"),
				2,
				experienceLevels,
				true),
			pageable,
			null);

		verify(roomRepository).findPublicRooms(
			RoomType.PERSON_FOCUSED,
			null,
			true,
			"모임",
			true,
			Instant.parse("2099-01-01T00:00:00Z"),
			true,
			Instant.parse("2099-01-02T00:00:00Z"),
			true,
			2,
			experienceLevels,
			true,
			PUBLIC_STATUSES,
			pageable);
	}

	@Test
	void P1_빈_페이지의_로그인_사용자는_ACTIVE_참가를_조회하지_않는다() {
		PageRequest pageable = pageable();
		Set<ExperienceLevel> allExperienceLevels = Set.of(ExperienceLevel.values());
		when(roomRepository.findPublicRooms(
			null,
			null,
			false,
			"",
			false,
			Instant.EPOCH,
			false,
			Instant.EPOCH,
			false,
			0,
			allExperienceLevels,
			false,
			PUBLIC_STATUSES,
			pageable))
			.thenReturn(Page.empty(pageable));

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			criteria(null, null, null), pageable, 42L);

		assertEquals(Set.of(), result.activeParticipationRoomIds());
		verify(roomRepository, never()).findActiveParticipationRoomIds(anyLong(), any());
	}

	@Test
	void P1_비어있지_않은_페이지의_로그인_사용자는_ACTIVE_참가를_일괄_조회한다() {
		PageRequest pageable = pageable();
		Set<ExperienceLevel> experienceLevels = Set.of(ExperienceLevel.ALL_LEVELS);
		Page<Room> rooms = new PageImpl<>(List.of(room(10L), room(20L)), pageable, 2);
		when(roomRepository.findPublicRooms(
			RoomType.PERSON_FOCUSED,
			null,
			false,
			"",
			false,
			Instant.EPOCH,
			false,
			Instant.EPOCH,
			false,
			0,
			experienceLevels,
			false,
			PUBLIC_STATUSES,
			pageable))
			.thenReturn(rooms);
		when(roomRepository.findActiveParticipationRoomIds(42L, List.of(10L, 20L)))
			.thenReturn(List.of(10L));

		RoomListReadService.RoomListReadResult result = roomListReadService.findPublicRooms(
			criteria(RoomType.PERSON_FOCUSED, null, null, null, null, null, experienceLevels, false),
			pageable,
			42L);

		assertEquals(Set.of(10L), result.activeParticipationRoomIds());
		verify(roomRepository).findActiveParticipationRoomIds(42L, List.of(10L, 20L));
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
	}

	private RoomListSearchCriteria criteria(RoomType roomType, Long gameId, String keyword) {
		return criteria(roomType, gameId, keyword, null, null, null, Set.of(), false);
	}

	private RoomListSearchCriteria criteria(
		RoomType roomType,
		Long gameId,
		String keyword,
		Instant startsAtFrom,
		Instant startsAtTo,
		Integer minRemainingSeats,
		Set<ExperienceLevel> experienceLevels,
		boolean rulemasterOnly) {
		return new RoomListSearchCriteria(
			roomType, gameId, keyword, startsAtFrom, startsAtTo, minRemainingSeats, experienceLevels, rulemasterOnly);
	}

	private Room room(Long id) {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(id);
		return room;
	}
}
