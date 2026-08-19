package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false",
	"spring.main.allow-bean-definition-overriding=true"})
@Import(RoomCommandRequestTimeRetryBoundaryPostgresTest.RetryBoundaryConfiguration.class)
class RoomCommandRequestTimeRetryBoundaryPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-12T00:00:00Z");
	private static final Instant RETRY_WALL_CLOCK_TIME = REQUEST_TIME.plusSeconds(60);

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
	private SteppingClock steppingClock;

	@AfterEach
	void tearDown() {
		steppingClock.reset();
		jdbcTemplate.execute("truncate table participations, room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void 재시도는_최신_ROOM_version을_다시_읽되_최초_요청_시각으로_참가를_재판정한다() {
		long hostUserId = insertUser("retry-boundary-host@example.com");
		long participantUserId = insertUser("retry-boundary-participant@example.com");
		long roomId = insertRoom(hostUserId, REQUEST_TIME.plusSeconds(1));

		roomParticipationService.participate(participantUserId, roomId);

		assertEquals(1, steppingClock.instantCallCount());
		assertEquals(1L, roomVersion(roomId));
		assertEquals(1, activeParticipationCount(roomId, participantUserId));
	}

	@Test
	void 최신_업무_오류는_비관락_경로에서_재시도하지_않고_즉시_반환된다() {
		long hostUserId = insertUser("retry-priority-host@example.com");
		long participantUserId = insertUser("retry-priority-participant@example.com");
		long roomId = insertRoom(hostUserId, REQUEST_TIME.minusSeconds(1));

		BusinessException businessException = assertThrows(
			BusinessException.class,
			() -> roomParticipationService.participate(participantUserId, roomId));

		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, businessException.getErrorCode());
		assertEquals(0, activeParticipationCount(roomId, participantUserId));
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

}
