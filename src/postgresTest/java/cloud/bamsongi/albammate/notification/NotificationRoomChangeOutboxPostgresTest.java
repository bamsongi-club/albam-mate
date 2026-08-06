package cloud.bamsongi.albammate.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.notification.service.command.NotificationRoomChangeEventRecorder;
import cloud.bamsongi.albammate.room.contract.ParticipationCanceledEvent;
import cloud.bamsongi.albammate.room.contract.ParticipationJoinedEvent;
import cloud.bamsongi.albammate.room.contract.RoomCanceledEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEvent;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;

/** PostgreSQL에서 ROOM 변경의 실제 커밋 순서별 Outbox 수신자 스냅샷을 검증한다. */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class NotificationRoomChangeOutboxPostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_notification_room_change_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomStatusChangeService roomStatusChangeService;
	@MockitoSpyBean
	private NotificationRoomChangeEventRecorder roomChangeEventRecorder;

	@AfterEach
	void resetRecorderSpy() {
		reset(roomChangeEventRecorder);
	}

	@Test
	void 참가가_먼저_커밋되면_방_취소는_ACTIVE_참가자만_ROOM_CANCELED_수신자로_고정한다() {
		long hostUserId = user("join-first-host");
		long participantUserId = user("join-first-participant");
		long roomId = room(hostUserId, 2);

		CountDownLatch reachedParticipationRecorder = new CountDownLatch(1);
		CountDownLatch releaseParticipation = new CountDownLatch(1);
		CountDownLatch cancelStarted = new CountDownLatch(1);
		doAnswer(invocation -> {
			if (invocation.getArgument(0) instanceof ParticipationJoinedEvent) {
				reachedParticipationRecorder.countDown();
				assertTrue(releaseParticipation.await(10, TimeUnit.SECONDS));
			}
			return invocation.callRealMethod();
		}).when(roomChangeEventRecorder).record(any(RoomChangeEvent.class), any());
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			var participation = workers.submit(() -> roomParticipationService.participate(participantUserId, roomId));
			assertTrue(reachedParticipationRecorder.await(10, TimeUnit.SECONDS));
			var cancel = workers.submit(() -> {
				cancelStarted.countDown();
				return roomStatusChangeService.cancelRoom(hostUserId, roomId);
			});
			assertTrue(cancelStarted.await(10, TimeUnit.SECONDS));
			releaseParticipation.countDown();
			participation.get(20, TimeUnit.SECONDS);
			cancel.get(20, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		} finally {
			workers.shutdownNow();
			try {
				assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}

		assertEquals("CANCELED", roomStatus(roomId));
		assertEquals(List.of("PARTICIPATION_JOINED", "ROOM_CANCELED"), eventTypes(roomId));
		assertEquals(List.of(participantUserId), recipients(roomId, "ROOM_CANCELED"));
	}

	@Test
	void 방_취소가_먼저_커밋되면_뒤_참가는_거절되고_기존_ACTIVE만_ROOM_CANCELED_수신자로_고정한다() {
		long hostUserId = user("cancel-first-host");
		long existingParticipantUserId = user("cancel-first-existing");
		long participantUserId = user("cancel-first-participant");
		long roomId = room(hostUserId, 2);
		roomParticipationService.participate(existingParticipantUserId, roomId);
		reset(roomChangeEventRecorder);
		CountDownLatch reachedCanceledRecorder = new CountDownLatch(1);
		CountDownLatch releaseCancellation = new CountDownLatch(1);
		CountDownLatch participationStarted = new CountDownLatch(1);
		doAnswer(invocation -> {
			if (invocation.getArgument(0) instanceof RoomCanceledEvent) {
				reachedCanceledRecorder.countDown();
				assertTrue(releaseCancellation.await(10, TimeUnit.SECONDS));
			}
			return invocation.callRealMethod();
		}).when(roomChangeEventRecorder).record(any(RoomChangeEvent.class), any());
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			var cancel = workers.submit(() -> roomStatusChangeService.cancelRoom(hostUserId, roomId));
			assertTrue(reachedCanceledRecorder.await(10, TimeUnit.SECONDS));
			var participation = workers.submit(() -> {
				participationStarted.countDown();
				return roomParticipationService.participate(participantUserId, roomId);
			});
			assertTrue(participationStarted.await(10, TimeUnit.SECONDS));
			releaseCancellation.countDown();
			cancel.get(20, TimeUnit.SECONDS);
			assertParticipationRejected(
				assertThrows(java.util.concurrent.ExecutionException.class,
					() -> participation.get(20, TimeUnit.SECONDS)));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		} catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException exception) {
			throw new AssertionError(exception);
		} finally {
			workers.shutdownNow();
			try {
				assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}

		assertEquals("CANCELED", roomStatus(roomId));
		assertEquals(List.of("PARTICIPATION_JOINED", "ROOM_CANCELED"), eventTypes(roomId));
		assertEquals(List.of(existingParticipantUserId), recipients(roomId, "ROOM_CANCELED"));
	}

	@Test
	void 방_취소가_먼저_커밋되면_재시도_참가_취소는_PARTICIPATION_CANCELED를_남기지_않는다() {
		long hostUserId = user("cancel-before-participation-cancel-host");
		long participantUserId = user("cancel-before-participation-cancel-participant");
		long roomId = room(hostUserId, 2);
		roomParticipationService.participate(participantUserId, roomId);
		reset(roomChangeEventRecorder);
		CountDownLatch reachedCanceledRecorder = new CountDownLatch(1);
		CountDownLatch releaseCancellation = new CountDownLatch(1);
		CountDownLatch participationCancelStarted = new CountDownLatch(1);
		doAnswer(invocation -> {
			if (invocation.getArgument(0) instanceof RoomCanceledEvent) {
				reachedCanceledRecorder.countDown();
				assertTrue(releaseCancellation.await(10, TimeUnit.SECONDS));
			}
			return invocation.callRealMethod();
		}).when(roomChangeEventRecorder).record(any(RoomChangeEvent.class), any());
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			var cancelRoom = workers.submit(() -> roomStatusChangeService.cancelRoom(hostUserId, roomId));
			assertTrue(reachedCanceledRecorder.await(10, TimeUnit.SECONDS));
			var cancelParticipation = workers.submit(() -> {
				participationCancelStarted.countDown();
				return roomParticipationCancelService.cancelParticipation(participantUserId, roomId);
			});
			assertTrue(participationCancelStarted.await(10, TimeUnit.SECONDS));
			releaseCancellation.countDown();
			cancelRoom.get(20, TimeUnit.SECONDS);
			cancelParticipation.get(20, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		} finally {
			workers.shutdownNow();
			try {
				assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}

		assertEquals("CANCELED", roomStatus(roomId));
		assertEquals("CANCELED", participationStatus(roomId, participantUserId));
		assertEquals(List.of("PARTICIPATION_JOINED", "ROOM_CANCELED"), eventTypes(roomId));
		assertEquals(List.of(participantUserId), recipients(roomId, "ROOM_CANCELED"));
		assertEquals(List.of(), recipients(roomId, "PARTICIPATION_CANCELED"));
	}

	@Test
	void 참가_취소가_먼저_커밋되면_PARTICIPATION_CANCELED만_주최자에게_기록하고_방은_취소된다() {
		long hostUserId = user("participation-cancel-before-cancel-host");
		long participantUserId = user("participation-cancel-before-cancel-participant");
		long roomId = room(hostUserId, 2);
		roomParticipationService.participate(participantUserId, roomId);
		reset(roomChangeEventRecorder);
		CountDownLatch reachedParticipationCanceledRecorder = new CountDownLatch(1);
		CountDownLatch releaseParticipationCancellation = new CountDownLatch(1);
		CountDownLatch roomCancelStarted = new CountDownLatch(1);
		doAnswer(invocation -> {
			if (invocation.getArgument(0) instanceof ParticipationCanceledEvent) {
				reachedParticipationCanceledRecorder.countDown();
				assertTrue(releaseParticipationCancellation.await(10, TimeUnit.SECONDS));
			}
			return invocation.callRealMethod();
		}).when(roomChangeEventRecorder).record(any(RoomChangeEvent.class), any());
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			var cancelParticipation = workers.submit(
				() -> roomParticipationCancelService.cancelParticipation(participantUserId, roomId));
			assertTrue(reachedParticipationCanceledRecorder.await(10, TimeUnit.SECONDS));
			var cancelRoom = workers.submit(() -> {
				roomCancelStarted.countDown();
				return roomStatusChangeService.cancelRoom(hostUserId, roomId);
			});
			assertTrue(roomCancelStarted.await(10, TimeUnit.SECONDS));
			releaseParticipationCancellation.countDown();
			cancelParticipation.get(20, TimeUnit.SECONDS);
			cancelRoom.get(20, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError(exception);
		} finally {
			workers.shutdownNow();
			try {
				assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(exception);
			}
		}

		assertEquals("CANCELED", roomStatus(roomId));
		assertEquals("CANCELED", participationStatus(roomId, participantUserId));
		assertEquals(List.of("PARTICIPATION_JOINED", "PARTICIPATION_CANCELED"), eventTypes(roomId));
		assertEquals(List.of(hostUserId), recipients(roomId, "PARTICIPATION_CANCELED"));
		assertEquals(List.of(), recipients(roomId, "ROOM_CANCELED"));
	}

	@Test
	void Outbox_기록_실패는_참가와_ROOM과_Outbox를_함께_롤백한다() {
		long hostUserId = user("outbox-failure-host");
		long participantUserId = user("outbox-failure-participant");
		long roomId = room(hostUserId, 2);
		doThrow(new IllegalStateException("outbox failure"))
			.when(roomChangeEventRecorder)
			.record(any(RoomChangeEvent.class), any());

		assertThrows(IllegalStateException.class,
			() -> roomParticipationService.participate(participantUserId, roomId));

		assertEquals(0, activeParticipantCount(roomId));
		assertEquals(0, participationCount(roomId, participantUserId));
		assertEquals(List.of(), eventTypes(roomId));
	}

	@Test
	void 낙관락_재시도는_첫_시도_Outbox를_롤백하고_같은_occurredAt의_최종_한건만_남긴다() {
		long hostUserId = user("retry-host");
		long participantUserId = user("retry-participant");
		long roomId = room(hostUserId, 2);
		AtomicInteger attempts = new AtomicInteger();
		List<Instant> occurredAts = new ArrayList<>();
		doAnswer(invocation -> {
			RoomChangeEvent event = invocation.getArgument(0);
			occurredAts.add(event.occurredAt());
			invocation.callRealMethod();
			if (attempts.incrementAndGet() == 1) {
				throw new jakarta.persistence.OptimisticLockException("first attempt");
			}
			return null;
		}).when(roomChangeEventRecorder).record(any(RoomChangeEvent.class), any());

		roomParticipationService.participate(participantUserId, roomId);

		assertEquals(2, attempts.get());
		assertEquals(List.of(occurredAts.getFirst(), occurredAts.getFirst()), occurredAts);
		assertEquals(1, activeParticipantCount(roomId));
		assertEquals(1, participationCount(roomId, participantUserId));
		assertEquals(List.of("PARTICIPATION_JOINED"), eventTypes(roomId));
	}

	private long user(String prefix) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (concat(?, '-', nextval('users_id_seq'), '@example.com'), 'fixture-password-hash', ?, now(), now()) returning id",
			Long.class,
			prefix,
			prefix);
	}

	private long room(long hostUserId, int capacity) {
		return jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) "
				+ "values (?, 'PERSON_FOCUSED', 'Outbox 경쟁 검증 방', 'ALL_LEVELS', false, ?, 0, "
				+ "clock_timestamp() + interval '1 day', '홍대', 'RECRUITING', now(), now()) returning id",
			Long.class,
			hostUserId,
			capacity);
	}

	private String roomStatus(long roomId) {
		return jdbcTemplate.queryForObject("select status from rooms where id = ?", String.class, roomId);
	}

	private void assertParticipationRejected(java.util.concurrent.ExecutionException executionException) {
		assertTrue(executionException.getCause() instanceof BusinessException);
		BusinessException exception = (BusinessException)executionException.getCause();
		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, exception.getErrorCode());
	}

	private int activeParticipantCount(long roomId) {
		return jdbcTemplate.queryForObject("select active_participant_count from rooms where id = ?", Integer.class,
			roomId);
	}

	private int participationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ?", Integer.class, roomId, userId);
	}

	private String participationStatus(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select status from participations where room_id = ? and user_id = ?", String.class, roomId, userId);
	}

	private List<String> eventTypes(long roomId) {
		return jdbcTemplate.queryForList(
			"select event_type from notification_outbox_events where room_id = ? order by id", String.class, roomId);
	}

	private List<Long> recipients(long roomId, String eventType) {
		return jdbcTemplate.queryForList("""
			select recipient.recipient_user_id
			from notification_outbox_recipients recipient
			join notification_outbox_events event on event.id = recipient.outbox_event_id
			where event.room_id = ? and event.event_type = ?
			order by recipient.recipient_user_id
			""", Long.class, roomId, eventType);
	}
}
