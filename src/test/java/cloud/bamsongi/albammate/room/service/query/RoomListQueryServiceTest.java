package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;

@ExtendWith(MockitoExtension.class)
class RoomListQueryServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	@Mock
	private RoomListReadService roomListReadService;
	@Mock
	private GameQuery gameQuery;

	private RoomListQueryService roomListQueryService;

	@BeforeEach
	void setUp() {
		roomListQueryService = new RoomListQueryService(
			statusCorrectionCoordinator,
			roomListReadService,
			gameQuery,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void 필터를_생략하면_두_유형의_공개_방을_조회한다() {
		PageRequest pageable = pageable();
		when(roomListReadService.findPublicRooms(null, null, null, pageable, null))
			.thenReturn(readResult(List.of(), pageable, Set.of()));

		roomListQueryService.findPage(null, null, null, 0, 10, Optional.empty());

		verify(roomListReadService).findPublicRooms(null, null, null, pageable, null);
	}

	@Test
	void 상태_보정_후_같은_요청시각으로_공개_게임방을_페이지_응답으로_조립한다() {
		Room room = room(1L, 7L, 42L, RoomStatus.RECRUITING, 1, 3, NOW.plusSeconds(60));
		PageRequest pageable = pageable();
		when(roomListReadService.findPublicRooms(RoomType.GAME_FOCUSED, 7L, null, pageable, 99L))
			.thenReturn(readResult(List.of(room), pageable, Set.of()));
		GameSummary game = new GameSummary(7L, 1007L, "카탄");
		when(gameQuery.findSummariesByIds(Set.of(7L))).thenReturn(Map.of(7L, game));

		var response = roomListQueryService.findPage(
			RoomType.GAME_FOCUSED, 7L, null, 0, 10, Optional.of(99L));

		assertEquals("보드게임 모임", response.content().getFirst().title());
		assertEquals(game, response.content().getFirst().game());
		assertEquals(2, response.content().getFirst().participantCount());
		assertEquals(2, response.content().getFirst().remainingRecruitmentSeats());
		assertTrue(response.content().getFirst().joinable());
		assertEquals(1, response.totalElements());
		verify(gameQuery).findSummariesByIds(Set.of(7L));
		InOrder inOrder = inOrder(statusCorrectionCoordinator, roomListReadService);
		inOrder.verify(statusCorrectionCoordinator).correctDueRooms(NOW);
		inOrder.verify(roomListReadService)
			.findPublicRooms(RoomType.GAME_FOCUSED, 7L, null, pageable, 99L);
	}

	@Test
	void 사람_중심_검색어는_strip하고_빈_검색어는_검색하지_않는다() {
		PageRequest pageable = pageable();
		when(roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, "모임", pageable, null))
			.thenReturn(readResult(List.of(), pageable, Set.of()));
		when(roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, null, pageable, null))
			.thenReturn(readResult(List.of(), pageable, Set.of()));

		roomListQueryService.findPage(
			RoomType.PERSON_FOCUSED, null, "\u3000모임\u3000", 0, 10, Optional.empty());
		roomListQueryService.findPage(
			RoomType.PERSON_FOCUSED, null, "   ", 0, 10, Optional.empty());

		verify(roomListReadService)
			.findPublicRooms(RoomType.PERSON_FOCUSED, null, "모임", pageable, null);
		verify(roomListReadService)
			.findPublicRooms(RoomType.PERSON_FOCUSED, null, null, pageable, null);
	}

	@Test
	void 사람_중심_검색어의_LIKE_예약문자를_리터럴_문자로_이스케이프한다() {
		PageRequest pageable = pageable();
		when(roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, "!%!!!_", pageable, null))
			.thenReturn(readResult(List.of(), pageable, Set.of()));

		roomListQueryService.findPage(
			RoomType.PERSON_FOCUSED, null, "%!_", 0, 10, Optional.empty());

		verify(roomListReadService)
			.findPublicRooms(RoomType.PERSON_FOCUSED, null, "!%!!!_", pageable, null);
	}

	@Test
	void 페이지의_서로_다른_게임은_한번의_bulk_조회로_요약한다() {
		PageRequest pageable = pageable();
		Room first = room(1L, 7L, 42L, RoomStatus.RECRUITING, 0, 3, NOW.plusSeconds(60));
		Room second = room(2L, 7L, 43L, RoomStatus.RECRUITING, 0, 3, NOW.plusSeconds(120));
		Room third = room(3L, 8L, 44L, RoomStatus.RECRUITING, 0, 3, NOW.plusSeconds(180));
		when(roomListReadService.findPublicRooms(RoomType.GAME_FOCUSED, 7L, null, pageable, 99L))
			.thenReturn(readResult(List.of(first, second, third), pageable, Set.of()));
		when(gameQuery.findSummariesByIds(Set.of(7L, 8L)))
			.thenReturn(
				Map.of(
					7L, new GameSummary(7L, 1007L, "카탄"),
					8L, new GameSummary(8L, 1008L, "아줄")));

		roomListQueryService.findPage(RoomType.GAME_FOCUSED, 7L, null, 0, 10, Optional.of(99L));

		verify(gameQuery, times(1)).findSummariesByIds(Set.of(7L, 8L));
	}

	@Test
	void 없는_게임_요약은_기존처럼_GAME_NOT_FOUND다() {
		PageRequest pageable = pageable();
		Room room = mock(Room.class);
		when(room.getGameId()).thenReturn(999L);
		when(roomListReadService.findPublicRooms(RoomType.GAME_FOCUSED, 999L, null, pageable, null))
			.thenReturn(readResult(List.of(room), pageable, Set.of()));
		when(gameQuery.findSummariesByIds(Set.of(999L))).thenReturn(Map.of());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomListQueryService.findPage(
				RoomType.GAME_FOCUSED,
				999L,
				null,
				0,
				10,
				Optional.empty()));

		assertEquals(ErrorCode.GAME_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 익명_주최자_ACTIVE_참가자는_참가할_수_없고_CANCELED_참가자는_다시_참가할_수_있다() {
		Room room = room(1L, null, 42L, RoomStatus.RECRUITING, 0, 3, NOW.plusSeconds(60));
		PageRequest pageable = pageable();
		when(roomListReadService.findPublicRooms(
			eq(RoomType.PERSON_FOCUSED), eq(null), eq(null), eq(pageable), eq(null)))
			.thenReturn(readResult(List.of(room), pageable, Set.of()));
		when(roomListReadService.findPublicRooms(
			eq(RoomType.PERSON_FOCUSED), eq(null), eq(null), eq(pageable), eq(42L)))
			.thenReturn(readResult(List.of(room), pageable, Set.of()));
		when(roomListReadService.findPublicRooms(
			eq(RoomType.PERSON_FOCUSED), eq(null), eq(null), eq(pageable), eq(99L)))
			.thenReturn(readResult(List.of(room), pageable, Set.of(1L)));
		when(roomListReadService.findPublicRooms(
			eq(RoomType.PERSON_FOCUSED), eq(null), eq(null), eq(pageable), eq(100L)))
			.thenReturn(readResult(List.of(room), pageable, Set.of()));

		assertFalse(
			roomListQueryService
				.findPage(RoomType.PERSON_FOCUSED, null, null, 0, 10, Optional.empty())
				.content()
				.getFirst()
				.joinable());
		assertFalse(
			roomListQueryService
				.findPage(RoomType.PERSON_FOCUSED, null, null, 0, 10, Optional.of(42L))
				.content()
				.getFirst()
				.joinable());
		assertFalse(
			roomListQueryService
				.findPage(RoomType.PERSON_FOCUSED, null, null, 0, 10, Optional.of(99L))
				.content()
				.getFirst()
				.joinable());
		assertTrue(
			roomListQueryService
				.findPage(RoomType.PERSON_FOCUSED, null, null, 0, 10, Optional.of(100L))
				.content()
				.getFirst()
				.joinable());
		verify(gameQuery, never()).findSummariesByIds(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void 정원이_찼거나_시작시각과_같으면_참가할_수_없다() {
		PageRequest pageable = pageable();
		Room full = room(1L, null, 42L, RoomStatus.RECRUITING, 3, 3, NOW.plusSeconds(1));
		Room started = room(2L, null, 42L, RoomStatus.RECRUITING, 0, 3, NOW);
		when(roomListReadService.findPublicRooms(
			RoomType.PERSON_FOCUSED, null, null, pageable, 99L))
			.thenReturn(readResult(List.of(full, started), pageable, Set.of()));

		var content = roomListQueryService
			.findPage(RoomType.PERSON_FOCUSED, null, null, 0, 10, Optional.of(99L))
			.content();

		assertFalse(content.get(0).joinable());
		assertFalse(content.get(1).joinable());
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.asc("startAt"), Sort.Order.asc("id")));
	}

	private RoomListReadService.RoomListReadResult readResult(
		List<Room> rooms, PageRequest pageable, Set<Long> activeParticipationRoomIds) {
		return new RoomListReadService.RoomListReadResult(
			new PageImpl<>(rooms, pageable, rooms.size()), activeParticipationRoomIds);
	}

	private Room room(
		Long id,
		Long gameId,
		Long hostUserId,
		RoomStatus status,
		int activeParticipantCount,
		int capacity,
		Instant startsAt) {
		Room room = mock(Room.class);
		when(room.getId()).thenReturn(id);
		when(room.getGameId()).thenReturn(gameId);
		when(room.getHostUserId()).thenReturn(hostUserId);
		when(room.getRoomType())
			.thenReturn(gameId == null ? RoomType.PERSON_FOCUSED : RoomType.GAME_FOCUSED);
		when(room.getTitle()).thenReturn("보드게임 모임");
		when(room.getDescription()).thenReturn(null);
		when(room.getExperienceLevel()).thenReturn(ExperienceLevel.ALL_LEVELS);
		when(room.isRulemasterLed()).thenReturn(false);
		when(room.getStartAt()).thenReturn(startsAt);
		when(room.getRegion()).thenReturn("홍대");
		when(room.getCapacity()).thenReturn(capacity);
		when(room.getActiveParticipantCount()).thenReturn(activeParticipantCount);
		when(room.getStatus()).thenReturn(status);
		return room;
	}
}
