package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.RoomCanceledEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEventRecorder;
import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@ExtendWith(MockitoExtension.class)
class RoomStatusChangeExecutorTest {

	private static final long HOST_ID = 42L;
	private static final long ROOM_ID = 7L;
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomRepository roomRepository;
	@Mock
	private RoomWaitlistRepository roomWaitlistRepository;
	@Mock
	private ParticipationRepository participationRepository;
	@Mock
	private RoomChangeEventRecorder roomChangeEventRecorder;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	private RoomStatusChangeExecutor executor;

	@BeforeEach
	void setUp() {
		executor = new RoomStatusChangeExecutor(
			roomRepository, roomWaitlistRepository, participationRepository, roomChangeEventRecorder, eventPublisher);
	}

	@Test
	void 취소는_상태_보정_뒤_마감된_방을_CANCELED로_바꾼다() {
		Room room = room(NOW);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		RoomStatusResponse response = executor.cancelRoom(HOST_ID, ROOM_ID, NOW);

		assertEquals(ROOM_ID, response.roomId());
		assertEquals(RoomStatus.CANCELED, response.roomStatus());
		assertEquals(RoomStatus.CANCELED, room.getStatus());
	}

	@Test
	void 종료는_상태_보정_뒤_시작_시각_경계에서_FINISHED로_바꾼다() {
		Room room = room(NOW);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		RoomStatusResponse response = executor.finishRoom(HOST_ID, ROOM_ID, NOW);

		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, room.getStatus());
	}

	@Test
	void 존재하지_않는_방은_ROOM_NOT_FOUND다() {
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

		BusinessException exception = assertThrows(
			BusinessException.class, () -> executor.cancelRoom(HOST_ID, ROOM_ID, NOW));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 주최자가_아니면_FORBIDDEN이다() {
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room(NOW)));

		BusinessException exception = assertThrows(BusinessException.class,
			() -> executor.finishRoom(99L, ROOM_ID, NOW));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	@Test
	void 시작_전_마감_방은_종료할_수_없다() {
		Room room = room(NOW.plusSeconds(1));
		setStatus(room, RoomStatus.CLOSED);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		BusinessException exception = assertThrows(
			BusinessException.class, () -> executor.finishRoom(HOST_ID, ROOM_ID, NOW));

		assertEquals(ErrorCode.INVALID_ROOM_STATUS_TRANSITION, exception.getErrorCode());
	}

	@Test
	void 자동_종료_경계_이후_종료는_정합화된_FINISHED_상태로_성공한다() {
		Room room = room(NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START));
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		RoomStatusResponse response = executor.finishRoom(HOST_ID, ROOM_ID, NOW);

		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, room.getStatus());
	}

	@Test
	void 이미_FINISHED인_방의_종료는_성공한다() {
		Room room = room(NOW);
		setStatus(room, RoomStatus.FINISHED);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		RoomStatusResponse response = executor.finishRoom(HOST_ID, ROOM_ID, NOW);

		assertEquals(ROOM_ID, response.roomId());
		assertEquals(RoomStatus.FINISHED, response.roomStatus());
		assertEquals(RoomStatus.FINISHED, room.getStatus());
	}

	@Test
	void 취소된_방의_종료는_상태_전이_오류다() {
		Room room = room(NOW);
		setStatus(room, RoomStatus.CANCELED);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		BusinessException exception = assertThrows(
			BusinessException.class, () -> executor.finishRoom(HOST_ID, ROOM_ID, NOW));

		assertEquals(ErrorCode.INVALID_ROOM_STATUS_TRANSITION, exception.getErrorCode());
	}

	@Test
	void 최종_상태_방의_반복_취소는_상태_전이_오류다() {
		Room room = room(NOW);
		setStatus(room, RoomStatus.CANCELED);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

		BusinessException exception = assertThrows(
			BusinessException.class, () -> executor.cancelRoom(HOST_ID, ROOM_ID, NOW));

		assertEquals(ErrorCode.INVALID_ROOM_STATUS_TRANSITION, exception.getErrorCode());
	}

	@Test
	void ROOM_취소는_WAITING_종료가_확정된_뒤_terminal_event를_한번_발행한다() {
		Room room = mock(Room.class);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(room.getHostUserId()).thenReturn(HOST_ID);
		when(room.getId()).thenReturn(ROOM_ID);
		when(room.cancel()).thenReturn(true);
		when(room.getStatus()).thenReturn(RoomStatus.CANCELED);

		executor.cancelRoom(HOST_ID, ROOM_ID, NOW);

		InOrder completionOrder = inOrder(roomRepository, roomWaitlistRepository, eventPublisher);
		completionOrder.verify(roomRepository).save(room);
		completionOrder.verify(roomRepository).flush();
		completionOrder.verify(roomWaitlistRepository).cancelAllWaiting(ROOM_ID, NOW);
		completionOrder.verify(eventPublisher).publishEvent(any(RoomTerminalStateReached.class));
	}

	@Test
	void ROOM_취소의_WAITING_종료가_실패하면_terminal_event를_발행하지_않는다() {
		Room room = mock(Room.class);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(room.getHostUserId()).thenReturn(HOST_ID);
		when(room.getId()).thenReturn(ROOM_ID);
		when(room.cancel()).thenReturn(true);
		doThrow(new DataIntegrityViolationException("test waitlist failure"))
			.when(roomWaitlistRepository)
			.cancelAllWaiting(ROOM_ID, NOW);

		assertThrows(
			DataIntegrityViolationException.class,
			() -> executor.cancelRoom(HOST_ID, ROOM_ID, NOW));

		verify(eventPublisher, never()).publishEvent(any(RoomTerminalStateReached.class));
	}

	@Test
	void 방_취소는_ACTIVE_참가자_스냅샷만_기록하고_수신자가_없으면_생략한다() {
		Room room = mock(Room.class);
		when(roomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
		when(room.getHostUserId()).thenReturn(HOST_ID);
		when(room.getId()).thenReturn(ROOM_ID);
		when(room.cancel()).thenReturn(true);
		when(participationRepository.findUserIdsByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			ROOM_ID, cloud.bamsongi.albammate.room.enums.ParticipationStatus.ACTIVE))
			.thenReturn(java.util.List.of(11L, 12L));

		executor.cancelRoom(HOST_ID, ROOM_ID, NOW);

		org.mockito.ArgumentCaptor<RoomChangeEvent> eventCaptor = org.mockito.ArgumentCaptor
			.forClass(RoomChangeEvent.class);
		org.mockito.ArgumentCaptor<java.util.Collection<Long>> recipientsCaptor = org.mockito.ArgumentCaptor
			.forClass(java.util.Collection.class);
		verify(roomChangeEventRecorder).record(eventCaptor.capture(), recipientsCaptor.capture());
		RoomCanceledEvent event = org.junit.jupiter.api.Assertions.assertInstanceOf(
			RoomCanceledEvent.class, eventCaptor.getValue());
		assertEquals(ROOM_ID, event.roomId());
		assertEquals(NOW, event.occurredAt());
		assertEquals(java.util.List.of(11L, 12L), recipientsCaptor.getValue());

		Room emptyRecipientsRoom = mock(Room.class);
		when(roomRepository.findById(8L)).thenReturn(Optional.of(emptyRecipientsRoom));
		when(emptyRecipientsRoom.getHostUserId()).thenReturn(HOST_ID);
		when(emptyRecipientsRoom.getId()).thenReturn(8L);
		when(emptyRecipientsRoom.cancel()).thenReturn(true);
		when(participationRepository.findUserIdsByRoomIdAndStatusOrderByJoinedAtAscIdAsc(
			8L, cloud.bamsongi.albammate.room.enums.ParticipationStatus.ACTIVE))
			.thenReturn(java.util.List.of());

		executor.cancelRoom(HOST_ID, 8L, NOW);

		verify(roomChangeEventRecorder, times(1)).record(any(RoomChangeEvent.class), any());
	}

	private Room room(Instant startsAt) {
		Room room = Room.create(
			HOST_ID,
			RoomType.PERSON_FOCUSED,
			"방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startsAt,
			"장소",
			3);
		setId(room, ROOM_ID);
		return room;
	}

	private void setId(Room room, long roomId) {
		setField(room, "id", roomId);
	}

	private void setStatus(Room room, RoomStatus status) {
		setField(room, "status", status);
	}

	private void setField(Room room, String name, Object value) {
		try {
			Field field = Room.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(room, value);
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError(exception);
		}
	}
}
