package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;

@Testcontainers
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false",
	"spring.main.allow-bean-definition-overriding=true"})
@Import(RoomCommandRequestTimeRetryBoundaryPostgresTest.RetryBoundaryConfiguration.class)
class RoomCommandRequestTimeRetryBoundaryPostgresTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-12T00:00:00Z");
	private static final Instant RETRY_WALL_CLOCK_TIME = REQUEST_TIME.plusSeconds(60);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_room_command_retry_boundary");

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;
	@Autowired
	private RoomStatusCorrectionCoordinator roomStatusCorrectionCoordinator;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RetryBoundaryGate retryBoundaryGate;
	@Autowired
	private SteppingClock steppingClock;

	@AfterEach
	void tearDown() {
		retryBoundaryGate.reset();
		steppingClock.reset();
		jdbcTemplate.execute("truncate table participations, room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void 재시도는_최신_ROOM_version을_다시_읽되_최초_요청_시각으로_참가를_재판정한다() {
		long hostUserId = insertUser("retry-boundary-host@example.com");
		long participantUserId = insertUser("retry-boundary-participant@example.com");
		long roomId = insertRoom(hostUserId, REQUEST_TIME.plusSeconds(1));
		retryBoundaryGate.changeStartAtAfterFirstRead(roomId, REQUEST_TIME.plusSeconds(30));

		roomParticipationService.participate(participantUserId, roomId);

		assertEquals(1, steppingClock.instantCallCount());
		assertEquals(List.of(0L, 1L), retryBoundaryGate.readVersions());
		assertEquals(2L, roomVersion(roomId));
		assertEquals(1, activeParticipationCount(roomId, participantUserId));
	}

	@Test
	void 최신_업무_오류는_첫_충돌_뒤_우선하고_세_번_충돌한_경우만_동시_수정_오류가_된다() {
		long hostUserId = insertUser("retry-priority-host@example.com");
		long participantUserId = insertUser("retry-priority-participant@example.com");
		long roomId = insertRoom(hostUserId, REQUEST_TIME.plusSeconds(1));
		retryBoundaryGate.changeStartAtAfterFirstRead(roomId, REQUEST_TIME.minusSeconds(1));

		BusinessException businessException = assertThrows(
			BusinessException.class,
			() -> roomParticipationService.participate(participantUserId, roomId));

		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, businessException.getErrorCode());
		assertEquals(List.of(0L, 1L), retryBoundaryGate.readVersions());
		assertEquals(0, activeParticipationCount(roomId, participantUserId));

		retryBoundaryGate.reset();
		steppingClock.reset();
		long exhaustedRoomId = insertRoom(hostUserId, REQUEST_TIME.plusSeconds(120));
		retryBoundaryGate.conflictOnEveryRead(exhaustedRoomId);

		BusinessException exhaustedException = assertThrows(
			BusinessException.class,
			() -> roomParticipationService.participate(participantUserId, exhaustedRoomId));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exhaustedException.getErrorCode());
		assertEquals(List.of(0L, 1L, 2L), retryBoundaryGate.readVersions());
		assertEquals(0, activeParticipationCount(exhaustedRoomId, participantUserId));
	}

	@Test
	void 상태_보정과_참가_대기_취소는_양쪽_확정_순서에서_시작_뒤_결과를_보존한다() {
		for (CommitOrder order : CommitOrder.values()) {
			String suffix = String.valueOf(order.ordinal());
			long hostUserId = insertUser("boundary-host-" + suffix + "@example.com");
			long participantUserId = insertUser("boundary-participant-" + suffix + "@example.com");
			long directJoinRoomId = insertRoom(hostUserId, REQUEST_TIME);

			assertInCommitOrder(
				order,
				directJoinRoomId,
				() -> assertBusinessFailure(
					ErrorCode.ROOM_NOT_RECRUITING,
					() -> roomParticipationService.participate(participantUserId, directJoinRoomId)));
			assertEquals(0, activeParticipationCount(directJoinRoomId, participantUserId));
			assertClosed(directJoinRoomId);

			long waitlistUserId = insertUser("boundary-waiting-" + suffix + "@example.com");
			long waitlistRoomId = insertRoom(hostUserId, REQUEST_TIME);
			jdbcTemplate.update("update rooms set active_participant_count = capacity where id = ?", waitlistRoomId);

			assertWaitlistRegistrationBoundary(order, waitlistRoomId, waitlistUserId);
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from room_waitlists where room_id = ? and user_id = ?", Integer.class,
				waitlistRoomId, waitlistUserId));
			assertClosed(waitlistRoomId);

			long cancelUserId = insertUser("boundary-cancel-" + suffix + "@example.com");
			long cancelRoomId = insertRoom(hostUserId, REQUEST_TIME);
			jdbcTemplate.update("update rooms set active_participant_count = 1 where id = ?", cancelRoomId);
			jdbcTemplate.update("""
				insert into participations (room_id, user_id, status, joined_at, created_at, updated_at)
				values (?, ?, 'ACTIVE', ?, ?, ?)
				""", cancelRoomId, cancelUserId, Timestamp.from(REQUEST_TIME.minusSeconds(1)),
				Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));

			assertInCommitOrder(
				order,
				cancelRoomId,
				() -> assertBusinessFailure(
					ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
					() -> roomParticipationCancelService.cancelParticipation(cancelUserId, cancelRoomId)));
			assertEquals(1, activeParticipationCount(cancelRoomId, cancelUserId));
			assertClosed(cancelRoomId);
		}
	}

	private long insertUser(String email) {
		jdbcTemplate.update("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			values (?, 'fixture-password-hash', ?, ?, ?)
			""", email, email, Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertRoom(long hostUserId, Instant startsAt) {
		jdbcTemplate.update("""
			insert into rooms (
				host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
				active_participant_count, start_at, place, status, version, created_at, updated_at)
			values (?, 'PERSON_FOCUSED', '재시도 경계 방', 'ALL_LEVELS', false, '홍대', 3, 0, ?, '테스트 장소',
					'RECRUITING', 0, ?, ?)
			""", hostUserId, Timestamp.from(startsAt), Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from rooms where host_user_id = ? order by id desc limit 1",
			Long.class, hostUserId);
	}

	private long roomVersion(long roomId) {
		return jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId);
	}

	private int activeParticipationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ? and status = 'ACTIVE'",
			Integer.class,
			roomId,
			userId);
	}

	private void assertInCommitOrder(CommitOrder order, long roomId, Runnable command) {
		if (order == CommitOrder.CORRECTION_FIRST) {
			roomStatusCorrectionCoordinator.correctRoom(roomId, REQUEST_TIME);
			command.run();
			return;
		}
		command.run();
		roomStatusCorrectionCoordinator.correctRoom(roomId, REQUEST_TIME);
	}

	private void assertWaitlistRegistrationBoundary(CommitOrder order, long roomId, long waitlistUserId) {
		if (order == CommitOrder.CORRECTION_FIRST) {
			roomStatusCorrectionCoordinator.correctRoom(roomId, REQUEST_TIME);
			assertClosedAtVersion(roomId, 1L);
			assertBusinessFailure(
				ErrorCode.WAITLIST_NOT_AVAILABLE,
				() -> roomWaitlistCommandService.register(waitlistUserId, roomId));
		} else {
			assertBusinessFailure(
				ErrorCode.WAITLIST_NOT_AVAILABLE,
				() -> roomWaitlistCommandService.register(waitlistUserId, roomId));
			assertRecruitingAtVersion(roomId, 0L);
			roomStatusCorrectionCoordinator.correctRoom(roomId, REQUEST_TIME);
			assertClosedAtVersion(roomId, 1L);
		}
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and user_id = ?", Integer.class,
			roomId, waitlistUserId));
	}

	private void assertBusinessFailure(ErrorCode expectedErrorCode, Runnable action) {
		BusinessException exception = assertThrows(BusinessException.class, action::run);
		assertEquals(expectedErrorCode, exception.getErrorCode());
	}

	private void assertClosed(long roomId) {
		assertEquals("CLOSED",
			jdbcTemplate.queryForObject("select status from rooms where id = ?", String.class, roomId));
	}

	private void assertClosedAtVersion(long roomId, long expectedVersion) {
		assertClosed(roomId);
		assertEquals(expectedVersion, roomVersion(roomId));
	}

	private void assertRecruitingAtVersion(long roomId, long expectedVersion) {
		assertEquals("RECRUITING",
			jdbcTemplate.queryForObject("select status from rooms where id = ?", String.class, roomId));
		assertEquals(expectedVersion, roomVersion(roomId));
	}

	private enum CommitOrder {
		CORRECTION_FIRST,
		COMMAND_FIRST
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RetryBoundaryConfiguration {

		@Bean
		@Primary
		SteppingClock steppingClock() {
			return new SteppingClock(REQUEST_TIME, RETRY_WALL_CLOCK_TIME);
		}

		@Bean("auditingDateTimeProvider")
		DateTimeProvider fixedAuditingDateTimeProvider() {
			return () -> Optional.of(REQUEST_TIME);
		}

		@Bean
		RetryBoundaryGate retryBoundaryGate(PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
			return new RetryBoundaryGate(transactionManager, jdbcTemplate);
		}

		@Bean(name = "retryBoundaryRoomRepository")
		@Primary
		RoomRepository retryBoundaryRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate, RetryBoundaryGate retryBoundaryGate) {
			InvocationHandler handler = new RetryBoundaryRoomRepositoryInvocationHandler(delegate, retryBoundaryGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
		}
	}

	static final class SteppingClock extends Clock {

		private final List<Instant> instants;
		private int instantCallCount;

		SteppingClock(Instant... instants) {
			this.instants = List.of(instants);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			Instant instant = instants.get(Math.min(instantCallCount, instants.size() - 1));
			instantCallCount++;
			return instant;
		}

		void reset() {
			instantCallCount = 0;
		}

		int instantCallCount() {
			return instantCallCount;
		}
	}

	static final class RetryBoundaryGate {

		private final TransactionTemplate requiresNewTransaction;
		private final JdbcTemplate jdbcTemplate;
		private final List<Long> readVersions = new ArrayList<>();
		private long roomId;
		private Instant replacementStartAt;
		private boolean conflictOnEveryRead;

		RetryBoundaryGate(PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
			requiresNewTransaction = new TransactionTemplate(transactionManager);
			requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		}

		void changeStartAtAfterFirstRead(long roomId, Instant replacementStartAt) {
			this.roomId = roomId;
			this.replacementStartAt = replacementStartAt;
			conflictOnEveryRead = false;
		}

		void conflictOnEveryRead(long roomId) {
			this.roomId = roomId;
			replacementStartAt = null;
			conflictOnEveryRead = true;
		}

		void afterRead(long readRoomId, Optional<Room> room) {
			if (readRoomId != roomId || room.isEmpty()) {
				return;
			}
			readVersions.add(room.get().getVersion());
			boolean shouldConflict = conflictOnEveryRead || readVersions.size() == 1;
			if (!shouldConflict) {
				return;
			}
			requiresNewTransaction.executeWithoutResult(status -> {
				if (replacementStartAt == null) {
					jdbcTemplate.update("update rooms set version = version + 1 where id = ?", roomId);
					return;
				}
				jdbcTemplate.update("update rooms set start_at = ?, version = version + 1 where id = ?",
					Timestamp.from(replacementStartAt), roomId);
			});
		}

		List<Long> readVersions() {
			return List.copyOf(readVersions);
		}

		void reset() {
			readVersions.clear();
			roomId = 0L;
			replacementStartAt = null;
			conflictOnEveryRead = false;
		}
	}

	private static final class RetryBoundaryRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final RetryBoundaryGate retryBoundaryGate;

		private RetryBoundaryRoomRepositoryInvocationHandler(RoomRepository delegate,
			RetryBoundaryGate retryBoundaryGate) {
			this.delegate = delegate;
			this.retryBoundaryGate = retryBoundaryGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				if (method.getName().equals("findById") && arguments != null && arguments.length == 1
					&& arguments[0] instanceof Long roomId && result instanceof Optional<?> optionalResult) {
					@SuppressWarnings("unchecked") Optional<Room> room = (Optional<Room>)optionalResult;
					retryBoundaryGate.afterRead(roomId, room);
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}
}
