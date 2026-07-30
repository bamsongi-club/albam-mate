package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.UserQuery;

@ExtendWith(MockitoExtension.class)
class RoomUpdateExecutorTest {

	private static final long HOST_ID = 42L;
	private static final long ROOM_ID = 7L;
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private GameQuery gameQuery;
	@Mock
	private UserQuery userQuery;

	private RoomUpdateExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new RoomUpdateExecutor(roomRepository, gameQuery, userQuery);
		lenient().when(userQuery.findNicknameById(HOST_ID)).thenReturn(Optional.of("방장"));
	}

	@Test
	void 허용_필드만_부분_수정하고_명시적_null_설명은_삭제한다() {
		Room room = room(RoomType.PERSON_FOCUSED, null, NOW.plusSeconds(3600));
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setTitle("  새 제목  ");
		request.setDescription(null);
		request.setRecruitmentCapacity(4);

		ParticipantRoomResponse response = executor.updateRoom(HOST_ID, ROOM_ID, request, NOW);

		assertEquals("새 제목", response.title());
		assertEquals(null, response.description());
		assertEquals("기존 장소", response.place());
		assertEquals(4, response.recruitmentCapacity());
		assertEquals(4, response.remainingRecruitmentSeats());
	}

	@Test
	void 모든_허용_필드를_제공하면_게임_중심_방의_값을_변경한다() {
		Room room = room(RoomType.GAME_FOCUSED, 3L, NOW.plusSeconds(3600));
		GameSummary changedGame = new GameSummary(4L, 1004L, "스플렌더");
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(gameQuery.findSummaryById(4L)).thenReturn(Optional.of(changedGame));
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setTitle("새 제목");
		request.setPlace("새 장소");
		request.setDescription("새 설명");
		request.setGameId(4L);
		request.setExperienceLevel(ExperienceLevel.BEGINNER_WELCOME);
		request.setRulemasterLed(true);
		request.setStartsAt(NOW.plusSeconds(7200));
		request.setRecruitmentCapacity(4);

		ParticipantRoomResponse response = executor.updateRoom(HOST_ID, ROOM_ID, request, NOW);

		assertEquals("새 제목", response.title());
		assertEquals("새 장소", response.place());
		assertEquals("새 설명", response.description());
		assertEquals(changedGame, response.game());
		assertEquals(ExperienceLevel.BEGINNER_WELCOME, response.experienceLevel());
		assertEquals(true, response.isRulemasterLed());
		assertEquals(NOW.plusSeconds(7200), response.startsAt());
		assertEquals(4, response.recruitmentCapacity());
	}

	@Test
	void 존재하지_않는_방은_ROOM_NOT_FOUND다() {
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(HOST_ID, ROOM_ID, new RoomUpdateRequest(), NOW));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 주최자가_아니면_FORBIDDEN이다() {
		when(roomRepository.findById(ROOM_ID))
			.thenReturn(
				Optional.of(room(RoomType.PERSON_FOCUSED, null, NOW.plusSeconds(3600))));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(99L, ROOM_ID, new RoomUpdateRequest(), NOW));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	@Test
	void 활성_참가자가_있으면_상태_조건보다_먼저_수정_불가_오류를_반환한다() {
		Room room = room(RoomType.PERSON_FOCUSED, null, NOW.minusSeconds(1));
		setActiveParticipantCount(room, 1);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(HOST_ID, ROOM_ID, new RoomUpdateRequest(), NOW));

		assertEquals(
			ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS,
			exception.getErrorCode());
	}

	@Test
	void 상태_보정_뒤_모집중이_아닌_방은_상태_전이_오류를_반환한다() {
		when(roomRepository.findById(ROOM_ID))
			.thenReturn(Optional.of(room(RoomType.PERSON_FOCUSED, null, NOW.minusSeconds(1))));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(HOST_ID, ROOM_ID, new RoomUpdateRequest(), NOW));

		assertEquals(ErrorCode.INVALID_ROOM_STATUS_TRANSITION, exception.getErrorCode());
	}

	@Test
	void 게임_중심_방에서_gameId를_null로_수정하면_검증_오류를_반환한다() {
		when(roomRepository.findById(ROOM_ID))
			.thenReturn(Optional.of(room(RoomType.GAME_FOCUSED, 3L, NOW.plusSeconds(3600))));
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setGameId(null);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(HOST_ID, ROOM_ID, request, NOW));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verifyNoInteractions(gameQuery);
	}

	@Test
	void 사람_중심_방에서_gameId를_null로_수정하면_기존_게임_선택을_해제한다() {
		Room room = room(RoomType.PERSON_FOCUSED, 3L, NOW.plusSeconds(3600));
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setGameId(null);

		ParticipantRoomResponse response = executor.updateRoom(HOST_ID, ROOM_ID, request, NOW);

		assertEquals(null, response.game());
		assertEquals(null, room.getGameId());
		verify(gameQuery, never()).findSummaryById(anyLong());
	}

	@Test
	void 요청에_포함한_존재하지_않는_게임은_GAME_NOT_FOUND다() {
		when(roomRepository.findById(ROOM_ID))
			.thenReturn(
				Optional.of(room(RoomType.PERSON_FOCUSED, null, NOW.plusSeconds(3600))));
		when(gameQuery.findSummaryById(999L)).thenReturn(Optional.empty());
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setGameId(999L);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(HOST_ID, ROOM_ID, request, NOW));

		assertEquals(ErrorCode.GAME_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 현재_시각과_같거나_과거인_시작_시각은_검증_오류다() {
		when(roomRepository.findById(ROOM_ID))
			.thenReturn(
				Optional.of(room(RoomType.PERSON_FOCUSED, null, NOW.plusSeconds(3600))));
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setStartsAt(NOW);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> executor.updateRoom(HOST_ID, ROOM_ID, request, NOW));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		verify(gameQuery, never()).findSummaryById(anyLong());
	}

	@Test
	void 게임을_유지한_사람_중심_방은_기존_게임_요약을_반환한다() {
		Room room = room(RoomType.PERSON_FOCUSED, 3L, NOW.plusSeconds(3600));
		GameSummary game = new GameSummary(3L, 1003L, "카탄");
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(gameQuery.findSummaryById(3L)).thenReturn(Optional.of(game));

		ParticipantRoomResponse response = executor.updateRoom(HOST_ID, ROOM_ID, new RoomUpdateRequest(), NOW);

		assertEquals(game, response.game());
		verify(userQuery).findNicknameById(HOST_ID);
	}

	private Room room(RoomType roomType, Long gameId, Instant startsAt) {
		Room room = Room.create(
			HOST_ID,
			roomType,
			"기존 제목",
			"기존 설명",
			gameId,
			ExperienceLevel.ALL_LEVELS,
			false,
			startsAt,
			"기존 장소",
			3);
		setId(room, ROOM_ID);
		return room;
	}

	private void setId(Room room, long roomId) {
		try {
			Field field = Room.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(room, roomId);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}

	private void setActiveParticipantCount(Room room, int activeParticipantCount) {
		try {
			Field field = Room.class.getDeclaredField("activeParticipantCount");
			field.setAccessible(true);
			field.set(room, activeParticipantCount);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
