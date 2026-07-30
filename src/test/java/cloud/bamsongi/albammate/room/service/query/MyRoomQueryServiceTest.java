package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;

@ExtendWith(MockitoExtension.class)
class MyRoomQueryServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	@Mock
	private MyRoomReadService myRoomReadService;
	@Mock
	private GameQuery gameQuery;

	private MyRoomQueryService myRoomQueryService;

	@BeforeEach
	void setUp() {
		myRoomQueryService = new MyRoomQueryService(
			statusCorrectionCoordinator,
			myRoomReadService,
			gameQuery,
			Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void 상태_보정_커밋_후_내림차순_페이지와_게임_요약을_조립한다() {
		PageRequest pageable = pageable();
		Room hosted = room(1L, 7L, 42L, 1, 3);
		Room joined = room(2L, 8L, 99L, 2, 3);
		when(myRoomReadService.findMyRooms(42L, MyRoomRole.ALL, pageable))
			.thenReturn(new PageImpl<>(List.of(hosted, joined), pageable, 2));
		when(gameQuery.findSummariesByIds(Set.of(7L, 8L)))
			.thenReturn(
				Map.of(
					7L, new GameSummary(7L, 1007L, "카탄"),
					8L, new GameSummary(8L, 1008L, "아줄")));

		var response = myRoomQueryService.findPage(42L, MyRoomRole.ALL, 0, 10);

		assertEquals(MyRole.HOST, response.content().get(0).myRole());
		assertNull(response.content().get(0).participationStatus());
		assertEquals(MyRole.JOINED, response.content().get(1).myRole());
		assertEquals(ParticipationStatus.ACTIVE, response.content().get(1).participationStatus());
		assertFalse(response.content().get(0).joinable());
		assertFalse(response.content().get(1).joinable());
		assertEquals(3, response.content().get(1).participantCount());
		assertEquals(1, response.content().get(1).remainingRecruitmentSeats());
		verify(gameQuery).findSummariesByIds(Set.of(7L, 8L));
		InOrder inOrder = inOrder(statusCorrectionCoordinator, myRoomReadService);
		inOrder.verify(statusCorrectionCoordinator).correctDueRooms(NOW);
		inOrder.verify(myRoomReadService).findMyRooms(42L, MyRoomRole.ALL, pageable);
	}

	private PageRequest pageable() {
		return PageRequest.of(0, 10, Sort.by(Sort.Order.desc("startAt"), Sort.Order.desc("id")));
	}

	private Room room(
		Long id, Long gameId, Long hostUserId, int activeParticipantCount, int capacity) {
		Room room = org.mockito.Mockito.mock(Room.class);
		when(room.getId()).thenReturn(id);
		when(room.getGameId()).thenReturn(gameId);
		when(room.getHostUserId()).thenReturn(hostUserId);
		when(room.getRoomType()).thenReturn(RoomType.GAME_FOCUSED);
		when(room.getTitle()).thenReturn("내 모임");
		when(room.getDescription()).thenReturn(null);
		when(room.getExperienceLevel()).thenReturn(ExperienceLevel.ALL_LEVELS);
		when(room.isRulemasterLed()).thenReturn(false);
		when(room.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(room.getRegion()).thenReturn("홍대");
		when(room.getCapacity()).thenReturn(capacity);
		when(room.getActiveParticipantCount()).thenReturn(activeParticipantCount);
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		return room;
	}
}
