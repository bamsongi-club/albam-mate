package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
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
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.command.RoomUpdateService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.OptimisticLockException;

@Testcontainers
@SpringBootTest
@Import(RoomParticipationConcurrencyPostgresTest.ConcurrencyTestConfiguration.class)
class RoomParticipationConcurrencyPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;

	@Autowired
	private RoomUpdateService roomUpdateService;

	@Autowired
	private RoomStatusChangeService roomStatusChangeService;

	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;

	@Autowired
	private RoomReadGate roomReadGate;

	@Autowired
	private RoomVersionClaimGate roomVersionClaimGate;

	@Autowired
	private WaitlistReactivationGate waitlistReactivationGate;

	@Autowired
	private ParticipationWriteFailureGate participationWriteFailureGate;

	@Autowired
	private ParticipationCancelStepGate participationCancelStepGate;

	@Autowired
	private PromotionRetryGate promotionRetryGate;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private RoomTerminalEventCounter roomTerminalEventCounter;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	@Qualifier("participationRepository") private ParticipationRepository participationRepository;

	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		roomReadGate.deactivate();
		roomVersionClaimGate.deactivate();
		waitlistReactivationGate.deactivate();
		participationWriteFailureGate.deactivate();
		participationCancelStepGate.deactivate();
		promotionRetryGate.deactivate();
		roomTerminalEventCounter.clear();
		jdbcTemplate.execute(
			"truncate table chat_rooms, room_waitlists, participations, rooms, users restart identity cascade");
	}

	@Test
	void 마지막_좌석_참가_두_건은_같은_버전을_읽어도_한_건만_성공하고_정원을_초과하지_않는다() throws Exception {
		long hostUserId = insertUser("last-seat-host", "방장");
		long firstParticipantId = insertUser("last-seat-first", "참가자1");
		long secondParticipantId = insertUser("last-seat-second", "참가자2");
		Room room = createRoom(hostUserId, 1);

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationService.participate(
					firstParticipantId, room.getId()),
				() -> roomParticipationService.participate(
					secondParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertEquals(1, results.stream().filter(CommandResult::successful).count());
		assertEquals(
			List.of(ErrorCode.CAPACITY_EXCEEDED),
			results.stream()
				.filter(result -> !result.successful())
				.map(CommandResult::errorCode)
				.toList());
		assertRoomInvariant(room.getId());
		assertEquals(
			RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
	}

	@Test
	void 참가_취소와_새_참가는_같은_버전을_읽은_뒤_재시도해_둘_다_성공한다() throws Exception {
		long hostUserId = insertUser("cancel-join-host", "방장");
		long cancelingParticipantId = insertUser("cancel-join-current", "기존참가자");
		long joiningParticipantId = insertUser("cancel-join-new", "새참가자");
		Room room = createRoom(hostUserId, 2);
		roomParticipationService.participate(cancelingParticipantId, room.getId());

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationCancelService.cancelParticipation(
					cancelingParticipantId, room.getId()),
				() -> roomParticipationService.participate(
					joiningParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertTrue(results.stream().allMatch(CommandResult::successful));
		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(1, storedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());
		assertEquals(
			ParticipationStatus.CANCELED,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), cancelingParticipantId)
				.orElseThrow()
				.getStatus());
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), joiningParticipantId)
				.orElseThrow()
				.getStatus());
		assertRoomInvariant(room.getId());
	}

	@Test
	void 정원_축소와_새_참가는_같은_버전을_읽어도_최신_업무_규칙과_저장_불변식을_지킨다() throws Exception {
		long hostUserId = insertUser("update-join-host", "방장");
		long joiningParticipantId = insertUser("update-join-new", "새참가자");
		Room room = createRoom(hostUserId, 2);
		RoomUpdateRequest updateRequest = new RoomUpdateRequest();
		updateRequest.setRecruitmentCapacity(1);

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomUpdateService.updateRoom(
					hostUserId, room.getId(), updateRequest),
				() -> roomParticipationService.participate(
					joiningParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		CommandResult updateResult = results.get(0);
		CommandResult joinResult = results.get(1);
		assertTrue(joinResult.successful());
		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		if (updateResult.successful()) {
			assertTrue(joinResult.successful());
			assertEquals(1, storedRoom.getCapacity());
			assertEquals(1, storedRoom.getActiveParticipantCount());
			assertEquals(RoomStatus.CLOSED, storedRoom.getStatus());
		} else {
			assertEquals(
				ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS,
				updateResult.errorCode());
			assertEquals(2, storedRoom.getCapacity());
			assertEquals(1, storedRoom.getActiveParticipantCount());
			assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());
		}
		assertRoomInvariant(room.getId());
	}

	@Test
	void 취소된_기존_참가와_신규_참가는_같은_버전을_읽은_뒤_재시도해_관계를_정확히_저장한다() throws Exception {
		long hostUserId = insertUser("rejoin-host", "방장");
		long activeParticipantId = insertUser("rejoin-active", "기존활성참가자");
		long rejoiningParticipantId = insertUser("rejoin-canceled", "재참가자");
		long newParticipantId = insertUser("rejoin-new", "새참가자");
		Room room = createRoom(hostUserId, 3);
		roomParticipationService.participate(activeParticipantId, room.getId());
		roomParticipationService.participate(rejoiningParticipantId, room.getId());
		roomParticipationCancelService.cancelParticipation(rejoiningParticipantId, room.getId());
		Long canceledParticipationId = participationRepository
			.findByRoomIdAndUserId(room.getId(), rejoiningParticipantId)
			.orElseThrow()
			.getId();

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationService.participate(
					rejoiningParticipantId, room.getId()),
				() -> roomParticipationService.participate(
					newParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertTrue(results.stream().allMatch(CommandResult::successful));
		Participation rejoinedParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), rejoiningParticipantId)
			.orElseThrow();
		assertEquals(canceledParticipationId, rejoinedParticipation.getId());
		assertEquals(ParticipationStatus.ACTIVE, rejoinedParticipation.getStatus());
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), newParticipantId)
				.orElseThrow()
				.getStatus());
		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(3, storedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.CLOSED, storedRoom.getStatus());
		assertRoomInvariant(room.getId());
	}

	@Test
	void participation_저장_실패는_이미_flush된_방_변경까지_같은_트랜잭션에서_롤백한다() {
		long hostUserId = insertUser("rollback-host", "방장");
		long participantId = insertUser("rollback-participant", "참가자");
		Room room = createRoom(hostUserId, 1);

		participationWriteFailureGate.activate();
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> roomParticipationService.participate(participantId, room.getId()));
		} finally {
			participationWriteFailureGate.deactivate();
		}

		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(0, storedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());
		int activeParticipationCount = (int)participationRepository.findAll().stream()
			.filter(
				participation -> participation
					.getRoom()
					.getId()
					.equals(room.getId()))
			.filter(
				participation -> participation.getStatus() == ParticipationStatus.ACTIVE)
			.count();
		assertEquals(0, activeParticipationCount);
	}

	@Test
	void 승격_참가_저장_실패는_참가_취소와_대기_승격과_ROOM_변경을_모두_롤백한다() {
		long hostUserId = insertUser("promotion-rollback-host", "방장");
		long leavingUserId = insertUser("promotion-rollback-leaving", "취소자");
		long waitingUserId = insertUser("promotion-rollback-waiting", "대기자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW));

		participationWriteFailureGate.activateAfterSuccessfulWrites(1);
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId()));
		} finally {
			participationWriteFailureGate.deactivate();
		}

		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, storedRoom.getStatus());
		assertEquals(1, storedRoom.getActiveParticipantCount());
		assertEquals(ParticipationStatus.ACTIVE, participationRepository
			.findByRoomIdAndUserId(room.getId(), leavingUserId)
			.orElseThrow()
			.getStatus());
		assertEquals(RoomWaitlistStatus.WAITING, roomWaitlistRepository
			.findById(new RoomWaitlistId(room.getId(), waitingUserId))
			.orElseThrow()
			.getStatus());
		assertTrue(participationRepository.findByRoomIdAndUserId(room.getId(), waitingUserId).isEmpty());
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from notification_outbox_events where room_id = ? and event_type = 'WAITLIST_PROMOTED'",
			Integer.class,
			room.getId()));
		assertRoomInvariant(room.getId());
	}

	@Test
	void T3_승격_재시도_최종_성공은_accepted_한번_failed_0으로_기록한다() {
		long hostUserId = insertUser("promotion-retry-success-host", "방장");
		long leavingUserId = insertUser("promotion-retry-success-leaving", "취소자");
		long waitingUserId = insertUser("promotion-retry-success-waiting", "대기자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW));
		double acceptedBefore = promotionCount("accepted");
		double failedBefore = promotionCount("failed");

		promotionRetryGate.activate(room.getId(), 1);
		try {
			roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId());
		} finally {
			promotionRetryGate.deactivate();
		}

		assertEquals(acceptedBefore + 1.0, promotionCount("accepted"));
		assertEquals(failedBefore, promotionCount("failed"));
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), waitingUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), waitingUserId));
		assertRoomInvariant(room.getId());
	}

	@Test
	void T3_승격_재시도_소진은_failed_한번으로_기록한다() {
		long hostUserId = insertUser("promotion-retry-failed-host", "방장");
		long leavingUserId = insertUser("promotion-retry-failed-leaving", "취소자");
		long waitingUserId = insertUser("promotion-retry-failed-waiting", "대기자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW));
		double acceptedBefore = promotionCount("accepted");
		double failedBefore = promotionCount("failed");

		promotionRetryGate.activate(room.getId(), 3);
		try {
			BusinessException exception = assertThrows(BusinessException.class,
				() -> roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId()));
			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		} finally {
			promotionRetryGate.deactivate();
		}

		assertEquals(acceptedBefore, promotionCount("accepted"));
		assertEquals(failedBefore + 1.0, promotionCount("failed"));
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(room.getId(), waitingUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), leavingUserId));
		assertRoomInvariant(room.getId());
	}

	@Test
	void 복수_참가_취소는_빈자리마다_서로_다른_현재_FIFO_대기자만_승격한다() throws Exception {
		long hostUserId = insertUser("multiple-promotion-host", "방장");
		long firstLeavingUserId = insertUser("multiple-promotion-first-leaving", "취소자1");
		long secondLeavingUserId = insertUser("multiple-promotion-second-leaving", "취소자2");
		long firstWaitingUserId = insertUser("multiple-promotion-first-waiting", "대기자1");
		long secondWaitingUserId = insertUser("multiple-promotion-second-waiting", "대기자2");
		long thirdWaitingUserId = insertUser("multiple-promotion-third-waiting", "대기자3");
		Room room = createRoom(hostUserId, 2);
		roomParticipationService.participate(firstLeavingUserId, room.getId());
		roomParticipationService.participate(secondLeavingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), firstWaitingUserId, 10L, NOW));
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), secondWaitingUserId, 20L, NOW));
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), thirdWaitingUserId, 30L, NOW));

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationCancelService.cancelParticipation(firstLeavingUserId, room.getId()),
				() -> roomParticipationCancelService.cancelParticipation(secondLeavingUserId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertTrue(results.stream().allMatch(CommandResult::successful));
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), firstWaitingUserId));
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), secondWaitingUserId));
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(room.getId(), thirdWaitingUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), firstWaitingUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), secondWaitingUserId));
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
			Integer.class,
			room.getId()));
		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from notification_outbox_events where room_id = ? and event_type = 'WAITLIST_PROMOTED'",
			Integer.class,
			room.getId()));
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_recipients recipient
			join notification_outbox_events event on event.id = recipient.outbox_event_id
			where event.room_id = ?
			  and event.event_type = 'WAITLIST_PROMOTED'
			  and recipient.recipient_user_id = ?
			""", Integer.class, room.getId(), firstWaitingUserId));
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_recipients recipient
			join notification_outbox_events event on event.id = recipient.outbox_event_id
			where event.room_id = ?
			  and event.event_type = 'WAITLIST_PROMOTED'
			  and recipient.recipient_user_id = ?
			""", Integer.class, room.getId(), secondWaitingUserId));
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_recipients recipient
			join notification_outbox_events event on event.id = recipient.outbox_event_id
			where event.room_id = ?
			  and event.event_type = 'WAITLIST_PROMOTED'
			  and recipient.recipient_user_id = ?
			""", Integer.class, room.getId(), thirdWaitingUserId));
		assertRoomInvariant(room.getId());
	}

	@Test
	void 대기_활성화가_먼저_확정되면_참가_취소는_그_대기자를_승격하고_방을_마감한다() throws Exception {
		long hostUserId = insertUser("registration-first-host", "방장");
		long leavingUserId = insertUser("registration-first-leaving", "취소자");
		long waitingUserId = insertUser("registration-first-waiting", "신청자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());

		participationCancelStepGate.activate(room.getId(), leavingUserId);
		ExecutorService executor = Executors.newFixedThreadPool(1);
		try {
			Future<CommandResult> participationCancelFuture = executor.submit(
				() -> execute(() -> roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId())));

			participationCancelStepGate.awaitCancellationBlocked();
			assertTrue(roomWaitlistCommandService.register(waitingUserId, room.getId()).created());
			assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(room.getId(), waitingUserId));
			participationCancelStepGate.releaseCancellation();
			assertTrue(participationCancelFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).successful());
		} finally {
			participationCancelStepGate.releaseCancellation();
			participationCancelStepGate.deactivate();
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}

		Room promotedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, promotedRoom.getStatus());
		assertEquals(1, promotedRoom.getActiveParticipantCount());
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), waitingUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), waitingUserId));
		assertEquals(ParticipationStatus.CANCELED, participationStatus(room.getId(), leavingUserId));
		assertEquals(0, activeWaitingCount(room.getId()));
		assertRoomInvariant(room.getId());
	}

	@Test
	void 신규_대기_신청과_참가_취소가_경합해도_활성_대기가_있는_모집_상태로_수렴하지_않는다() throws Exception {
		long hostUserId = insertUser("registration-cancel-race-host", "방장");
		long leavingUserId = insertUser("registration-cancel-race-leaving", "취소자");
		long waitingUserId = insertUser("registration-cancel-race-waiting", "신청자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomWaitlistCommandService.register(waitingUserId, room.getId()),
				() -> roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		CommandResult registrationResult = results.get(0);
		assertTrue(results.get(1).successful());
		assertEquals(ParticipationStatus.CANCELED, participationStatus(room.getId(), leavingUserId));
		assertEquals(0, activeWaitingCount(room.getId()));
		Room finalRoom = roomRepository.findById(room.getId()).orElseThrow();
		if (registrationResult.successful()) {
			assertEquals(RoomStatus.CLOSED, finalRoom.getStatus());
			assertEquals(1, finalRoom.getActiveParticipantCount());
			assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), waitingUserId));
			assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), waitingUserId));
		} else {
			assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, registrationResult.errorCode());
			assertEquals(RoomStatus.RECRUITING, finalRoom.getStatus());
			assertEquals(0, finalRoom.getActiveParticipantCount());
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
				Integer.class,
				room.getId()));
		}
		assertRoomInvariant(room.getId());
	}

	@Test
	void 취소된_대기_재신청이_먼저_확정되면_참가_취소는_재신청자를_승격한다() throws Exception {
		assertReapplicationWins(createCanceledWaitlistFixture("canceled-reapply-first"));
	}

	@Test
	void 승격된_대기_재신청이_먼저_확정되면_참가_취소는_재신청자를_승격한다() throws Exception {
		assertReapplicationWins(createPromotedWaitlistFixture("promoted-reapply-first"));
	}

	@Test
	void 취소된_대기_재신청보다_참가_취소가_먼저_확정되면_재신청은_거절된다() throws Exception {
		assertCancellationWins(createCanceledWaitlistFixture("canceled-cancel-first"));
	}

	@Test
	void 승격된_대기_재신청보다_참가_취소가_먼저_확정되면_재신청은_거절된다() throws Exception {
		assertCancellationWins(createPromotedWaitlistFixture("promoted-cancel-first"));
	}

	@Test
	void ROOM_취소가_승리하면_terminal_event와_채팅_수명주기를_한_번만_반영한다() throws Exception {
		long hostUserId = insertUser("cancel-promotion-race-host", "방장");
		long leavingUserId = insertUser("cancel-promotion-race-leaving", "취소자");
		long waitingUserId = insertUser("cancel-promotion-race-waiting", "대기자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L, NOW));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));

		roomReadGate.activate(room.getId());
		participationCancelStepGate.activate(room.getId(), leavingUserId);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> roomCancelFuture = executor.submit(
				() -> execute(() -> roomStatusChangeService.cancelRoom(hostUserId, room.getId())));
			Future<CommandResult> participationCancelFuture = executor.submit(
				() -> execute(() -> roomParticipationCancelService.cancelParticipation(leavingUserId, room.getId())));

			participationCancelStepGate.awaitCancellationBlocked();
			assertTrue(roomCancelFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).successful());
			assertEquals(RoomStatus.CANCELED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
			participationCancelStepGate.releaseCancellation();
			assertTrue(participationCancelFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).successful());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			participationCancelStepGate.releaseCancellation();
			participationCancelStepGate.deactivate();
			roomReadGate.deactivate();
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}

		assertEquals(RoomStatus.CANCELED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(RoomWaitlistStatus.ROOM_CANCELED, waitlistStatus(room.getId(), waitingUserId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ? and status = 'ACTIVE'",
			Integer.class,
			room.getId(),
			waitingUserId));
		assertEquals(1, roomTerminalEventCounter.count());
		assertEquals(NOW.plusSeconds(TimeUnit.DAYS.toSeconds(30)), chatRoomRepository
			.findByRoomId(room.getId())
			.orElseThrow()
			.getPurgeAfter());
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and status = 'WAITING'",
			Integer.class,
			room.getId()));
		assertTrue(jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
			Integer.class,
			room.getId()) <= 1);
	}

	@Test
	void CANCELED와_FINISHED_ROOM의_동시_참가는_최종_상태와_참가_카운터를_바꾸지_않는다() throws Exception {
		assertTerminalRoomRejectsConcurrentParticipation(createCanceledRoom(), RoomStatus.CANCELED);
		assertTerminalRoomRejectsConcurrentParticipation(createFinishedRoom(), RoomStatus.FINISHED);
	}

	private Room createCanceledRoom() {
		long hostUserId = insertUser("terminal-canceled-host", "방장");
		long participantUserId = insertUser("terminal-canceled-participant", "참가자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(participantUserId, room.getId());
		roomStatusChangeService.cancelRoom(hostUserId, room.getId());
		return roomRepository.findById(room.getId()).orElseThrow();
	}

	private Room createFinishedRoom() {
		long hostUserId = insertUser("terminal-finished-host", "방장");
		long participantUserId = insertUser("terminal-finished-participant", "참가자");
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"종료 상태 동시성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW,
				"홍대 테스트 장소",
				1));
		participationRepository.saveAndFlush(
			Participation.createActive(room, participantUserId, NOW.minusSeconds(60)));
		room.addActiveParticipant();
		roomRepository.saveAndFlush(room);
		roomStatusChangeService.finishRoom(hostUserId, room.getId());
		return roomRepository.findById(room.getId()).orElseThrow();
	}

	private void assertTerminalRoomRejectsConcurrentParticipation(Room terminalRoom, RoomStatus expectedStatus)
		throws Exception {
		long firstUserId = insertUser("terminal-first-" + terminalRoom.getId(), "참가자1");
		long secondUserId = insertUser("terminal-second-" + terminalRoom.getId(), "참가자2");

		roomReadGate.activate(terminalRoom.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationService.participate(firstUserId, terminalRoom.getId()),
				() -> roomParticipationService.participate(secondUserId, terminalRoom.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertEquals(
			List.of(ErrorCode.ROOM_NOT_RECRUITING, ErrorCode.ROOM_NOT_RECRUITING),
			results.stream().map(CommandResult::errorCode).toList());
		Room storedRoom = roomRepository.findById(terminalRoom.getId()).orElseThrow();
		assertEquals(expectedStatus, storedRoom.getStatus());
		int activeParticipationCount = jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
			Integer.class,
			terminalRoom.getId());
		assertEquals(activeParticipationCount, storedRoom.getActiveParticipantCount());
		assertTrue(storedRoom.getActiveParticipantCount() >= 0);
		assertTrue(storedRoom.getActiveParticipantCount() <= storedRoom.getCapacity());
	}

	private RoomWaitlistStatus waitlistStatus(long roomId, long userId) {
		return roomWaitlistRepository.findById(new RoomWaitlistId(roomId, userId)).orElseThrow().getStatus();
	}

	private ParticipationStatus participationStatus(long roomId, long userId) {
		return participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow().getStatus();
	}

	private int activeWaitingCount(long roomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and status = 'WAITING'",
			Integer.class,
			roomId);
	}

	private double promotionCount(String outcome) {
		Counter counter = meterRegistry.find("room.waitlist.operations")
			.tags("operation", "promote", "outcome", outcome)
			.counter();
		return counter == null ? 0.0 : counter.count();
	}

	private ReapplicationFixture createCanceledWaitlistFixture(String emailPrefix) {
		long hostUserId = insertUser(emailPrefix + "-host", "방장");
		long leavingUserId = insertUser(emailPrefix + "-leaving", "취소자");
		long reapplyingUserId = insertUser(emailPrefix + "-reapplying", "재신청자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(leavingUserId, room.getId());
		assertTrue(roomWaitlistCommandService.register(reapplyingUserId, room.getId()).created());
		roomWaitlistCommandService.cancel(reapplyingUserId, room.getId());
		assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), reapplyingUserId));
		return new ReapplicationFixture(room, leavingUserId, reapplyingUserId);
	}

	private ReapplicationFixture createPromotedWaitlistFixture(String emailPrefix) {
		long hostUserId = insertUser(emailPrefix + "-host", "방장");
		long initiallyParticipatingUserId = insertUser(emailPrefix + "-initial", "초기참가자");
		long reapplyingUserId = insertUser(emailPrefix + "-reapplying", "재신청자");
		long leavingUserId = insertUser(emailPrefix + "-leaving", "취소자");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(initiallyParticipatingUserId, room.getId());
		assertTrue(roomWaitlistCommandService.register(reapplyingUserId, room.getId()).created());
		roomParticipationCancelService.cancelParticipation(initiallyParticipatingUserId, room.getId());
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), reapplyingUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), reapplyingUserId));
		roomParticipationCancelService.cancelParticipation(reapplyingUserId, room.getId());
		assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), reapplyingUserId));
		assertEquals(ParticipationStatus.CANCELED, participationStatus(room.getId(), reapplyingUserId));
		roomParticipationService.participate(leavingUserId, room.getId());
		return new ReapplicationFixture(room, leavingUserId, reapplyingUserId);
	}

	private void assertReapplicationWins(ReapplicationFixture fixture) throws Exception {
		roomReadGate.activate(fixture.room().getId());
		participationCancelStepGate.activate(fixture.room().getId(), fixture.leavingUserId());
		waitlistReactivationGate.activate(fixture.room().getId(), fixture.reapplyingUserId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> registrationFuture = executor.submit(
				() -> execute(() -> roomWaitlistCommandService.register(
					fixture.reapplyingUserId(), fixture.room().getId())));
			Future<CommandResult> cancellationFuture = executor.submit(
				() -> execute(() -> roomParticipationCancelService.cancelParticipation(
					fixture.leavingUserId(), fixture.room().getId())));

			participationCancelStepGate.awaitCancellationBlocked();
			assertTrue(registrationFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).successful());
			waitlistReactivationGate.assertExactlyOneReactivation();
			participationCancelStepGate.releaseCancellation();
			assertTrue(cancellationFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).successful());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			participationCancelStepGate.releaseCancellation();
			participationCancelStepGate.deactivate();
			roomReadGate.deactivate();
			waitlistReactivationGate.deactivate();
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}

		Room finalRoom = roomRepository.findById(fixture.room().getId()).orElseThrow();
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(fixture.room().getId(), fixture.reapplyingUserId()));
		assertEquals(ParticipationStatus.ACTIVE,
			participationStatus(fixture.room().getId(), fixture.reapplyingUserId()));
		assertEquals(RoomStatus.CLOSED, finalRoom.getStatus());
		assertEquals(0, activeWaitingCount(fixture.room().getId()));
		assertRoomInvariant(fixture.room().getId());
	}

	private void assertCancellationWins(ReapplicationFixture fixture) throws Exception {
		roomReadGate.activate(fixture.room().getId());
		roomVersionClaimGate.activate(fixture.room().getId());
		participationCancelStepGate.activate(fixture.room().getId(), fixture.leavingUserId());
		waitlistReactivationGate.activate(fixture.room().getId(), fixture.reapplyingUserId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> registrationFuture = executor.submit(
				() -> execute(() -> roomWaitlistCommandService.register(
					fixture.reapplyingUserId(), fixture.room().getId())));
			Future<CommandResult> cancellationFuture = executor.submit(
				() -> execute(() -> roomParticipationCancelService.cancelParticipation(
					fixture.leavingUserId(), fixture.room().getId())));

			participationCancelStepGate.awaitCancellationBlocked();
			roomVersionClaimGate.awaitRegistrationBlocked();
			participationCancelStepGate.releaseCancellation();
			assertTrue(cancellationFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).successful());
			roomVersionClaimGate.releaseRegistration();
			CommandResult registrationResult = registrationFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, registrationResult.errorCode());
			waitlistReactivationGate.assertNoReactivation();
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			participationCancelStepGate.releaseCancellation();
			roomVersionClaimGate.releaseRegistration();
			participationCancelStepGate.deactivate();
			roomVersionClaimGate.deactivate();
			roomReadGate.deactivate();
			waitlistReactivationGate.deactivate();
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}

		Room finalRoom = roomRepository.findById(fixture.room().getId()).orElseThrow();
		assertEquals(RoomStatus.RECRUITING, finalRoom.getStatus());
		assertEquals(0, activeWaitingCount(fixture.room().getId()));
		assertRoomInvariant(fixture.room().getId());
	}

	private List<CommandResult> executeConcurrently(Callable<?> first, Callable<?> second)
		throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> firstFuture = executor.submit(() -> execute(first));
			Future<CommandResult> secondFuture = executor.submit(() -> execute(second));
			CommandResult firstResult = firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			CommandResult secondResult = secondFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			return List.of(firstResult, secondResult);
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}
	}

	private CommandResult execute(Callable<?> command) throws Exception {
		try {
			command.call();
			return CommandResult.success();
		} catch (BusinessException exception) {
			return CommandResult.failure(exception.getErrorCode());
		}
	}

	private void assertRoomInvariant(long roomId) {
		Room room = roomRepository.findById(roomId).orElseThrow();
		int activeParticipationCount = (int)participationRepository.findAll().stream()
			.filter(
				participation -> participation.getRoom().getId().equals(roomId))
			.filter(
				participation -> participation.getStatus() == ParticipationStatus.ACTIVE)
			.count();

		assertEquals(activeParticipationCount, room.getActiveParticipantCount());
		assertTrue(room.getActiveParticipantCount() >= 0);
		assertTrue(room.getActiveParticipantCount() <= room.getCapacity());
		assertEquals(
			room.getActiveParticipantCount() == room.getCapacity()
				? RoomStatus.CLOSED
				: RoomStatus.RECRUITING,
			room.getStatus());
	}

	private Room createRoom(long hostUserId, int capacity) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"동시성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대 테스트 장소",
				capacity));
	}

	private long insertUser(String emailPrefix, String nickname) {
		String email = emailPrefix + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			email,
			nickname);
		Long userId = jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
		assertNotNull(userId);
		return userId;
	}

	private record CommandResult(boolean successful, ErrorCode errorCode) {

		private static CommandResult success() {
			return new CommandResult(true, null);
		}

		private static CommandResult failure(ErrorCode errorCode) {
			return new CommandResult(false, errorCode);
		}
	}

	private record ReapplicationFixture(Room room, long leavingUserId, long reapplyingUserId) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConcurrencyTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		RoomReadGate roomReadGate() {
			return new RoomReadGate();
		}

		@Bean
		RoomVersionClaimGate roomVersionClaimGate() {
			return new RoomVersionClaimGate();
		}

		@Bean
		WaitlistReactivationGate waitlistReactivationGate() {
			return new WaitlistReactivationGate();
		}

		@Bean
		ParticipationWriteFailureGate participationWriteFailureGate() {
			return new ParticipationWriteFailureGate();
		}

		@Bean
		ParticipationCancelStepGate participationCancelStepGate() {
			return new ParticipationCancelStepGate();
		}

		@Bean
		PromotionRetryGate promotionRetryGate() {
			return new PromotionRetryGate();
		}

		@Bean
		RoomTerminalEventCounter roomTerminalEventCounter() {
			return new RoomTerminalEventCounter();
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomReadGate roomReadGate,
			RoomVersionClaimGate roomVersionClaimGate) {
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(
				delegate, roomReadGate, roomVersionClaimGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(),
				new Class<?>[] {RoomRepository.class},
				handler);
		}

		@Bean(name = "gatedRoomWaitlistRepository")
		@Primary
		RoomWaitlistRepository gatedRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			WaitlistReactivationGate waitlistReactivationGate) {
			InvocationHandler handler = new GateAwareRoomWaitlistRepositoryInvocationHandler(
				delegate, waitlistReactivationGate);
			return (RoomWaitlistRepository)Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(),
				new Class<?>[] {RoomWaitlistRepository.class},
				handler);
		}

		@Bean(name = "gatedParticipationRepository")
		@Primary
		ParticipationRepository gatedParticipationRepository(
			@Qualifier("participationRepository") ParticipationRepository delegate,
			ParticipationWriteFailureGate participationWriteFailureGate,
			ParticipationCancelStepGate participationCancelStepGate,
			PromotionRetryGate promotionRetryGate) {
			InvocationHandler handler = new GateAwareParticipationRepositoryInvocationHandler(
				delegate, participationWriteFailureGate, participationCancelStepGate, promotionRetryGate);
			return (ParticipationRepository)Proxy.newProxyInstance(
				ParticipationRepository.class.getClassLoader(),
				new Class<?>[] {ParticipationRepository.class},
				handler);
		}
	}

	private static final class GateAwareRoomRepositoryInvocationHandler
		implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomReadGate roomReadGate;
		private final RoomVersionClaimGate roomVersionClaimGate;

		private GateAwareRoomRepositoryInvocationHandler(
			RoomRepository delegate, RoomReadGate roomReadGate, RoomVersionClaimGate roomVersionClaimGate) {
			this.delegate = delegate;
			this.roomReadGate = roomReadGate;
			this.roomVersionClaimGate = roomVersionClaimGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				roomVersionClaimGate.blockBeforeClaimVersion(method, args);
				Object result = method.invoke(delegate, args);
				if (method.getName().equals("findById")
					&& args != null
					&& args.length == 1
					&& args[0] instanceof Long roomId
					&& result instanceof Optional<?> optional) {
					roomReadGate.afterFindById(roomId, optional.map(Room.class::cast));
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	private static final class GateAwareRoomWaitlistRepositoryInvocationHandler
		implements InvocationHandler {

		private final RoomWaitlistRepository delegate;
		private final WaitlistReactivationGate waitlistReactivationGate;

		private GateAwareRoomWaitlistRepositoryInvocationHandler(
			RoomWaitlistRepository delegate, WaitlistReactivationGate waitlistReactivationGate) {
			this.delegate = delegate;
			this.waitlistReactivationGate = waitlistReactivationGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				Object result = method.invoke(delegate, args);
				waitlistReactivationGate.afterReactivateWaiting(method, args);
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	private static final class GateAwareParticipationRepositoryInvocationHandler
		implements InvocationHandler {

		private final ParticipationRepository delegate;
		private final ParticipationWriteFailureGate participationWriteFailureGate;
		private final ParticipationCancelStepGate participationCancelStepGate;
		private final PromotionRetryGate promotionRetryGate;

		private GateAwareParticipationRepositoryInvocationHandler(
			ParticipationRepository delegate,
			ParticipationWriteFailureGate participationWriteFailureGate,
			ParticipationCancelStepGate participationCancelStepGate,
			PromotionRetryGate promotionRetryGate) {
			this.delegate = delegate;
			this.participationWriteFailureGate = participationWriteFailureGate;
			this.participationCancelStepGate = participationCancelStepGate;
			this.promotionRetryGate = promotionRetryGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				participationCancelStepGate.blockBeforeParticipationLookup(method, args);
				Object result = method.invoke(delegate, args);
				if (method.getName().equals("save")
					&& args != null
					&& args.length == 1
					&& participationWriteFailureGate.consumeFailureWhenActive()) {
					delegate.flush();
					throw new DataIntegrityViolationException("테스트 전용 participation 저장 실패");
				}
				if (method.getName().equals("save")
					&& args != null
					&& args.length == 1
					&& args[0] instanceof Participation participation
					&& promotionRetryGate.consumeFailureWhenPromotionSaved(participation)) {
					delegate.flush();
					throw new OptimisticLockException("테스트 전용 승격 낙관 락 충돌");
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	static final class RoomReadGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId)));
		}

		void afterFindById(long roomId, Optional<Room> room) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.roomId != roomId) {
				return;
			}

			int readOrder = scenario.totalReadCount.getAndIncrement();
			if (readOrder >= 2) {
				return;
			}

			Long version = room.orElseThrow().getVersion();
			assertNotNull(version);
			scenario.initialReadCount.incrementAndGet();
			scenario.observedVersions.add(version);
			scenario.initialReads.countDown();
			try {
				assertTrue(scenario.initialReads.await(WAIT_SECONDS, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("동시 초기 findById 대기 중 인터럽트되었습니다.", exception);
			}
		}

		void assertExactlyTwoReadsOfOneVersion() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			assertEquals(2, scenario.initialReadCount.get());
			assertEquals(1, scenario.observedVersions.size());
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private static final class Scenario {

			private final long roomId;
			private final CountDownLatch initialReads = new CountDownLatch(2);
			private final AtomicInteger initialReadCount = new AtomicInteger();
			private final AtomicInteger totalReadCount = new AtomicInteger();
			private final Set<Long> observedVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}

	static final class RoomVersionClaimGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId)));
		}

		void blockBeforeClaimVersion(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null
				|| !method.getName().equals("claimVersion")
				|| arguments == null
				|| arguments.length != 2
				|| !(arguments[0] instanceof Long roomId)
				|| scenario.roomId != roomId
				|| !scenario.firstClaim.compareAndSet(false, true)) {
				return;
			}

			scenario.registrationBlocked.countDown();
			await(scenario.registrationMayContinue);
		}

		void awaitRegistrationBlocked() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			await(scenario.registrationBlocked);
		}

		void releaseRegistration() {
			Scenario scenario = activeScenario.get();
			if (scenario != null) {
				scenario.registrationMayContinue.countDown();
			}
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private void await(CountDownLatch latch) {
			try {
				assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("ROOM version claim 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final AtomicBoolean firstClaim = new AtomicBoolean();
			private final CountDownLatch registrationBlocked = new CountDownLatch(1);
			private final CountDownLatch registrationMayContinue = new CountDownLatch(1);

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}

	static final class WaitlistReactivationGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId, long userId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId, userId)));
		}

		void afterReactivateWaiting(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null
				|| !method.getName().equals("reactivateWaiting")
				|| arguments == null
				|| arguments.length != 4
				|| !(arguments[0] instanceof Long roomId)
				|| !(arguments[1] instanceof Long userId)
				|| scenario.roomId != roomId
				|| scenario.userId != userId) {
				return;
			}
			scenario.reactivationCount.incrementAndGet();
		}

		void assertExactlyOneReactivation() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			assertEquals(1, scenario.reactivationCount.get());
		}

		void assertNoReactivation() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			assertEquals(0, scenario.reactivationCount.get());
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private static final class Scenario {

			private final long roomId;
			private final long userId;
			private final AtomicInteger reactivationCount = new AtomicInteger();

			private Scenario(long roomId, long userId) {
				this.roomId = roomId;
				this.userId = userId;
			}
		}
	}

	static final class ParticipationWriteFailureGate {

		private final AtomicReference<AtomicInteger> successfulWritesBeforeFailure = new AtomicReference<>();

		void activate() {
			activateAfterSuccessfulWrites(0);
		}

		void activateAfterSuccessfulWrites(int successfulWriteCount) {
			assertTrue(successfulWritesBeforeFailure.compareAndSet(null, new AtomicInteger(successfulWriteCount)));
		}

		boolean consumeFailureWhenActive() {
			AtomicInteger remainingWrites = successfulWritesBeforeFailure.get();
			return remainingWrites != null && remainingWrites.getAndDecrement() == 0;
		}

		void deactivate() {
			successfulWritesBeforeFailure.set(null);
		}
	}

	static final class ParticipationCancelStepGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId, long userId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId, userId)));
		}

		void blockBeforeParticipationLookup(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null
				|| !method.getName().equals("findByRoomIdAndUserId")
				|| arguments == null
				|| arguments.length != 2
				|| !(arguments[0] instanceof Long roomId)
				|| !(arguments[1] instanceof Long userId)
				|| scenario.roomId != roomId
				|| scenario.userId != userId
				|| !scenario.firstLookup.compareAndSet(false, true)) {
				return;
			}

			scenario.cancellationBlocked.countDown();
			await(scenario.cancellationMayContinue);
		}

		void awaitCancellationBlocked() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			await(scenario.cancellationBlocked);
		}

		void releaseCancellation() {
			Scenario scenario = activeScenario.get();
			if (scenario != null) {
				scenario.cancellationMayContinue.countDown();
			}
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private void await(CountDownLatch latch) {
			try {
				assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("참가 취소 단계 게이트 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final long userId;
			private final AtomicBoolean firstLookup = new AtomicBoolean();
			private final CountDownLatch cancellationBlocked = new CountDownLatch(1);
			private final CountDownLatch cancellationMayContinue = new CountDownLatch(1);

			private Scenario(long roomId, long userId) {
				this.roomId = roomId;
				this.userId = userId;
			}
		}
	}

	static final class PromotionRetryGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId, int failureCount) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId, failureCount)));
		}

		boolean consumeFailureWhenPromotionSaved(Participation participation) {
			Scenario scenario = activeScenario.get();
			if (scenario == null
				|| participation.getStatus() != ParticipationStatus.ACTIVE
				|| participation.getRoom() == null
				|| participation.getRoom().getId() == null
				|| participation.getRoom().getId() != scenario.roomId) {
				return false;
			}
			return scenario.remainingFailures.getAndDecrement() > 0;
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private static final class Scenario {

			private final long roomId;
			private final AtomicInteger remainingFailures;

			private Scenario(long roomId, int failureCount) {
				this.roomId = roomId;
				this.remainingFailures = new AtomicInteger(failureCount);
			}
		}
	}

	static final class RoomTerminalEventCounter {

		private final AtomicInteger count = new AtomicInteger();

		@EventListener
		public void onApplicationEvent(RoomTerminalStateReached event) {
			count.incrementAndGet();
		}

		int count() {
			return count.get();
		}

		void clear() {
			count.set(0);
		}
	}
}
