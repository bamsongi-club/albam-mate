package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;

@Testcontainers
@SpringBootTest
@Import(RoomRejoinPromotionConcurrencyPostgresTest.ConcurrencyTestConfiguration.class)
class RoomRejoinPromotionConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_rejoin_promotion_test");

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;

	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;

	@Autowired
	private ParticipationLookupGate participationLookupGate;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	@Qualifier("participationRepository") private ParticipationRepository participationRepository;

	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		participationLookupGate.deactivate();
		jdbcTemplate.execute(
			"truncate table room_waitlists, participations, rooms, users restart identity cascade");
	}

	@Test
	void 직접_재참가가_먼저_만석을_판정하면_취소_뒤_기존_관계만_자동_승격된다() throws Exception {
		RejoinPromotionFixture fixture = createFixture("rejoin-promotion-rejoin-first");
		EventCounts before = eventCounts(fixture.roomId());
		PromotionRecipientCounts promotionRecipientsBefore = promotionRecipientCounts(fixture);
		participationLookupGate.activate(fixture.roomId(), fixture.rejoiningUserId());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<CommandResult> rejoinFuture = executor.submit(
				() -> participate(fixture.rejoiningUserId(), fixture.roomId()));

			participationLookupGate.awaitBlocked();
			participationLookupGate.release();
			assertEquals(
				ErrorCode.CAPACITY_EXCEEDED,
				rejoinFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).errorCode());

			roomParticipationCancelService.cancelParticipation(fixture.leavingUserId(), fixture.roomId());
		} finally {
			participationLookupGate.release();
			participationLookupGate.deactivate();
			shutdown(executor);
		}

		assertPromotionResult(fixture, before, promotionRecipientsBefore);
	}

	@Test
	void 취소와_자동_승격이_먼저_커밋되면_직접_재참가는_이미_참가_중으로_거절된다() throws Exception {
		RejoinPromotionFixture fixture = createFixture("rejoin-promotion-cancel-first");
		EventCounts before = eventCounts(fixture.roomId());
		PromotionRecipientCounts promotionRecipientsBefore = promotionRecipientCounts(fixture);
		participationLookupGate.activate(fixture.roomId(), fixture.rejoiningUserId());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<CommandResult> rejoinFuture = executor.submit(
				() -> participate(fixture.rejoiningUserId(), fixture.roomId()));

			participationLookupGate.awaitBlocked();
			roomParticipationCancelService.cancelParticipation(fixture.leavingUserId(), fixture.roomId());
			participationLookupGate.release();
			assertEquals(
				ErrorCode.ALREADY_PARTICIPATING,
				rejoinFuture.get(WAIT_SECONDS, TimeUnit.SECONDS).errorCode());
		} finally {
			participationLookupGate.release();
			participationLookupGate.deactivate();
			shutdown(executor);
		}

		assertPromotionResult(fixture, before, promotionRecipientsBefore);
	}

	private RejoinPromotionFixture createFixture(String emailPrefix) {
		long hostUserId = insertUser(emailPrefix + "-host", "방장");
		long rejoiningUserId = insertUser(emailPrefix + "-rejoining", "재참가자");
		long leavingUserId = insertUser(emailPrefix + "-leaving", "취소자");
		Room room = createRoom(hostUserId);

		roomParticipationService.participate(rejoiningUserId, room.getId());
		roomParticipationCancelService.cancelParticipation(rejoiningUserId, room.getId());
		Long canceledParticipationId = participationRepository
			.findByRoomIdAndUserId(room.getId(), rejoiningUserId)
			.orElseThrow()
			.getId();
		roomParticipationService.participate(leavingUserId, room.getId());
		assertTrue(roomWaitlistCommandService.register(rejoiningUserId, room.getId()).created());

		return new RejoinPromotionFixture(
			room.getId(), rejoiningUserId, leavingUserId, canceledParticipationId);
	}

	private CommandResult participate(long userId, long roomId) {
		try {
			roomParticipationService.participate(userId, roomId);
			return CommandResult.success();
		} catch (BusinessException exception) {
			return CommandResult.failure(exception.getErrorCode());
		}
	}

	private void assertPromotionResult(
		RejoinPromotionFixture fixture,
		EventCounts eventsBefore,
		PromotionRecipientCounts promotionRecipientsBefore) {
		Participation participation = participationRepository
			.findByRoomIdAndUserId(fixture.roomId(), fixture.rejoiningUserId())
			.orElseThrow();
		assertEquals(fixture.canceledParticipationId(), participation.getId());
		assertEquals(ParticipationStatus.ACTIVE, participation.getStatus());
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*)
			from participations
			where room_id = ? and user_id = ?
			""", Integer.class, fixture.roomId(), fixture.rejoiningUserId()));
		assertEquals(1, jdbcTemplate.queryForObject("""
			select count(*)
			from participations
			where room_id = ? and user_id = ? and status = 'ACTIVE'
			""", Integer.class, fixture.roomId(), fixture.rejoiningUserId()));
		assertEquals(
			RoomWaitlistStatus.PROMOTED,
			roomWaitlistRepository
				.findById(new RoomWaitlistId(fixture.roomId(), fixture.rejoiningUserId()))
				.orElseThrow()
				.getStatus());
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from room_waitlists
			where room_id = ? and user_id = ? and status = 'WAITING'
			""", Integer.class, fixture.roomId(), fixture.rejoiningUserId()));

		Room room = roomRepository.findById(fixture.roomId()).orElseThrow();
		int activeParticipationCount = jdbcTemplate.queryForObject("""
			select count(*)
			from participations
			where room_id = ? and status = 'ACTIVE'
			""", Integer.class, fixture.roomId());
		assertEquals(activeParticipationCount, room.getActiveParticipantCount());
		assertTrue(room.getActiveParticipantCount() <= room.getCapacity());

		EventCounts after = eventCounts(fixture.roomId());
		assertEquals(eventsBefore.waitlistPromoted() + 1, after.waitlistPromoted());
		assertEquals(eventsBefore.participationJoined(), after.participationJoined());
		assertEquals(eventsBefore.participationCanceled(), after.participationCanceled());
		PromotionRecipientCounts promotionRecipientsAfter = promotionRecipientCounts(fixture);
		assertEquals(
			promotionRecipientsBefore.totalRecipients() + 1,
			promotionRecipientsAfter.totalRecipients());
		assertEquals(
			promotionRecipientsBefore.rejoiningUserRecipients() + 1,
			promotionRecipientsAfter.rejoiningUserRecipients());
	}

	private EventCounts eventCounts(long roomId) {
		int waitlistPromoted = jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_events
			where room_id = ? and event_type = 'WAITLIST_PROMOTED'
			""", Integer.class, roomId);
		int participationJoined = jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_events
			where room_id = ? and event_type = 'PARTICIPATION_JOINED'
			""", Integer.class, roomId);
		int participationCanceled = jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_events
			where room_id = ? and event_type = 'PARTICIPATION_CANCELED'
			""", Integer.class, roomId);
		return new EventCounts(waitlistPromoted, participationJoined, participationCanceled);
	}

	private PromotionRecipientCounts promotionRecipientCounts(RejoinPromotionFixture fixture) {
		int totalRecipients = jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_recipients recipient
			join notification_outbox_events event on event.id = recipient.outbox_event_id
			where event.room_id = ? and event.event_type = 'WAITLIST_PROMOTED'
			""", Integer.class, fixture.roomId());
		int rejoiningUserRecipients = jdbcTemplate.queryForObject("""
			select count(*)
			from notification_outbox_recipients recipient
			join notification_outbox_events event on event.id = recipient.outbox_event_id
			where event.room_id = ?
			  and event.event_type = 'WAITLIST_PROMOTED'
			  and recipient.recipient_user_id = ?
			""", Integer.class, fixture.roomId(), fixture.rejoiningUserId());
		return new PromotionRecipientCounts(totalRecipients, rejoiningUserRecipients);
	}

	private Room createRoom(long hostUserId) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"재참가 승격 동시성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대 테스트 장소",
				1));
	}

	private long insertUser(String emailPrefix, String nickname) {
		String email = emailPrefix + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-08-10T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-08-10T00:00:00Z')",
			email,
			nickname);
		Long userId = jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
		assertNotNull(userId);
		return userId;
	}

	private void shutdown(ExecutorService executor) throws InterruptedException {
		executor.shutdown();
		if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	private record RejoinPromotionFixture(
		long roomId, long rejoiningUserId, long leavingUserId, long canceledParticipationId) {
	}

	private record CommandResult(ErrorCode errorCode) {

		private static CommandResult success() {
			return new CommandResult(null);
		}

		private static CommandResult failure(ErrorCode errorCode) {
			return new CommandResult(errorCode);
		}
	}

	private record EventCounts(int waitlistPromoted, int participationJoined, int participationCanceled) {
	}

	private record PromotionRecipientCounts(int totalRecipients, int rejoiningUserRecipients) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConcurrencyTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		ParticipationLookupGate participationLookupGate() {
			return new ParticipationLookupGate();
		}

		@Bean(name = "gatedParticipationRepository")
		@Primary
		ParticipationRepository gatedParticipationRepository(
			@Qualifier("participationRepository") ParticipationRepository delegate,
			ParticipationLookupGate participationLookupGate) {
			InvocationHandler handler = new GateAwareParticipationRepositoryInvocationHandler(
				delegate, participationLookupGate);
			return (ParticipationRepository)Proxy.newProxyInstance(
				ParticipationRepository.class.getClassLoader(),
				new Class<?>[] {ParticipationRepository.class},
				handler);
		}
	}

	private static final class GateAwareParticipationRepositoryInvocationHandler
		implements InvocationHandler {

		private final ParticipationRepository delegate;
		private final ParticipationLookupGate participationLookupGate;

		private GateAwareParticipationRepositoryInvocationHandler(
			ParticipationRepository delegate, ParticipationLookupGate participationLookupGate) {
			this.delegate = delegate;
			this.participationLookupGate = participationLookupGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				participationLookupGate.blockBeforeFirstTargetLookup(method, args);
				return method.invoke(delegate, args);
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	static final class ParticipationLookupGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId, long userId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId, userId)));
		}

		void blockBeforeFirstTargetLookup(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null
				|| !method.getName().equals("findByRoomIdAndUserId")
				|| arguments == null
				|| arguments.length != 2
				|| !(arguments[0] instanceof Long roomId)
				|| !(arguments[1] instanceof Long userId)
				|| scenario.roomId != roomId
				|| scenario.userId != userId
				|| !scenario.firstTargetLookup.compareAndSet(false, true)) {
				return;
			}

			scenario.blocked.countDown();
			await(scenario.mayContinue);
		}

		void awaitBlocked() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			await(scenario.blocked);
		}

		void release() {
			Scenario scenario = activeScenario.get();
			if (scenario != null) {
				scenario.mayContinue.countDown();
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
				throw new AssertionError("재참가 참가 관계 조회 게이트 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final long userId;
			private final AtomicBoolean firstTargetLookup = new AtomicBoolean();
			private final CountDownLatch blocked = new CountDownLatch(1);
			private final CountDownLatch mayContinue = new CountDownLatch(1);

			private Scenario(long roomId, long userId) {
				this.roomId = roomId;
				this.userId = userId;
			}
		}
	}
}
