package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomDetailResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.service.RoomActionAvailabilityEvaluator;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import cloud.bamsongi.albammate.user.contract.UserQuery;

@ExtendWith(MockitoExtension.class)
class RoomDetailServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	@Mock
	private RoomDetailReadService roomDetailReadService;
	@Mock
	private GameQuery gameQuery;
	@Mock
	private UserQuery userQuery;

	private RoomDetailService roomDetailService;

	@BeforeEach
	void setUp() {
		roomDetailService = new RoomDetailService(
			statusCorrectionCoordinator,
			roomDetailReadService,
			gameQuery,
			userQuery,
			Clock.fixed(NOW, ZoneOffset.UTC),
			new RoomActionAvailabilityEvaluator());
	}

	@Test
	void 상태_보정_후_동일한_요청시각의_공개_상세를_반환한다() {
		Room room = room(7L, 42L, null, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		when(roomDetailReadService.findRoomDetail(7L, null)).thenReturn(readResult(room, List.of(), false));

		RoomDetailResponse result = roomDetailService.findRoomDetail(7L, Optional.empty());

		PublicRoomResponse response = assertInstanceOf(PublicRoomResponse.class, result);
		assertEquals(1, response.participantCount());
		assertEquals(3, response.remainingRecruitmentSeats());
		assertFalse(response.joinable());
		InOrder inOrder = inOrder(statusCorrectionCoordinator, roomDetailReadService);
		inOrder.verify(statusCorrectionCoordinator).correctRoom(7L, NOW);
		inOrder.verify(roomDetailReadService).findRoomDetail(7L, null);
		verify(gameQuery, never()).findSummaryById(org.mockito.ArgumentMatchers.anyLong());
		verifyNoInteractions(userQuery);
	}

	@Test
	void 관계없는_로그인_사용자는_모집중_빈자리에_참가할_수_있다() {
		Room room = room(7L, 42L, null, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		when(roomDetailReadService.findRoomDetail(7L, 99L)).thenReturn(readResult(room, List.of(), false));

		PublicRoomResponse response = assertInstanceOf(
			PublicRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(99L)));

		assertTrue(response.joinable());
	}

	@Test
	void CANCELED_참가관계는_ACTIVE_목록에_없으므로_재참가할_수_있다() {
		Room room = room(7L, 42L, null, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		when(roomDetailReadService.findRoomDetail(7L, 77L)).thenReturn(readResult(room, List.of(), false));

		PublicRoomResponse response = assertInstanceOf(
			PublicRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(77L)));

		assertTrue(response.joinable());
	}

	@Test
	void 주최자는_정확한_장소와_HOST_역할_및_ACTIVE_참가자_목록을_받는다() {
		Room room = room(7L, 42L, 100L, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		when(room.getTotalParticipantCount()).thenReturn(3);
		when(room.getRemainingRecruitmentSeats()).thenReturn(1);
		Participation firstParticipation = participation(77L);
		Participation secondParticipation = participation(88L);
		when(roomDetailReadService.findRoomDetail(7L, 42L))
			.thenReturn(readResult(room, List.of(firstParticipation, secondParticipation), false));
		when(gameQuery.findSummaryById(100L))
			.thenReturn(Optional.of(new GameSummary(100L, 1100L, "카탄")));
		when(userQuery.findNicknamesByIds(List.of(42L, 77L, 88L)))
			.thenReturn(Map.of(88L, "두 번째 참가자", 42L, "방장", 77L, "첫 번째 참가자"));

		ParticipantRoomResponse response = assertInstanceOf(
			ParticipantRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(42L)));

		assertEquals(MyRole.HOST, response.myRole());
		assertEquals("정확한 장소", response.place());
		assertEquals("방장", response.host().nickname());
		assertEquals(
			List.of("방장", "첫 번째 참가자", "두 번째 참가자"),
			response.participants().stream().map(p -> p.nickname()).toList());
		assertEquals(3, response.participantCount());
		assertEquals(1, response.remainingRecruitmentSeats());
		verify(userQuery).findNicknamesByIds(List.of(42L, 77L, 88L));
		verify(userQuery, never()).findNicknameById(org.mockito.ArgumentMatchers.anyLong());
		verifyNoMoreInteractions(userQuery);
	}

	@Test
	void 상세의_Game_User_조회와_DTO_조립은_ReadService_반환_뒤에_수행한다() {
		Room room = room(7L, 42L, 100L, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		when(roomDetailReadService.findRoomDetail(7L, 42L)).thenReturn(readResult(room, List.of(), false));
		when(gameQuery.findSummaryById(100L)).thenReturn(Optional.of(new GameSummary(100L, 1100L, "카탄")));
		when(userQuery.findNicknamesByIds(List.of(42L))).thenReturn(Map.of(42L, "방장"));

		roomDetailService.findRoomDetail(7L, Optional.of(42L));

		InOrder inOrder = inOrder(roomDetailReadService, gameQuery, userQuery);
		inOrder.verify(roomDetailReadService).findRoomDetail(7L, 42L);
		inOrder.verify(gameQuery).findSummaryById(100L);
		inOrder.verify(userQuery).findNicknamesByIds(List.of(42L));
	}

	@Test
	void CANCELED_방의_주최자는_관계자_상세를_받는다() {
		Room room = room(7L, 42L, null, RoomStatus.CANCELED, 3, NOW.plusSeconds(60));
		Participation participation = participation(77L);
		when(roomDetailReadService.findRoomDetail(7L, 42L))
			.thenReturn(readResult(room, List.of(participation), false));
		when(userQuery.findNicknamesByIds(List.of(42L, 77L)))
			.thenReturn(Map.of(42L, "방장", 77L, "참가자"));

		ParticipantRoomResponse response = assertInstanceOf(
			ParticipantRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(42L)));

		assertEquals(MyRole.HOST, response.myRole());
		assertEquals("정확한 장소", response.place());
		assertEquals("방장", response.host().nickname());
		assertEquals(
			List.of("방장", "참가자"),
			response.participants().stream().map(p -> p.nickname()).toList());
		assertFalse(response.joinable());
	}

	@Test
	void ACTIVE_참가자는_JOINED_역할을_받고_사람중심_방은_게임이_null이다() {
		Room room = room(7L, 42L, null, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		Participation participation = participation(77L);
		when(roomDetailReadService.findRoomDetail(7L, 77L))
			.thenReturn(readResult(room, List.of(participation), true, false));
		when(userQuery.findNicknamesByIds(List.of(42L, 77L)))
			.thenReturn(Map.of(42L, "방장", 77L, "참가자"));

		ParticipantRoomResponse response = assertInstanceOf(
			ParticipantRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(77L)));

		assertEquals(MyRole.JOINED, response.myRole());
		assertNull(response.game());
		assertFalse(response.joinable());
		verify(gameQuery, never()).findSummaryById(org.mockito.ArgumentMatchers.anyLong());
	}

	@Test
	void ReadResult의_현재_ACTIVE_사실로_참가자_상세를_판정한다() {
		Room room = room(7L, 42L, null, RoomStatus.RECRUITING, 3, NOW.plusSeconds(60));
		Participation activeParticipation = participation(88L);
		when(roomDetailReadService.findRoomDetail(7L, 77L))
			.thenReturn(readResult(room, List.of(activeParticipation), true, false));
		when(userQuery.findNicknamesByIds(List.of(42L, 88L)))
			.thenReturn(Map.of(42L, "방장", 88L, "다른 참가자"));

		ParticipantRoomResponse response = assertInstanceOf(
			ParticipantRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(77L)));

		assertEquals(MyRole.JOINED, response.myRole());
		assertEquals(List.of("방장", "다른 참가자"),
			response.participants().stream().map(participant -> participant.nickname()).toList());
	}

	@Test
	void FINISHED_방의_ACTIVE_참가자는_관계자_상세를_받는다() {
		Room room = room(7L, 42L, null, RoomStatus.FINISHED, 3, NOW.plusSeconds(60));
		Participation participation = participation(77L);
		when(roomDetailReadService.findRoomDetail(7L, 77L))
			.thenReturn(readResult(room, List.of(participation), true, false));
		when(userQuery.findNicknamesByIds(List.of(42L, 77L)))
			.thenReturn(Map.of(42L, "방장", 77L, "참가자"));

		ParticipantRoomResponse response = assertInstanceOf(
			ParticipantRoomResponse.class,
			roomDetailService.findRoomDetail(7L, Optional.of(77L)));

		assertEquals(MyRole.JOINED, response.myRole());
		assertEquals("정확한 장소", response.place());
		assertEquals("방장", response.host().nickname());
		assertEquals(
			List.of("방장", "참가자"),
			response.participants().stream().map(p -> p.nickname()).toList());
		assertFalse(response.joinable());
	}

	@Test
	void 관계자_닉네임_결과에_필요한_사용자가_없으면_내부_오류다() {
		Room room = mock(Room.class);
		when(room.getHostUserId()).thenReturn(42L);
		when(room.getGameId()).thenReturn(null);
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		Participation participation = participation(77L);
		when(roomDetailReadService.findRoomDetail(7L, 42L))
			.thenReturn(readResult(room, List.of(participation), false));
		when(userQuery.findNicknamesByIds(List.of(42L, 77L))).thenReturn(Map.of(42L, "방장"));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomDetailService.findRoomDetail(7L, Optional.of(42L)));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
	}

	@Test
	void 최종_상태_방은_관계없는_사용자에게_존재를_숨긴다() {
		Room room = mock(Room.class);
		when(room.getHostUserId()).thenReturn(42L);
		when(room.getStatus()).thenReturn(RoomStatus.CANCELED);
		when(roomDetailReadService.findRoomDetail(7L, 99L)).thenReturn(readResult(room, List.of(), false));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomDetailService.findRoomDetail(7L, Optional.of(99L)));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(gameQuery, userQuery);
	}

	@Test
	void CANCELED와_FINISHED_방의_비로그인_비ACTIVE_요청은_GAME과_닉네임을_조회하지_않는다() {
		assertFinalRoomIsHidden(RoomStatus.CANCELED, Optional.empty());
		assertFinalRoomIsHidden(RoomStatus.CANCELED, Optional.of(99L));
		assertFinalRoomIsHidden(RoomStatus.FINISHED, Optional.empty());
		assertFinalRoomIsHidden(RoomStatus.FINISHED, Optional.of(99L));
	}

	private void assertFinalRoomIsHidden(RoomStatus status, Optional<Long> currentUserId) {
		Room room = mock(Room.class);
		when(room.getHostUserId()).thenReturn(42L);
		when(room.getStatus()).thenReturn(status);
		when(roomDetailReadService.findRoomDetail(7L, currentUserId.orElse(null)))
			.thenReturn(readResult(room, List.of(), false));

		BusinessException exception = assertThrows(
			BusinessException.class, () -> roomDetailService.findRoomDetail(7L, currentUserId));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
		verifyNoInteractions(gameQuery, userQuery);
		clearInvocations(gameQuery, userQuery);
	}

	private RoomDetailReadService.RoomDetailReadResult readResult(
		Room room, List<Participation> activeParticipations, boolean currentUserWaiting) {
		return readResult(room, activeParticipations, false, currentUserWaiting);
	}

	private RoomDetailReadService.RoomDetailReadResult readResult(
		Room room,
		List<Participation> activeParticipations,
		boolean currentUserIsActiveParticipant,
		boolean currentUserWaiting) {
		return new RoomDetailReadService.RoomDetailReadResult(
			room, activeParticipations, currentUserIsActiveParticipant, currentUserWaiting);
	}

	private Participation participation(long userId) {
		Participation participation = org.mockito.Mockito.mock(Participation.class);
		when(participation.getUserId()).thenReturn(userId);
		return participation;
	}

	private Room room(
		long roomId,
		long hostUserId,
		Long gameId,
		RoomStatus status,
		int capacity,
		Instant startsAt) {
		Room room = org.mockito.Mockito.mock(Room.class);
		when(room.getId()).thenReturn(roomId);
		when(room.getHostUserId()).thenReturn(hostUserId);
		when(room.getGameId()).thenReturn(gameId);
		when(room.getRoomType())
			.thenReturn(gameId == null ? RoomType.PERSON_FOCUSED : RoomType.GAME_FOCUSED);
		when(room.getTitle()).thenReturn("방 상세");
		when(room.getDescription()).thenReturn("소개");
		when(room.getExperienceLevel()).thenReturn(ExperienceLevel.ALL_LEVELS);
		when(room.isRulemasterLed()).thenReturn(false);
		when(room.getStartAt()).thenReturn(startsAt);
		when(room.getRegion()).thenReturn("홍대");
		when(room.getCapacity()).thenReturn(capacity);
		when(room.getTotalParticipantCount()).thenReturn(1);
		when(room.getRemainingRecruitmentSeats()).thenReturn(capacity);
		when(room.getStatus()).thenReturn(status);
		lenient().when(room.getPlace()).thenReturn("정확한 장소");
		return room;
	}
}
