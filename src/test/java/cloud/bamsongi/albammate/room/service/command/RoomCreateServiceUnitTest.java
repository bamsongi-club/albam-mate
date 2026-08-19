package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.RoomActionAvailabilityEvaluator;
import cloud.bamsongi.albammate.user.contract.UserQuery;

@ExtendWith(MockitoExtension.class)
class RoomCreateServiceUnitTest {

	private static final Instant NOW = Instant.parse("2026-07-27T00:00:00Z");

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private GameQuery gameQuery;
	@Mock
	private UserQuery userQuery;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private RoomCreateService roomCreateService;

	@BeforeEach
	void setUp() {
		roomCreateService = new RoomCreateService(
			roomRepository, gameQuery, userQuery, Clock.fixed(NOW, ZoneOffset.UTC), eventPublisher,
			new RoomActionAvailabilityEvaluator());
		lenient()
			.when(userQuery.findUserSummaryById(42L))
			.thenReturn(Optional.of(new UserQuery.UserSummary("방장", "https://cdn.example.com/host.png")));
		lenient()
			.when(roomRepository.save(any(Room.class)))
			.thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));
	}

	@Test
	void T4_게임_중심_방을_생성하면_모집중이고_주최자는_참가행없이_프로필_이미지_URL과_함께_표시된다() {
		GameSummary game = new GameSummary(7L, 1007L, "카탄");
		when(gameQuery.findSummaryById(7L)).thenReturn(Optional.of(game));

		ParticipantRoomResponse response = roomCreateService.createRoom(42L, request(RoomType.GAME_FOCUSED, 7L));

		assertEquals(RoomType.GAME_FOCUSED, response.roomType());
		assertEquals(game, response.game());
		assertEquals("홍대", response.region());
		assertEquals(3, response.recruitmentCapacity());
		assertEquals(1, response.participantCount());
		assertEquals(3, response.remainingRecruitmentSeats());
		assertEquals("RECRUITING", response.status().name());
		assertEquals(MyRole.HOST, response.myRole());
		assertEquals("방장", response.host().nickname());
		assertEquals("https://cdn.example.com/host.png", response.host().profileImageUrl());
		assertEquals(
			java.util.List.of(new NicknameSummary("방장", "https://cdn.example.com/host.png")),
			response.participants());
		verify(roomRepository).save(any(Room.class));
	}

	@Test
	void T6_프로필_이미지가_없는_주최자의_host_profileImageUrl은_null이다() {
		when(userQuery.findUserSummaryById(42L)).thenReturn(Optional.of(new UserQuery.UserSummary("방장", null)));

		ParticipantRoomResponse response = roomCreateService.createRoom(42L, request(RoomType.PERSON_FOCUSED, null));

		assertEquals(null, response.host().profileImageUrl());
	}

	@Test
	void 사람_중심_방은_게임을_생략할_수_있다() {
		ParticipantRoomResponse response = roomCreateService.createRoom(42L, request(RoomType.PERSON_FOCUSED, null));

		assertEquals(null, response.game());
		verify(gameQuery, never()).findSummaryById(any());
	}

	@Test
	void 사람_중심_방에_존재하는_게임을_선택하면_응답과_저장_방에_게임_ID를_반영한다() {
		GameSummary game = new GameSummary(7L, 1007L, "카탄");
		when(gameQuery.findSummaryById(7L)).thenReturn(Optional.of(game));

		ParticipantRoomResponse response = roomCreateService.createRoom(42L, request(RoomType.PERSON_FOCUSED, 7L));

		ArgumentCaptor<Room> roomCaptor = ArgumentCaptor.forClass(Room.class);
		verify(roomRepository).save(roomCaptor.capture());
		assertEquals(game, response.game());
		assertEquals(7L, roomCaptor.getValue().getGameId());
	}

	@Test
	void 게임_중심_게임_ID_누락은_검증오류다() {
		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomCreateService.createRoom(
				42L, request(RoomType.GAME_FOCUSED, null)));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verify(roomRepository, never()).save(any());
	}

	@Test
	void 존재하지_않는_게임은_GAME_NOT_FOUND다() {
		when(gameQuery.findSummaryById(999L)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomCreateService.createRoom(
				42L, request(RoomType.PERSON_FOCUSED, 999L)));

		assertEquals(ErrorCode.GAME_NOT_FOUND, exception.getErrorCode());
		verify(roomRepository, never()).save(any());
	}

	@Test
	void 시작_시각이_현재와_같거나_과거면_검증오류다() {
		CreateRoomRequest request = new CreateRoomRequest(
			RoomType.PERSON_FOCUSED,
			"제목",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			true,
			NOW,
			"장소",
			3);

		BusinessException exception = assertThrows(
			BusinessException.class, () -> roomCreateService.createRoom(42L, request));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verify(roomRepository, never()).save(any());
	}

	@Test
	void 인증_주체에_대응하는_사용자가_없으면_인증필요_오류로_종료한다() {
		when(userQuery.findUserSummaryById(42L)).thenReturn(Optional.empty());

		assertThrows(
			UnauthenticatedException.class,
			() -> roomCreateService.createRoom(42L, request(RoomType.PERSON_FOCUSED, null)));
		verify(roomRepository, never()).save(any());
	}

	private CreateRoomRequest request(RoomType roomType, Long gameId) {
		return new CreateRoomRequest(
			roomType,
			"  제목  ",
			"소개",
			gameId,
			ExperienceLevel.ALL_LEVELS,
			true,
			NOW.plusSeconds(3600),
			"  장소  ",
			3);
	}

	private Room withId(Room room, long roomId) {
		try {
			java.lang.reflect.Field id = Room.class.getDeclaredField("id");
			id.setAccessible(true);
			id.set(room, roomId);
			return room;
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
