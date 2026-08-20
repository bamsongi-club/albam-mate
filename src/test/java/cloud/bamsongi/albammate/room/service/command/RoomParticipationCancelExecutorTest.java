package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ParticipationCanceledEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEventRecorder;
import cloud.bamsongi.albammate.room.contract.WaitlistPromotedEvent;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistCandidateProjection;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import jakarta.persistence.EntityManager;

@SpringBootTest
@Import(RoomParticipationCancelExecutorTest.FixedClockConfiguration.class)
class RoomParticipationCancelExecutorTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final ApplicationEventPublisher NO_OP_EVENT_PUBLISHER = event -> {};

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	@Test
	void 없는_방의_서비스_통합_경로는_ROOM_NOT_FOUND로_종료한다() {
		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomParticipationCancelService.cancelParticipation(42L, 999_999L));

		assertEquals(ErrorCode.ROOM_NOT_FOUND, exception.getErrorCode());
	}

	@Test
	void 대기자가_없으면_참가_취소와_인원_감소가_함께_반영되어_모집을_재개한다() {
		long hostUserId = insertUser("cancel-host@example.com", "방장");
		long participantUserId = insertUser("cancel-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		Participation participation = participationRepository.saveAndFlush(
			Participation.createActive(room, participantUserId, NOW.minusSeconds(60)));
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);

		RoomParticipationResponse response = roomParticipationCancelService.cancelParticipation(participantUserId,
			room.getId());

		assertEquals(ParticipationStatus.CANCELED, response.participationStatus());
		assertEquals(RoomStatus.RECRUITING, response.roomStatus());
		assertEquals(1, response.participantCount());
		assertEquals(1, response.remainingRecruitmentSeats());
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from participations where room_id = ? and user_id = ?",
				Integer.class,
				room.getId(),
				participantUserId));
		Participation canceledParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow();
		assertEquals(participation.getId(), canceledParticipation.getId());
		assertEquals(ParticipationStatus.CANCELED, canceledParticipation.getStatus());
		assertEquals(NOW, canceledParticipation.getCanceledAt());
		assertEquals(
			0, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
		assertEquals(
			RoomStatus.RECRUITING,
			roomRepository.findById(room.getId()).orElseThrow().getStatus());
	}

	@Test
	void 대기자가_없으면_참가_취소_알림을_기록하기_전에_ROOM_인원_감소를_flush한다() {
		long roomId = 7L;
		long hostUserId = 1L;
		long participantUserId = 10L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		RoomChangeEventRecorder recorder = mock(RoomChangeEventRecorder.class);
		Room room = mock(Room.class);
		Participation participation = mock(Participation.class);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository,
			mockedParticipationRepository,
			mockedWaitlistRepository,
			recorder,
			NO_OP_EVENT_PUBLISHER,
			mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(room));
		when(room.getHostUserId()).thenReturn(hostUserId);
		when(room.getId()).thenReturn(roomId);
		when(room.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getRemainingRecruitmentSeats()).thenReturn(1);
		when(participation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, participantUserId))
			.thenReturn(java.util.Optional.of(participation));
		when(mockedWaitlistRepository.findFirstWaitingByRoomId(roomId)).thenReturn(java.util.Optional.empty());

		executor.cancelParticipation(participantUserId, roomId, NOW);

		InOrder order = inOrder(room, mockedRoomRepository, recorder);
		order.verify(room).removeActiveParticipant();
		order.verify(mockedRoomRepository).save(room);
		order.verify(mockedRoomRepository).flush();
		order.verify(recorder).record(any(ParticipationCanceledEvent.class), any());
	}

	@Test
	void 첫_WAITING을_승격하고_취소된_참가_관계를_복구해_방을_마감한다() {
		long hostUserId = insertUser("promotion-host@example.com", "방장");
		long leavingUserId = insertUser("promotion-leaving@example.com", "취소자");
		long waitingUserId = insertUser("promotion-waiting@example.com", "대기자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		Participation leavingParticipation = participationRepository.saveAndFlush(
			Participation.createActive(room, leavingUserId, NOW.minusSeconds(60)));
		Participation waitingParticipation = Participation.createActive(room, waitingUserId, NOW.minusSeconds(120));
		waitingParticipation.cancel(NOW.minusSeconds(90));
		Participation canceledWaitingParticipation = participationRepository.saveAndFlush(waitingParticipation);
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW.minusSeconds(30)));
		long roomVersionBeforeCancellation = roomRepository.findById(room.getId()).orElseThrow().getVersion();

		RoomParticipationResponse response = roomParticipationCancelService.cancelParticipation(leavingUserId,
			room.getId());

		clearPersistenceContext();
		assertEquals(ParticipationStatus.CANCELED, response.participationStatus());
		Room promotedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, promotedRoom.getStatus());
		assertEquals(1, promotedRoom.getActiveParticipantCount());
		assertEquals(roomVersionBeforeCancellation + 1, promotedRoom.getVersion());
		assertEquals(RoomWaitlistStatus.PROMOTED, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), waitingUserId))
			.orElseThrow()
			.getStatus());
		Participation promotedParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), waitingUserId)
			.orElseThrow();
		assertEquals(canceledWaitingParticipation.getId(), promotedParticipation.getId());
		assertEquals(ParticipationStatus.ACTIVE, promotedParticipation.getStatus());
		assertEquals(NOW, promotedParticipation.getJoinedAt());
		assertEquals(null, promotedParticipation.getCanceledAt());
		assertEquals(ParticipationStatus.CANCELED, participationRepository
			.findById(leavingParticipation.getId())
			.orElseThrow()
			.getStatus());
	}

	@Test
	void 현재_FIFO_첫_WAITING만_승격하고_뒤_대기자는_남긴다() {
		long hostUserId = insertUser("fifo-host@example.com", "방장");
		long leavingUserId = insertUser("fifo-leaving@example.com", "취소자");
		long firstWaitingUserId = insertUser("fifo-first@example.com", "첫 대기자");
		long secondWaitingUserId = insertUser("fifo-second@example.com", "둘째 대기자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		participationRepository.saveAndFlush(Participation.createActive(room, leavingUserId, NOW.minusSeconds(60)));
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(room.getId(), firstWaitingUserId, 10L, NOW.minusSeconds(30)));
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(room.getId(), secondWaitingUserId, 20L, NOW.minusSeconds(20)));

		roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId());

		clearPersistenceContext();
		assertEquals(RoomWaitlistStatus.PROMOTED, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), firstWaitingUserId))
			.orElseThrow()
			.getStatus());
		assertEquals(RoomWaitlistStatus.WAITING, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), secondWaitingUserId))
			.orElseThrow()
			.getStatus());
		assertEquals(1, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
	}

	@Test
	void 승격_대상이_이미_활성_참가자면_인원과_참가_관계가_어긋난_채로_커밋하지_않는다() {
		long hostUserId = insertUser("promotion-conflict-host@example.com", "방장");
		long leavingUserId = insertUser("promotion-conflict-leaving@example.com", "취소자");
		long waitingUserId = insertUser("promotion-conflict-waiting@example.com", "대기자");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		participationRepository.saveAndFlush(Participation.createActive(room, leavingUserId, NOW.minusSeconds(60)));
		participationRepository.saveAndFlush(Participation.createActive(room, waitingUserId, NOW.minusSeconds(50)));
		room.addActiveParticipant();
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW.minusSeconds(30)));

		assertThrows(IllegalStateException.class,
			() -> roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId()));

		clearPersistenceContext();
		Room unchangedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(2, unchangedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.CLOSED, unchangedRoom.getStatus());
		assertEquals(RoomWaitlistStatus.WAITING, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), waitingUserId))
			.orElseThrow()
			.getStatus());
		assertEquals(ParticipationStatus.ACTIVE, participationRepository
			.findByRoomIdAndUserId(room.getId(), leavingUserId)
			.orElseThrow()
			.getStatus());
	}

	@Test
	void 과거_순번_승격이_실패하면_다음_현재_FIFO_대기자만_승격한다() {
		long roomId = 7L;
		long leavingUserId = 10L;
		long staleWaitingUserId = 20L;
		long currentWaitingUserId = 30L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		Room mockedRoom = mock(Room.class);
		Participation leavingParticipation = mock(Participation.class);
		RoomWaitlistCandidateProjection staleCandidate = candidate(staleWaitingUserId, 10L);
		RoomWaitlistCandidateProjection currentCandidate = candidate(currentWaitingUserId, 20L);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository,
			mockedParticipationRepository,
			mockedWaitlistRepository,
			mock(RoomChangeEventRecorder.class),
			NO_OP_EVENT_PUBLISHER,
			mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(mockedRoom));
		when(mockedRoom.getHostUserId()).thenReturn(1L);
		when(mockedRoom.getId()).thenReturn(roomId);
		when(mockedRoom.getVersion()).thenReturn(1L);
		when(mockedRoom.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(mockedRoom.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(mockedRoom.getTotalParticipantCount()).thenReturn(2);
		when(mockedRoom.getRemainingRecruitmentSeats()).thenReturn(0);
		when(leavingParticipation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, leavingUserId))
			.thenReturn(java.util.Optional.of(leavingParticipation));
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, currentWaitingUserId))
			.thenReturn(java.util.Optional.empty());
		when(mockedWaitlistRepository.findFirstWaitingByRoomId(roomId))
			.thenReturn(java.util.Optional.of(staleCandidate), java.util.Optional.of(currentCandidate));
		when(mockedWaitlistRepository.promoteWaiting(roomId, staleWaitingUserId, 10L, NOW)).thenReturn(0);
		when(mockedWaitlistRepository.promoteWaiting(roomId, currentWaitingUserId, 20L, NOW)).thenReturn(1);
		when(mockedRoomRepository.claimVersion(roomId, 1L)).thenReturn(1);

		executor.cancelParticipation(leavingUserId, roomId, NOW);

		InOrder transitionOrder = inOrder(mockedWaitlistRepository);
		transitionOrder.verify(mockedWaitlistRepository).promoteWaiting(roomId, staleWaitingUserId, 10L, NOW);
		transitionOrder.verify(mockedWaitlistRepository).promoteWaiting(roomId, currentWaitingUserId, 20L, NOW);
		verify(mockedParticipationRepository, times(2)).save(any(Participation.class));
		verify(mockedParticipationRepository).findByRoomIdAndUserId(roomId, currentWaitingUserId);
	}

	@Test
	void 성공_승격은_기존_ROOM_version_claim으로_경계를_유지하고_명시적_ROOM_flush를_호출하지_않는다() {
		long roomId = 7L;
		long leavingUserId = 10L;
		long waitingUserId = 20L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		Room mockedRoom = mock(Room.class);
		Participation leavingParticipation = mock(Participation.class);
		RoomWaitlistCandidateProjection waitingCandidate = candidate(waitingUserId, 10L);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository,
			mockedParticipationRepository,
			mockedWaitlistRepository,
			mock(RoomChangeEventRecorder.class),
			NO_OP_EVENT_PUBLISHER,
			mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(mockedRoom));
		when(mockedRoom.getHostUserId()).thenReturn(1L);
		when(mockedRoom.getId()).thenReturn(roomId);
		when(mockedRoom.getVersion()).thenReturn(3L);
		when(mockedRoom.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(mockedRoom.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(mockedRoom.getTotalParticipantCount()).thenReturn(2);
		when(mockedRoom.getRemainingRecruitmentSeats()).thenReturn(0);
		when(leavingParticipation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, leavingUserId))
			.thenReturn(java.util.Optional.of(leavingParticipation));
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, waitingUserId))
			.thenReturn(java.util.Optional.empty());
		when(mockedWaitlistRepository.findFirstWaitingByRoomId(roomId))
			.thenReturn(java.util.Optional.of(waitingCandidate));
		when(mockedWaitlistRepository.promoteWaiting(roomId, waitingUserId, 10L, NOW)).thenReturn(1);
		when(mockedRoomRepository.claimVersion(roomId, 3L)).thenReturn(1);

		executor.cancelParticipation(leavingUserId, roomId, NOW);

		verify(mockedRoomRepository).claimVersion(roomId, 3L);
		verify(mockedRoomRepository, org.mockito.Mockito.never()).flush();
	}

	@Test
	void 취소된_참가_관계는_활성_참가가_아니므로_참가_관계를_찾지_못한_오류를_반환한다() {
		long hostUserId = insertUser("canceled-host@example.com", "방장");
		long participantUserId = insertUser("canceled-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 1, NOW.plusSeconds(3600));
		Instant canceledAt = NOW.minusSeconds(30);
		Participation participation = Participation.createActive(room, participantUserId, NOW.minusSeconds(60));
		participation.cancel(canceledAt);
		participationRepository.saveAndFlush(participation);

		assertError(
			ErrorCode.PARTICIPATION_NOT_FOUND,
			() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));

		clearPersistenceContext();
		Participation canceledParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow();
		assertEquals(ParticipationStatus.CANCELED, canceledParticipation.getStatus());
		assertEquals(canceledAt, canceledParticipation.getCanceledAt());
		assertEquals(0, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
	}

	@Test
	void 오류_우선순위에_따라_주최자와_없는_관계와_시작_이후를_거절한다() {
		long hostUserId = insertUser("error-host@example.com", "방장");
		long participantUserId = insertUser("error-member@example.com", "참가자");
		Room futureRoom = createRoom(hostUserId, 1, NOW.plusSeconds(3600));

		assertError(
			ErrorCode.FORBIDDEN,
			() -> roomParticipationCancelService.cancelParticipation(
				hostUserId, futureRoom.getId()));
		assertError(
			ErrorCode.PARTICIPATION_NOT_FOUND,
			() -> roomParticipationCancelService.cancelParticipation(
				participantUserId, futureRoom.getId()));

		Room startedRoom = createRoom(hostUserId, 1, NOW);
		assertError(
			ErrorCode.FORBIDDEN,
			() -> roomParticipationCancelService.cancelParticipation(hostUserId, startedRoom.getId()));
		assertError(
			ErrorCode.PARTICIPATION_NOT_FOUND,
			() -> roomParticipationCancelService.cancelParticipation(participantUserId, startedRoom.getId()));

		participationRepository.saveAndFlush(
			Participation.createActive(startedRoom, participantUserId, NOW.minusSeconds(60)));
		jdbcTemplate.update(
			"update rooms set active_participant_count = 1 where id = ?", startedRoom.getId());
		clearPersistenceContext();

		assertError(
			ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
			() -> roomParticipationCancelService.cancelParticipation(
				participantUserId, startedRoom.getId()));
		clearPersistenceContext();
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(startedRoom.getId(), participantUserId)
				.orElseThrow()
				.getStatus());
		assertEquals(
			1,
			roomRepository
				.findById(startedRoom.getId())
				.orElseThrow()
				.getActiveParticipantCount());
		assertEquals(
			RoomStatus.RECRUITING,
			roomRepository.findById(startedRoom.getId()).orElseThrow().getStatus());
	}

	@Test
	void 시작_시각_경계에서는_참가와_ROOM과_WAITING을_변경하지_않는다() {
		long hostUserId = insertUser("start-boundary-host@example.com", "방장");
		long participantUserId = insertUser("start-boundary-member@example.com", "참가자");
		long waitingUserId = insertUser("start-boundary-waiting@example.com", "대기자");
		Room room = createRoom(hostUserId, 1, NOW);
		participationRepository.saveAndFlush(
			Participation.createActive(room, participantUserId, NOW.minusSeconds(60)));
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW.minusSeconds(30)));

		assertError(
			ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
			() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));

		clearPersistenceContext();
		assertEquals(ParticipationStatus.ACTIVE, participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow()
			.getStatus());
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(1, roomRepository.findById(room.getId()).orElseThrow().getActiveParticipantCount());
		assertEquals(RoomWaitlistStatus.WAITING, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), waitingUserId))
			.orElseThrow()
			.getStatus());
	}

	@Test
	void 자동_승격_없이_실제_빈자리가_남은_참가_취소만_주최자에게_기록한다() {
		long roomId = 7L;
		long participantUserId = 10L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		RoomChangeEventRecorder recorder = mock(RoomChangeEventRecorder.class);
		Room room = mock(Room.class);
		Participation participation = mock(Participation.class);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository, mockedParticipationRepository, mockedWaitlistRepository, recorder,
			NO_OP_EVENT_PUBLISHER, mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(room));
		when(room.getHostUserId()).thenReturn(1L);
		when(room.getId()).thenReturn(roomId);
		when(room.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getRemainingRecruitmentSeats()).thenReturn(1);
		when(participation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, participantUserId))
			.thenReturn(java.util.Optional.of(participation));
		when(mockedWaitlistRepository.findFirstWaitingByRoomId(roomId)).thenReturn(java.util.Optional.empty());

		executor.cancelParticipation(participantUserId, roomId, NOW);

		org.mockito.ArgumentCaptor<RoomChangeEvent> eventCaptor = org.mockito.ArgumentCaptor
			.forClass(RoomChangeEvent.class);
		org.mockito.ArgumentCaptor<java.util.Collection<Long>> recipientsCaptor = org.mockito.ArgumentCaptor
			.forClass(java.util.Collection.class);
		verify(recorder).record(eventCaptor.capture(), recipientsCaptor.capture());
		ParticipationCanceledEvent event = org.junit.jupiter.api.Assertions.assertInstanceOf(
			ParticipationCanceledEvent.class, eventCaptor.getValue());
		assertEquals(roomId, event.roomId());
		assertEquals(NOW, event.occurredAt());
		assertEquals(java.util.List.of(1L), recipientsCaptor.getValue());

		RoomRepository promotedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository promotedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository promotedWaitlistRepository = mock(RoomWaitlistRepository.class);
		RoomChangeEventRecorder promotedRecorder = mock(RoomChangeEventRecorder.class);
		Room promotedRoom = mock(Room.class);
		Participation leavingParticipation = mock(Participation.class);
		RoomWaitlistCandidateProjection waiting = candidate(20L, 1L);
		RoomParticipationCancelExecutor promotedExecutor = new RoomParticipationCancelExecutor(
			promotedRoomRepository, promotedParticipationRepository, promotedWaitlistRepository, promotedRecorder,
			NO_OP_EVENT_PUBLISHER, mock(EntityManager.class));
		when(promotedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(promotedRoom));
		when(promotedRoom.getHostUserId()).thenReturn(1L);
		when(promotedRoom.getId()).thenReturn(roomId);
		when(promotedRoom.getVersion()).thenReturn(1L);
		when(promotedRoom.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(promotedRoom.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(leavingParticipation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(promotedParticipationRepository.findByRoomIdAndUserId(roomId, participantUserId))
			.thenReturn(java.util.Optional.of(leavingParticipation));
		when(promotedParticipationRepository.findByRoomIdAndUserId(roomId, 20L)).thenReturn(java.util.Optional.empty());
		when(promotedWaitlistRepository.findFirstWaitingByRoomId(roomId)).thenReturn(java.util.Optional.of(waiting));
		when(promotedWaitlistRepository.promoteWaiting(roomId, 20L, 1L, NOW)).thenReturn(1);
		when(promotedRoomRepository.claimVersion(roomId, 1L)).thenReturn(1);

		promotedExecutor.cancelParticipation(participantUserId, roomId, NOW);

		org.mockito.ArgumentCaptor<RoomChangeEvent> promotedEventCaptor = org.mockito.ArgumentCaptor
			.forClass(RoomChangeEvent.class);
		org.mockito.ArgumentCaptor<java.util.Collection<Long>> promotedRecipientsCaptor = org.mockito.ArgumentCaptor
			.forClass(java.util.Collection.class);
		verify(promotedRecorder).record(promotedEventCaptor.capture(), promotedRecipientsCaptor.capture());
		assertInstanceOf(WaitlistPromotedEvent.class, promotedEventCaptor.getValue());
		assertEquals(java.util.List.of(20L), promotedRecipientsCaptor.getValue());
	}

	@Test
	void 자동_승격은_실제_조건부_전이에_성공한_대기자만_수신자로_기록한다() {
		long roomId = 8L;
		long leavingUserId = 10L;
		long promotedUserId = 20L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		RoomChangeEventRecorder recorder = mock(RoomChangeEventRecorder.class);
		Room room = mock(Room.class);
		Participation leavingParticipation = mock(Participation.class);
		RoomWaitlistCandidateProjection waiting = candidate(promotedUserId, 1L);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository, mockedParticipationRepository, mockedWaitlistRepository, recorder,
			NO_OP_EVENT_PUBLISHER, mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(room));
		when(room.getHostUserId()).thenReturn(1L);
		when(room.getId()).thenReturn(roomId);
		when(room.getVersion()).thenReturn(1L);
		when(room.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(leavingParticipation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, leavingUserId))
			.thenReturn(java.util.Optional.of(leavingParticipation));
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, promotedUserId))
			.thenReturn(java.util.Optional.empty());
		when(mockedWaitlistRepository.findFirstWaitingByRoomId(roomId)).thenReturn(java.util.Optional.of(waiting));
		when(mockedWaitlistRepository.promoteWaiting(roomId, promotedUserId, 1L, NOW)).thenReturn(1);
		when(mockedRoomRepository.claimVersion(roomId, 1L)).thenReturn(1);

		executor.cancelParticipation(leavingUserId, roomId, NOW);

		org.mockito.ArgumentCaptor<RoomChangeEvent> eventCaptor = org.mockito.ArgumentCaptor
			.forClass(RoomChangeEvent.class);
		org.mockito.ArgumentCaptor<java.util.Collection<Long>> recipientsCaptor = org.mockito.ArgumentCaptor
			.forClass(java.util.Collection.class);
		verify(recorder).record(eventCaptor.capture(), recipientsCaptor.capture());
		WaitlistPromotedEvent event = assertInstanceOf(WaitlistPromotedEvent.class, eventCaptor.getValue());
		assertEquals(roomId, event.roomId());
		assertEquals(NOW, event.occurredAt());
		assertEquals(java.util.List.of(promotedUserId), recipientsCaptor.getValue());
	}

	@Test
	void 대기자_승격_경쟁_실패_후_후보가_소진되면_주최자에게_기록한다() {
		long roomId = 7L;
		long hostUserId = 1L;
		long participantUserId = 10L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		RoomChangeEventRecorder recorder = mock(RoomChangeEventRecorder.class);
		Room room = mock(Room.class);
		Participation participation = mock(Participation.class);
		RoomWaitlistCandidateProjection waiting = candidate(20L, 1L);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository, mockedParticipationRepository, mockedWaitlistRepository, recorder,
			NO_OP_EVENT_PUBLISHER, mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(room));
		when(room.getHostUserId()).thenReturn(hostUserId);
		when(room.getId()).thenReturn(roomId);
		when(room.getVersion()).thenReturn(1L);
		when(room.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(room.getStatus()).thenReturn(RoomStatus.RECRUITING);
		when(room.getRemainingRecruitmentSeats()).thenReturn(1);
		when(participation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, participantUserId))
			.thenReturn(java.util.Optional.of(participation));
		when(mockedWaitlistRepository.findFirstWaitingByRoomId(roomId))
			.thenReturn(java.util.Optional.of(waiting), java.util.Optional.empty());
		when(mockedWaitlistRepository.promoteWaiting(roomId, 20L, 1L, NOW)).thenReturn(0);
		when(mockedRoomRepository.claimVersion(roomId, 1L)).thenReturn(1);

		executor.cancelParticipation(participantUserId, roomId, NOW);

		org.mockito.ArgumentCaptor<RoomChangeEvent> eventCaptor = org.mockito.ArgumentCaptor
			.forClass(RoomChangeEvent.class);
		org.mockito.ArgumentCaptor<java.util.Collection<Long>> recipientsCaptor = org.mockito.ArgumentCaptor
			.forClass(java.util.Collection.class);
		verify(recorder).record(eventCaptor.capture(), recipientsCaptor.capture());
		ParticipationCanceledEvent event = org.junit.jupiter.api.Assertions.assertInstanceOf(
			ParticipationCanceledEvent.class, eventCaptor.getValue());
		assertEquals(roomId, event.roomId());
		assertEquals(NOW, event.occurredAt());
		assertEquals(java.util.List.of(hostUserId), recipientsCaptor.getValue());
	}

	@Test
	void 취소된_방의_재시도는_PARTICIPATION_CANCELED를_기록하지_않는다() {
		long roomId = 7L;
		long participantUserId = 10L;
		RoomRepository mockedRoomRepository = mock(RoomRepository.class);
		ParticipationRepository mockedParticipationRepository = mock(ParticipationRepository.class);
		RoomWaitlistRepository mockedWaitlistRepository = mock(RoomWaitlistRepository.class);
		RoomChangeEventRecorder recorder = mock(RoomChangeEventRecorder.class);
		Room room = mock(Room.class);
		Participation participation = mock(Participation.class);
		RoomParticipationCancelExecutor executor = new RoomParticipationCancelExecutor(
			mockedRoomRepository, mockedParticipationRepository, mockedWaitlistRepository, recorder,
			NO_OP_EVENT_PUBLISHER, mock(EntityManager.class));
		when(mockedRoomRepository.findById(roomId)).thenReturn(java.util.Optional.of(room));
		when(room.getHostUserId()).thenReturn(1L);
		when(room.getId()).thenReturn(roomId);
		when(room.getStartAt()).thenReturn(NOW.plusSeconds(3600));
		when(room.getStatus()).thenReturn(RoomStatus.CANCELED);
		when(room.getRemainingRecruitmentSeats()).thenReturn(1);
		when(participation.getStatus()).thenReturn(ParticipationStatus.ACTIVE);
		when(mockedParticipationRepository.findByRoomIdAndUserId(roomId, participantUserId))
			.thenReturn(java.util.Optional.of(participation));

		executor.cancelParticipation(participantUserId, roomId, NOW);

		org.mockito.Mockito.verifyNoInteractions(recorder);
	}

	private Room createRoom(long hostUserId, int capacity, Instant startAt) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"취소 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대 장소",
				capacity));
	}

	private RoomWaitlistCandidateProjection candidate(long userId, long queueOrder) {
		RoomWaitlistCandidateProjection candidate = mock(RoomWaitlistCandidateProjection.class);
		when(candidate.getUserId()).thenReturn(userId);
		when(candidate.getQueueOrder()).thenReturn(queueOrder);
		return candidate;
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			email,
			nickname);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	private void assertError(ErrorCode expected, Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(expected, exception.getErrorCode());
	}

	private void clearPersistenceContext() {
		entityManager.clear();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
