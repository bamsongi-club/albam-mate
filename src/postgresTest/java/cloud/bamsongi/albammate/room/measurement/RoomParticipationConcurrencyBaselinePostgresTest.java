package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import jakarta.persistence.OptimisticLockException;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=10")
@Import(RoomParticipationConcurrencyBaselinePostgresTest.BaselineTestConfiguration.class)
class RoomParticipationConcurrencyBaselinePostgresTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final String FIXTURE_SEED = "ROOM-10A-20260806";
	private static final String FIXTURE_STATEMENT_MARKER = "room10a_fixture_marker";
	private static final String DETERMINISTIC_RETRY_EVENT = "room_10a_retry";
	private static final String PARTICIPATION_RETRY_EVENT = "room_participation_retry";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
		.withDatabaseName("albam_mate_room_10a");

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomOptimisticLockRetrier roomOptimisticLockRetrier;

	@Autowired
	private RoomConcurrencyBaselineSupport baselineSupport;

	@Autowired
	private RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void enablePgStatStatements() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
	}

	@AfterEach
	void tearDown() {
		roomReadGate.deactivate();
		jdbcTemplate.execute("truncate table participations, rooms, users restart identity cascade");
	}

	@Test
	void 결정적_attempt_gate는_시도_충돌_재시도_소진을_구분한다() {
		RoomConcurrencyBaselineSupport.RetryMeasurement firstSuccess = retryMeasurement(
			RoomConcurrencyBaselineSupport.AttemptPlan.success());
		RoomConcurrencyBaselineSupport.RetryMeasurement oneConflictThenSuccess = retryMeasurement(
			RoomConcurrencyBaselineSupport.AttemptPlan.conflict(),
			RoomConcurrencyBaselineSupport.AttemptPlan.success());
		RoomConcurrencyBaselineSupport.RetryMeasurement twoConflictsThenSuccess = retryMeasurement(
			RoomConcurrencyBaselineSupport.AttemptPlan.conflict(),
			RoomConcurrencyBaselineSupport.AttemptPlan.conflict(),
			RoomConcurrencyBaselineSupport.AttemptPlan.success());
		RoomConcurrencyBaselineSupport.RetryMeasurement exhausted = retryMeasurement(
			RoomConcurrencyBaselineSupport.AttemptPlan.conflict(),
			RoomConcurrencyBaselineSupport.AttemptPlan.conflict(),
			RoomConcurrencyBaselineSupport.AttemptPlan.conflict());

		assertRetryMeasurement(firstSuccess, 1, 0, 0, false);
		assertRetryMeasurement(oneConflictThenSuccess, 2, 1, 1, false);
		assertRetryMeasurement(twoConflictsThenSuccess, 3, 2, 2, false);
		assertRetryMeasurement(exhausted, 3, 3, 2, true);
	}

	@Test
	void 마지막_좌석_동시_참가는_모든_고정_수준에서_한_건만_성공한다() throws Exception {
		for (int concurrencyLevel : List.of(2, 4, 8)) {
			LastSeatFixture fixture = createLastSeatFixture(concurrencyLevel, 0);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureLastSeatRound(fixture);

			assertEquals(1, measurement.successCount());
			assertEquals(concurrencyLevel - 1, measurement.businessFailureCount());
			assertEquals(0, measurement.concurrencyFailureCount());
			assertEquals(0, measurement.technicalFailureCount());
			assertTrue(measurement.hasOnlyBusinessError(ErrorCode.CAPACITY_EXCEEDED));
		}
	}

	@Test
	void 마지막_좌석_측정_round는_결과_분포_재시도_응답시간과_PostgreSQL_비용을_원자료로_남긴다()
		throws Exception {
		for (int concurrencyLevel : List.of(2, 4, 8)) {
			runLastSeatPreparationRound(createLastSeatFixture(concurrencyLevel, 0));
			for (int round = 1; round <= 3; round++) {
				LastSeatFixture fixture = createLastSeatFixture(concurrencyLevel, round);
				RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureLastSeatRound(fixture);

				assertEquals(concurrencyLevel, measurement.totalRequestCount());
				assertEquals(
					concurrencyLevel,
					measurement.successCount()
						+ measurement.businessFailureCount()
						+ measurement.concurrencyFailureCount()
						+ measurement.technicalFailureCount());
				assertEquals(concurrencyLevel, measurement.retryCount(0)
					+ measurement.retryCount(1)
					+ measurement.retryCount(2));
				assertEquals(measurement.totalRetryCount(), measurement.retryAttemptLogCount());
				assertEquals(measurement.concurrencyFailureCount(), measurement.exhaustedLogCount());
				assertParticipationRetryLogFormat(measurement, fixture.room().getId());
				assertEquals(measurement.concurrencyFailureCount(), measurement.exhaustedCount());
				assertTrue(measurement.requestDurationsNanos().stream().allMatch(duration -> duration > 0));
				assertTrue(measurement.postgresCost().statementCalls() > 0);
				assertTrue(measurement.rawRecord().startsWith("ROOM10A_RAW"));
				assertTrue(measurement.rawRecord().contains("conflictCount="));
				assertTrue(measurement.rawRecord().contains("conflictRate="));
				assertTrue(measurement.rawRecord().contains("retry0="));
				assertTrue(measurement.rawRecord().contains("retry2="));
				assertTrue(measurement.rawRecord().contains("exhausted="));
				assertTrue(measurement.rawRecord().contains("responseNanos="));
				assertTrue(measurement.rawRecord().contains("sharedBlksRead="));
			}
		}
	}

	@Test
	void 각_측정_round_뒤_PostgreSQL_ROOM과_ACTIVE_참가_불변식이_유지된다() throws Exception {
		for (int concurrencyLevel : List.of(2, 4, 8)) {
			for (int round = 1; round <= 3; round++) {
				LastSeatFixture fixture = createLastSeatFixture(concurrencyLevel, round);
				measureLastSeatRound(fixture);

				RoomConcurrencyBaselineSupport.RoomInvariant invariant = baselineSupport.readRoomInvariant(
					fixture.room().getId());
				assertEquals(1, invariant.activeParticipantCount());
				assertEquals(invariant.activeParticipantCount(), invariant.activeParticipationCount());
				assertTrue(invariant.activeParticipantCount() >= 0);
				assertTrue(invariant.activeParticipantCount() <= invariant.capacity());
				assertEquals(RoomStatus.CLOSED, invariant.status());
				assertFalse(invariant.hasDuplicatedActiveParticipation());
			}
		}
	}

	@Test
	void 세_번의_독립_트랜잭션이_모두_충돌하면_ROOM_CONCURRENT_MODIFICATION을_반환한다() {
		RoomConcurrencyBaselineSupport.RetryMeasurement measurement = baselineSupport.newRetryMeasurement();

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> measurement.execute(roomOptimisticLockRetrier, DETERMINISTIC_RETRY_EVENT, () -> {
				throw new OptimisticLockException("deterministic conflict");
			}));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		assertEquals(3, measurement.attemptCount());
		assertEquals(3, measurement.conflictCount());
		assertEquals(3, measurement.transactionIds().size());
		assertEquals(3, measurement.transactionIds().stream().distinct().count());
		assertEquals(2, measurement.retryCount());
		assertTrue(measurement.exhausted());
	}

	@Test
	void 충돌_뒤_최신_업무_오류는_남은_재시도_없이_우선_반환한다() {
		long hostUserId = baselineSupport.insertUser(FIXTURE_SEED + "-business-host", "방장");
		long activeUserId = baselineSupport.insertUser(FIXTURE_SEED + "-business-active", "기존참가자");
		Room room = baselineSupport.createRoom(hostUserId, 1, NOW);
		roomParticipationService.participate(activeUserId, room.getId());

		AtomicInteger invocation = new AtomicInteger();
		RoomConcurrencyBaselineSupport.RetryMeasurement measurement = baselineSupport.newRetryMeasurement();

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> measurement.execute(roomOptimisticLockRetrier, DETERMINISTIC_RETRY_EVENT, () -> {
				if (invocation.getAndIncrement() == 0) {
					throw new OptimisticLockException("deterministic conflict");
				}
				int activeParticipantCount = jdbcTemplate.queryForObject(
					"select active_participant_count from rooms where id = ?", Integer.class, room.getId());
				assertEquals(1, activeParticipantCount);
				throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED);
			}));

		assertEquals(ErrorCode.CAPACITY_EXCEEDED, exception.getErrorCode());
		assertEquals(2, measurement.attemptCount());
		assertEquals(1, measurement.conflictCount());
		assertEquals(2, measurement.transactionIds().size());
		assertEquals(2, measurement.transactionIds().stream().distinct().count());
		assertEquals(1, measurement.businessFailureCount());
		assertFalse(measurement.exhausted());
		assertEquals(0, measurement.technicalFailureCount());
	}

	private RoomConcurrencyBaselineSupport.RetryMeasurement retryMeasurement(
		RoomConcurrencyBaselineSupport.AttemptPlan... plans) {
		RoomConcurrencyBaselineSupport.RetryMeasurement measurement = baselineSupport.newRetryMeasurement();
		measurement.executeDeterministic(roomOptimisticLockRetrier, DETERMINISTIC_RETRY_EVENT, List.of(plans));
		return measurement;
	}

	private void assertRetryMeasurement(
		RoomConcurrencyBaselineSupport.RetryMeasurement measurement,
		int attempts,
		int conflicts,
		int retries,
		boolean exhausted) {
		assertEquals(attempts, measurement.attemptCount());
		assertEquals(conflicts, measurement.conflictCount());
		assertEquals(retries, measurement.retryCount());
		assertEquals(exhausted, measurement.exhausted());

		List<Integer> expectedAttempts = new ArrayList<>();
		for (int attempt = 2; attempt <= attempts; attempt++) {
			expectedAttempts.add(attempt);
		}
		if (exhausted) {
			expectedAttempts.add(attempts);
		}

		List<RoomConcurrencyBaselineSupport.RetryLogRecord> retryLogs = measurement.retryLogRecords();
		assertEquals(expectedAttempts, retryLogs.stream()
			.map(RoomConcurrencyBaselineSupport.RetryLogRecord::attempt)
			.toList());
		assertTrue(retryLogs.stream().allMatch(log -> DETERMINISTIC_RETRY_EVENT.equals(log.event())));
		assertTrue(retryLogs.stream().allMatch(log -> log.roomId() == null));
		assertEquals(retries, retryLogs.stream()
			.filter(RoomConcurrencyBaselineSupport.RetryLogRecord::retryAttempt)
			.count());
		assertEquals(exhausted ? 1 : 0, retryLogs.stream()
			.filter(RoomConcurrencyBaselineSupport.RetryLogRecord::exhaustedAttempt)
			.count());
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureLastSeatRound(LastSeatFixture fixture)
		throws Exception {
		roomReadGate.activate(fixture.room().getId(), fixture.participantUserIds().size());
		try {
			jdbcTemplate.execute("select id as room10a_fixture_marker from users limit 0");
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = baselineSupport.measureRound(
				"last-seat",
				fixture.participantUserIds().size(),
				roomReadGate,
				lastSeatCommands(fixture));
			assertEquals(0L, baselineSupport.statementCallsContaining(FIXTURE_STATEMENT_MARKER));
			roomReadGate.assertInitialReadsShareOneVersion();
			return measurement;
		} finally {
			roomReadGate.deactivate();
		}
	}

	private void assertParticipationRetryLogFormat(
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement,
		long roomId) {
		List<RoomConcurrencyBaselineSupport.RetryLogRecord> retryLogs = measurement.retryLogRecords();
		assertEquals(
			measurement.totalRetryCount() + measurement.concurrencyFailureCount(),
			retryLogs.size());
		assertTrue(retryLogs.stream().allMatch(log -> PARTICIPATION_RETRY_EVENT.equals(log.event())));
		assertTrue(retryLogs.stream().allMatch(log -> Long.valueOf(roomId).equals(log.roomId())));
		assertTrue(retryLogs.stream().allMatch(log -> log.attempt() >= 2 && log.attempt() <= 3));
		assertTrue(retryLogs.stream().allMatch(log -> log.retryAttempt() || log.exhaustedAttempt()));
		assertTrue(retryLogs.stream()
			.filter(RoomConcurrencyBaselineSupport.RetryLogRecord::exhaustedAttempt)
			.allMatch(log -> log.attempt() == 3));
	}

	private void runLastSeatPreparationRound(LastSeatFixture fixture) throws Exception {
		roomReadGate.activate(fixture.room().getId(), fixture.participantUserIds().size());
		try {
			baselineSupport.runPreparationRound(lastSeatCommands(fixture));
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
		}
	}

	private List<java.util.concurrent.Callable<?>> lastSeatCommands(LastSeatFixture fixture) {
		return fixture.participantUserIds().stream().<java.util.concurrent.Callable<?>>map(
			userId -> () -> roomParticipationService.participate(userId, fixture.room().getId()))
			.toList();
	}

	private LastSeatFixture createLastSeatFixture(int concurrencyLevel, int round) {
		String fixtureName = FIXTURE_SEED + "-" + concurrencyLevel + "-" + round;
		long hostUserId = baselineSupport.insertUser(fixtureName + "-host", "방장");
		Room room = baselineSupport.createRoom(hostUserId, 1, NOW);
		List<Long> participantUserIds = new ArrayList<>();
		for (int index = 0; index < concurrencyLevel; index++) {
			participantUserIds.add(baselineSupport.insertUser(fixtureName + "-user-" + index, "참가자" + index));
		}
		return new LastSeatFixture(room, participantUserIds);
	}

	private record LastSeatFixture(Room room, List<Long> participantUserIds) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BaselineTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate() {
			return new RoomConcurrencyBaselineSupport.RoomReadGate();
		}

		@Bean
		RoomConcurrencyBaselineSupport roomConcurrencyBaselineSupport(
			PlatformTransactionManager transactionManager,
			JdbcTemplate jdbcTemplate,
			@Qualifier("roomRepository") RoomRepository roomRepository) {
			return new RoomConcurrencyBaselineSupport(transactionManager, jdbcTemplate, roomRepository);
		}

		@Bean
		@Primary
		RoomOptimisticLockRetrier measuredRoomOptimisticLockRetrier(
			RoomConcurrencyBaselineSupport baselineSupport) {
			return baselineSupport.measuredRetrier();
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			return RoomConcurrencyBaselineSupport.gatedRoomRepository(delegate, roomReadGate);
		}
	}
}
