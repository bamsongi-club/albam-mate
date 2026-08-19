package cloud.bamsongi.albammate.room.measurement;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import jakarta.persistence.OptimisticLockException;
import tools.jackson.databind.ObjectMapper;

/** ROOM-10a PostgreSQL 기준선의 결정적 gate, 재시도·비용 원자료 수집을 묶는다. */
final class RoomConcurrencyBaselineSupport {

	private static final long WAIT_SECONDS = 10;

	private static final Path REPORT_DIRECTORY = Path.of("build", "reports", "measurements");

	private final JdbcTemplate jdbcTemplate;
	private final RoomRepository roomRepository;
	private final TransactionTemplate requiresNewTransaction;
	private final MeasuredRoomOptimisticLockRetrier measuredRetrier = new MeasuredRoomOptimisticLockRetrier();
	private final List<CollectedRound> collectedRounds = new ArrayList<>();

	RoomConcurrencyBaselineSupport(
		PlatformTransactionManager transactionManager,
		JdbcTemplate jdbcTemplate,
		RoomRepository roomRepository) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		requiresNewTransaction = new TransactionTemplate(
			Objects.requireNonNull(transactionManager, "transactionManager"));
		requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	RoomOptimisticLockRetrier measuredRetrier() {
		return measuredRetrier;
	}

	RetryMeasurement newRetryMeasurement() {
		return new RetryMeasurement();
	}

	long insertUser(String emailPrefix, String nickname) {
		String email = emailPrefix + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			email,
			nickname);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	Room createRoom(long hostUserId, int capacity, Instant now) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"ROOM-10a 기준선 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				now.plusSeconds(3600),
				"홍대 테스트 장소",
				capacity));
	}

	RoundMeasurement measureRound(
		String scenario,
		int concurrencyLevel,
		RoomReadGate roomReadGate,
		List<Callable<?>> commands)
		throws Exception {
		resetPostgresStatistics();
		RetryLogCapture retryLogCapture = RetryLogCapture.attach();
		RetryTraceCapture retryTraceCapture = RetryTraceCapture.attach();
		ExecutorService executor = Executors.newFixedThreadPool(commands.size());
		CountDownLatch ready = new CountDownLatch(commands.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<RequestMeasurement>> futures = new ArrayList<>();
			for (Callable<?> command : commands) {
				futures.add(executor.submit(() -> measureRequest(command, roomReadGate, ready, start)));
			}
			await(ready, "동시 요청 준비");
			start.countDown();
			List<RequestMeasurement> requests = new ArrayList<>();
			for (Future<RequestMeasurement> future : futures) {
				requests.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			PostgresCost postgresCost = readPostgresCost();
			String rawRecord = formatRawRecord(scenario, concurrencyLevel, requests, postgresCost);
			LoggerFactory.getLogger(RoomConcurrencyBaselineSupport.class).info(rawRecord);
			RoundMeasurement measurement = new RoundMeasurement(
				requests, postgresCost, rawRecord, retryLogCapture.retryLogRecords(), retryTraceCapture.retryTraces());
			collectedRounds.add(new CollectedRound(scenario, concurrencyLevel, measurement));
			return measurement;
		} finally {
			start.countDown();
			retryTraceCapture.detach();
			retryLogCapture.detach();
			shutdown(executor);
		}
	}

	void runPreparationRound(List<Callable<?>> commands) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(commands.size());
		CountDownLatch ready = new CountDownLatch(commands.size());
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Callable<?> command : commands) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					await(start, "준비 round 동시 요청 시작");
					try {
						return command.call();
					} catch (BusinessException exception) {
						if (exception.getErrorCode() != ErrorCode.CAPACITY_EXCEEDED) {
							throw exception;
						}
						return null;
					}
				}));
			}
			await(ready, "준비 round 동시 요청 준비");
			start.countDown();
			for (Future<?> future : futures) {
				future.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
		} finally {
			start.countDown();
			shutdown(executor);
		}
	}

	RoomInvariant readRoomInvariant(long roomId) {
		Integer activeParticipantCount = jdbcTemplate.queryForObject(
			"select active_participant_count from rooms where id = ?", Integer.class, roomId);
		Integer capacity = jdbcTemplate.queryForObject(
			"select capacity from rooms where id = ?", Integer.class, roomId);
		String status = jdbcTemplate.queryForObject("select status from rooms where id = ?", String.class, roomId);
		Integer activeParticipationCount = jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'", Integer.class, roomId);
		Integer duplicatedActiveParticipationCount = jdbcTemplate.queryForObject(
			"select count(*) from (select user_id from participations where room_id = ? and status = 'ACTIVE' "
				+ "group by user_id having count(*) > 1) duplicated",
			Integer.class,
			roomId);
		return new RoomInvariant(
			activeParticipantCount,
			activeParticipationCount,
			capacity,
			RoomStatus.valueOf(status),
			duplicatedActiveParticipationCount > 0);
	}

	private RequestMeasurement measureRequest(
		Callable<?> command,
		RoomReadGate roomReadGate,
		CountDownLatch ready,
		CountDownLatch start) throws Exception {
		ready.countDown();
		await(start, "동시 요청 시작");
		roomReadGate.armResponseTimer();
		MeasuredRequestTrace trace = measuredRetrier.beginRequest();
		long startedAt = 0L;
		try {
			startedAt = System.nanoTime();
			command.call();
			return RequestMeasurement.success(roomReadGate.elapsedNanosSince(startedAt), trace.retryCount());
		} catch (BusinessException exception) {
			return RequestMeasurement.businessFailure(
				roomReadGate.elapsedNanosSince(startedAt), trace.retryCount(), exception.getErrorCode());
		} catch (Exception exception) {
			return RequestMeasurement.technicalFailure(roomReadGate.elapsedNanosSince(startedAt), trace.retryCount());
		} finally {
			roomReadGate.clearResponseTimer();
			measuredRetrier.endRequest();
		}
	}

	/**
	 * 수집한 측정 round를 JSON 원자료로 보존한다. 로그 출력만으로는 후속 슬라이스가 수준 간 비교에 쓸 입력이
	 * 남지 않으므로, ROOM-09c 기준선과 같은 형식으로 {@code build/reports/measurements/}에 쓴다.
	 */
	Path writeMeasurementReport(
		ObjectMapper objectMapper, String reportName, Map<String, String> configuration) throws Exception {
		if (collectedRounds.isEmpty()) {
			throw new AssertionError("보존할 측정 round가 없습니다: " + reportName);
		}
		Path reportPath = REPORT_DIRECTORY.resolve(reportName + ".json");
		Files.createDirectories(reportPath.getParent());
		Objects.requireNonNull(objectMapper, "objectMapper")
			.writerWithDefaultPrettyPrinter()
			.writeValue(reportPath.toFile(), new MeasurementReport(
				reportName,
				measurementEnvironment(configuration),
				collectedRounds.stream().map(RoomConcurrencyBaselineSupport::toRoundReport).toList()));
		return reportPath;
	}

	int collectedRoundCount() {
		return collectedRounds.size();
	}

	/**
	 * 이 support는 테스트 컨텍스트의 단일 bean이라 측정 round가 테스트 메서드 사이에 누적된다. 원자료를 보존하는
	 * 테스트는 자기 round만 남기도록 시작 시점에 수집분을 비운다.
	 */
	void clearCollectedRounds() {
		collectedRounds.clear();
	}

	private MeasurementEnvironment measurementEnvironment(Map<String, String> configuration) {
		Runtime runtime = Runtime.getRuntime();
		return new MeasurementEnvironment(
			gitSha(),
			System.getProperty("java.version"),
			jdbcTemplate.queryForObject("show server_version", String.class),
			System.getProperty("os.name"),
			System.getProperty("os.version"),
			runtime.availableProcessors(),
			runtime.totalMemory() - runtime.freeMemory(),
			runtime.maxMemory(),
			Map.copyOf(Objects.requireNonNull(configuration, "configuration")));
	}

	private String gitSha() {
		try {
			Process process = new ProcessBuilder(
				"git", "-c", "safe.directory=" + Path.of("").toAbsolutePath(), "rev-parse", "HEAD")
				.redirectErrorStream(true)
				.start();
			if (process.waitFor() == 0) {
				return new String(process.getInputStream().readAllBytes()).trim();
			}
		} catch (Exception ignored) {
			// 측정 원자료가 git을 읽지 못해도 실패 원인을 바꾸지 않는다.
		}
		return "UNAVAILABLE";
	}

	private static RoundReport toRoundReport(CollectedRound collected) {
		RoundMeasurement measurement = collected.measurement();
		long conflictCount = measurement.requests().stream()
			.mapToLong(RequestMeasurement::conflictCount)
			.sum();
		return new RoundReport(
			collected.scenario(),
			collected.concurrencyLevel(),
			measurement.totalRequestCount(),
			measurement.successCount(),
			measurement.businessFailureCount(),
			measurement.concurrencyFailureCount(),
			measurement.technicalFailureCount(),
			conflictCount,
			(double)conflictCount / measurement.totalRequestCount(),
			measurement.retryCount(0),
			measurement.retryCount(1),
			measurement.retryCount(2),
			measurement.exhaustedCount(),
			measurement.requestDurationsNanos(),
			measurement.postgresCost(),
			measurement.rawRecord());
	}

	private void resetPostgresStatistics() {
		jdbcTemplate.execute("select pg_stat_statements_reset()");
	}

	private PostgresCost readPostgresCost() {
		return jdbcTemplate.queryForObject(
			"select coalesce(sum(calls), 0), coalesce(sum(total_exec_time), 0), coalesce(sum(rows), 0), "
				+ "coalesce(sum(shared_blks_hit), 0), coalesce(sum(shared_blks_read), 0) "
				+ "from pg_stat_statements where dbid = (select oid from pg_database where datname = current_database()) "
				+ "and query not like '%pg_stat_statements%'",
			(rs, rowNumber) -> new PostgresCost(
				rs.getLong(1), rs.getDouble(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
	}

	long statementCallsContaining(String queryFragment) {
		return jdbcTemplate.queryForObject(
			"select coalesce(sum(calls), 0) from pg_stat_statements "
				+ "where dbid = (select oid from pg_database where datname = current_database()) "
				+ "and query like ? and query not like '%pg_stat_statements%'",
			Long.class,
			"%" + queryFragment + "%");
	}

	private String formatRawRecord(
		String scenario,
		int concurrencyLevel,
		List<RequestMeasurement> requests,
		PostgresCost postgresCost) {
		long successCount = requests.stream().filter(RequestMeasurement::successful).count();
		long businessFailureCount = requests.stream().filter(RequestMeasurement::businessFailure).count();
		long concurrencyFailureCount = requests.stream().filter(RequestMeasurement::concurrencyFailure).count();
		long technicalFailureCount = requests.stream().filter(RequestMeasurement::technicalFailure).count();
		long conflictCount = requests.stream().mapToLong(RequestMeasurement::conflictCount).sum();
		long retryZeroCount = requests.stream().filter(request -> request.retryCount() == 0).count();
		long retryOneCount = requests.stream().filter(request -> request.retryCount() == 1).count();
		long retryTwoCount = requests.stream().filter(request -> request.retryCount() == 2).count();
		List<Long> responseNanos = requests.stream().map(RequestMeasurement::durationNanos).toList();
		double conflictRate = requests.isEmpty() ? 0 : (double)conflictCount / requests.size();
		return "ROOM10A_RAW scenario=" + scenario
			+ " concurrencyLevel=" + concurrencyLevel
			+ " requestCount=" + requests.size()
			+ " success=" + successCount
			+ " businessFailure=" + businessFailureCount
			+ " concurrencyFailure=" + concurrencyFailureCount
			+ " technicalFailure=" + technicalFailureCount
			+ " conflictCount=" + conflictCount
			+ " conflictRate=" + conflictRate
			+ " retry0=" + retryZeroCount
			+ " retry1=" + retryOneCount
			+ " retry2=" + retryTwoCount
			+ " exhausted=" + concurrencyFailureCount
			+ " responseNanos=" + responseNanos
			+ " calls=" + postgresCost.statementCalls()
			+ " totalExecMs=" + postgresCost.totalExecutionMillis()
			+ " rows=" + postgresCost.rows()
			+ " sharedBlksHit=" + postgresCost.sharedBlockHits()
			+ " sharedBlksRead=" + postgresCost.sharedBlockReads();
	}

	private void await(CountDownLatch latch, String phase) {
		try {
			if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
				throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(phase + " 중 인터럽트되었습니다.", exception);
		}
	}

	private void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError("측정 round의 요청 스레드가 종료되지 않았습니다.");
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			executor.shutdownNow();
		}
	}

	static RoomRepository gatedRoomRepository(RoomRepository delegate, RoomReadGate roomReadGate) {
		InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(delegate, roomReadGate);
		return (RoomRepository)Proxy.newProxyInstance(
			RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
	}

	static final class RoomReadGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();
		private final ThreadLocal<Boolean> responseTimerArmed = new ThreadLocal<>();
		private final ThreadLocal<Long> gateWaitNanos = new ThreadLocal<>();
		private final LongSupplier nanoTime;

		RoomReadGate() {
			this(System::nanoTime);
		}

		RoomReadGate(LongSupplier nanoTime) {
			this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		}

		void activate(long roomId, int expectedReaders) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId, expectedReaders))) {
				throw new IllegalStateException("이미 ROOM read gate가 활성화되어 있습니다.");
			}
		}

		void afterFindById(long roomId, Optional<Room> room) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.roomId != roomId) {
				return;
			}
			int readOrder = scenario.totalReadCount.getAndIncrement();
			if (readOrder >= scenario.expectedReaders) {
				return;
			}
			scenario.observedVersions.add(room.orElseThrow().getVersion());
			long gateWaitStartedAt = nanoTime.getAsLong();
			scenario.initialReads.countDown();
			awaitGate(scenario.initialReads);
			recordGateWaitNanos(nanoTime.getAsLong() - gateWaitStartedAt);
		}

		void armResponseTimer() {
			responseTimerArmed.set(true);
			gateWaitNanos.set(0L);
		}

		long elapsedNanosSince(long fallbackStartNanos) {
			Long measuredGateWaitNanos = gateWaitNanos.get();
			long excludedNanos = measuredGateWaitNanos == null ? 0 : measuredGateWaitNanos;
			return nanoTime.getAsLong() - fallbackStartNanos - excludedNanos;
		}

		void clearResponseTimer() {
			responseTimerArmed.remove();
			gateWaitNanos.remove();
		}

		void recordGateWaitNanos(long durationNanos) {
			if (Boolean.TRUE.equals(responseTimerArmed.get())) {
				gateWaitNanos.set(gateWaitNanos.get() + durationNanos);
			}
		}

		void deactivate() {
			activeScenario.set(null);
		}

		void assertInitialReadsShareOneVersion() {
			Scenario scenario = activeScenario.get();
			if (scenario == null) {
				throw new AssertionError("ROOM read gate가 활성화되어 있지 않습니다.");
			}
			if (scenario.observedVersions.size() != 1) {
				throw new AssertionError(
					"동시 요청이 서로 다른 ROOM version을 읽었습니다: " + scenario.observedVersions);
			}
		}

		private void awaitGate(CountDownLatch latch) {
			try {
				if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError("같은 ROOM version 읽기 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("같은 ROOM version 읽기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final int expectedReaders;
			private final CountDownLatch initialReads;
			private final AtomicInteger totalReadCount = new AtomicInteger();
			private final Set<Long> observedVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();

			private Scenario(long roomId, int expectedReaders) {
				this.roomId = roomId;
				this.expectedReaders = expectedReaders;
				initialReads = new CountDownLatch(expectedReaders);
			}
		}
	}

	final class RetryMeasurement {

		private final List<Long> transactionIds = new ArrayList<>();
		private int attemptCount;
		private int conflictCount;
		private int businessFailureCount;
		private int technicalFailureCount;
		private boolean exhausted;
		private List<RetryLogRecord> retryLogRecords = List.of();

		void executeDeterministic(
			RoomOptimisticLockRetrier retrier,
			String event,
			List<AttemptPlan> plans) {
			AtomicInteger invocation = new AtomicInteger();
			try {
				execute(retrier, event, () -> plans.get(invocation.getAndIncrement()).execute());
			} catch (BusinessException exception) {
				if (exception.getErrorCode() != ErrorCode.ROOM_CONCURRENT_MODIFICATION) {
					throw exception;
				}
			}
		}

		<T> T execute(RoomOptimisticLockRetrier retrier, String event, Supplier<T> command) {
			RetryLogCapture retryLogCapture = RetryLogCapture.attach();
			MeasuredRequestTrace trace = measuredRetrier.beginRequest();
			try {
				return retrier.execute(
					() -> requiresNewTransaction.execute(status -> executeOneAttempt(command)), event, null);
			} catch (BusinessException exception) {
				if (exception.getErrorCode() == ErrorCode.ROOM_CONCURRENT_MODIFICATION) {
					exhausted = true;
				} else {
					businessFailureCount++;
				}
				throw exception;
			} catch (RuntimeException exception) {
				technicalFailureCount++;
				throw exception;
			} finally {
				attemptCount = transactionIds.size();
				conflictCount = trace.retryCount() + (exhausted ? 1 : 0);
				try {
					retryLogRecords = retryLogCapture.retryLogRecords();
				} finally {
					measuredRetrier.endRequest();
					retryLogCapture.detach();
				}
			}
		}

		private <T> T executeOneAttempt(Supplier<T> command) {
			Long transactionId = jdbcTemplate.queryForObject("select txid_current()", Long.class);
			transactionIds.add(transactionId);
			return command.get();
		}

		int attemptCount() {
			return attemptCount;
		}

		int conflictCount() {
			return conflictCount;
		}

		int retryCount() {
			return Math.max(0, attemptCount - 1);
		}

		boolean exhausted() {
			return exhausted;
		}

		List<RetryLogRecord> retryLogRecords() {
			return retryLogRecords;
		}

		List<Long> transactionIds() {
			return List.copyOf(transactionIds);
		}

		int businessFailureCount() {
			return businessFailureCount;
		}

		int technicalFailureCount() {
			return technicalFailureCount;
		}
	}

	enum AttemptPlan {
		SUCCESS,
		CONFLICT;

		static AttemptPlan success() {
			return SUCCESS;
		}

		static AttemptPlan conflict() {
			return CONFLICT;
		}

		Object execute() {
			if (this == CONFLICT) {
				throw new OptimisticLockException("deterministic conflict");
			}
			return new Object();
		}
	}

	record RoundMeasurement(
		List<RequestMeasurement> requests,
		PostgresCost postgresCost,
		String rawRecord,
		List<RetryLogRecord> retryLogRecords,
		List<RetryTrace> retryTraces) {

		int totalRequestCount() {
			return requests.size();
		}

		long successCount() {
			return requests.stream().filter(RequestMeasurement::successful).count();
		}

		long businessFailureCount() {
			return requests.stream().filter(RequestMeasurement::businessFailure).count();
		}

		long concurrencyFailureCount() {
			return requests.stream().filter(RequestMeasurement::concurrencyFailure).count();
		}

		long technicalFailureCount() {
			return requests.stream().filter(RequestMeasurement::technicalFailure).count();
		}

		long retryCount(int expectedRetryCount) {
			return requests.stream()
				.filter(request -> request.retryCount() == expectedRetryCount)
				.count();
		}

		long totalRetryCount() {
			return requests.stream().mapToLong(RequestMeasurement::retryCount).sum();
		}

		long retryAttemptLogCount() {
			return retryLogRecords.stream().filter(RetryLogRecord::retryAttempt).count();
		}

		long exhaustedLogCount() {
			return retryLogRecords.stream().filter(RetryLogRecord::exhaustedAttempt).count();
		}

		long exhaustedCount() {
			return concurrencyFailureCount();
		}

		List<Long> requestDurationsNanos() {
			return requests.stream().map(RequestMeasurement::durationNanos).toList();
		}

		boolean hasOnlyBusinessError(ErrorCode errorCode) {
			return requests.stream()
				.filter(RequestMeasurement::businessFailure)
				.allMatch(request -> request.errorCode() == errorCode);
		}
	}

	record RequestMeasurement(
		long durationNanos,
		int retryCount,
		int conflictCount,
		boolean successful,
		boolean businessFailure,
		boolean concurrencyFailure,
		boolean technicalFailure,
		ErrorCode errorCode) {

		static RequestMeasurement success(long durationNanos, int retryCount) {
			return new RequestMeasurement(durationNanos, retryCount, retryCount, true, false, false, false, null);
		}

		static RequestMeasurement businessFailure(long durationNanos, int retryCount, ErrorCode errorCode) {
			boolean concurrencyFailure = errorCode == ErrorCode.ROOM_CONCURRENT_MODIFICATION;
			int conflictCount = retryCount + (concurrencyFailure ? 1 : 0);
			return new RequestMeasurement(
				durationNanos,
				retryCount,
				conflictCount,
				false,
				!concurrencyFailure,
				concurrencyFailure,
				false,
				errorCode);
		}

		static RequestMeasurement technicalFailure(long durationNanos, int retryCount) {
			return new RequestMeasurement(durationNanos, retryCount, retryCount, false, false, false, true, null);
		}
	}

	private record CollectedRound(String scenario, int concurrencyLevel, RoundMeasurement measurement) {
	}

	record MeasurementReport(
		String reportName,
		MeasurementEnvironment environment,
		List<RoundReport> rounds) {
	}

	record MeasurementEnvironment(
		String gitSha,
		String javaVersion,
		String postgresqlVersion,
		String operatingSystem,
		String operatingSystemVersion,
		int cpuCount,
		long startUsedHeapBytes,
		long maxHeapBytes,
		Map<String, String> configuration) {
	}

	record RoundReport(
		String scenario,
		int concurrencyLevel,
		int totalRequestCount,
		long successCount,
		long businessFailureCount,
		long concurrencyFailureCount,
		long technicalFailureCount,
		long conflictCount,
		double conflictRate,
		long retry0Count,
		long retry1Count,
		long retry2Count,
		long exhaustedCount,
		List<Long> requestDurationsNanos,
		PostgresCost postgresCost,
		String rawRecord) {
	}

	record PostgresCost(
		long statementCalls,
		double totalExecutionMillis,
		long rows,
		long sharedBlockHits,
		long sharedBlockReads) {
	}

	record RetryLogRecord(
		String event,
		Long roomId,
		int attempt,
		String useCase,
		String reasonCode,
		Level level) {

		boolean retryAttempt() {
			return level == Level.DEBUG;
		}

		boolean exhaustedAttempt() {
			return level == Level.WARN;
		}
	}

	/** 두 bounded-jitter logger의 TRACE tuple을 시나리오 원자료에 붙이기 위한 공통 capture다. */
	record RetryTrace(
		String logger,
		String event,
		long roomId,
		int attempt,
		long requestSeed,
		long derivedSeed,
		long maxDelayMillis,
		long delayMillis) {

		boolean hasValidSeedRelationship() {
			return derivedSeed == requestSeed * 31 + attempt;
		}

		boolean hasValidDelayBounds() {
			return delayMillis >= 0 && delayMillis <= maxDelayMillis;
		}
	}

	static final class RetryTraceCapture {

		private static final String RETRIER_LOGGER = RoomOptimisticLockRetrier.class.getName();
		private static final String WAITLIST_REGISTRATION_LOGGER = "cloud.bamsongi.albammate.room.service.command.RoomWaitlistRegistrationCoordinator";
		private static final java.util.regex.Pattern TRACE_LOG_PATTERN = java.util.regex.Pattern.compile(
			"^event=(\\S+) roomId=(\\d+) attempt=(\\d+) requestSeed=(-?\\d+) jitterSeed=(-?\\d+) "
				+ "maxDelayMillis=(\\d+) delayMillis=(\\d+)$");

		private final Logger retrierLogger;
		private final Level previousRetrierLevel;
		private final Logger waitlistRegistrationLogger;
		private final Level previousWaitlistRegistrationLevel;
		private final RetryTraceAppender appender;

		private RetryTraceCapture(
			Logger retrierLogger,
			Level previousRetrierLevel,
			Logger waitlistRegistrationLogger,
			Level previousWaitlistRegistrationLevel,
			RetryTraceAppender appender) {
			this.retrierLogger = retrierLogger;
			this.previousRetrierLevel = previousRetrierLevel;
			this.waitlistRegistrationLogger = waitlistRegistrationLogger;
			this.previousWaitlistRegistrationLevel = previousWaitlistRegistrationLevel;
			this.appender = appender;
		}

		static RetryTraceCapture attach() {
			Logger retrierLogger = (Logger)LoggerFactory.getLogger(RETRIER_LOGGER);
			Logger waitlistRegistrationLogger = (Logger)LoggerFactory.getLogger(WAITLIST_REGISTRATION_LOGGER);
			Level previousRetrierLevel = retrierLogger.getLevel();
			Level previousWaitlistRegistrationLevel = waitlistRegistrationLogger.getLevel();
			RetryTraceAppender appender = new RetryTraceAppender();
			appender.start();
			retrierLogger.setLevel(Level.TRACE);
			waitlistRegistrationLogger.setLevel(Level.TRACE);
			retrierLogger.addAppender(appender);
			waitlistRegistrationLogger.addAppender(appender);
			return new RetryTraceCapture(
				retrierLogger,
				previousRetrierLevel,
				waitlistRegistrationLogger,
				previousWaitlistRegistrationLevel,
				appender);
		}

		List<RetryTrace> retryTraces() {
			return appender.events().stream()
				.filter(event -> event.getLevel() == Level.TRACE)
				.map(RetryTraceCapture::parse)
				.toList();
		}

		void detach() {
			retrierLogger.detachAppender(appender);
			waitlistRegistrationLogger.detachAppender(appender);
			retrierLogger.setLevel(previousRetrierLevel);
			waitlistRegistrationLogger.setLevel(previousWaitlistRegistrationLevel);
			appender.stop();
		}

		private static RetryTrace parse(ILoggingEvent event) {
			java.util.regex.Matcher matcher = TRACE_LOG_PATTERN.matcher(event.getFormattedMessage());
			if (!matcher.matches()) {
				throw new AssertionError("bounded-jitter TRACE 형식이 계약과 다릅니다: " + event.getFormattedMessage());
			}
			return new RetryTrace(
				event.getLoggerName(),
				matcher.group(1),
				Long.parseLong(matcher.group(2)),
				Integer.parseInt(matcher.group(3)),
				Long.parseLong(matcher.group(4)),
				Long.parseLong(matcher.group(5)),
				Long.parseLong(matcher.group(6)),
				Long.parseLong(matcher.group(7)));
		}
	}

	private static final class RetryTraceAppender extends AppenderBase<ILoggingEvent> {

		private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

		@Override
		protected void append(ILoggingEvent event) {
			events.add(event);
		}

		private List<ILoggingEvent> events() {
			return List.copyOf(events);
		}
	}

	record RoomInvariant(
		int activeParticipantCount,
		int activeParticipationCount,
		int capacity,
		RoomStatus status,
		boolean hasDuplicatedActiveParticipation) {
	}

	private static final class GateAwareRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomReadGate roomReadGate;

		private GateAwareRoomRepositoryInvocationHandler(RoomRepository delegate, RoomReadGate roomReadGate) {
			this.delegate = Objects.requireNonNull(delegate, "delegate");
			this.roomReadGate = Objects.requireNonNull(roomReadGate, "roomReadGate");
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				if (method.getName().equals("findById")
					&& arguments != null
					&& arguments.length == 1
					&& arguments[0] instanceof Long roomId
					&& result instanceof Optional<?> optional) {
					roomReadGate.afterFindById(roomId, optional.map(Room.class::cast));
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	private static final class MeasuredRequestTrace {

		private final AtomicInteger retryCount = new AtomicInteger();

		private int retryCount() {
			return retryCount.get();
		}
	}

	private static final class MeasuredRoomOptimisticLockRetrier extends RoomOptimisticLockRetrier {

		private final ThreadLocal<MeasuredRequestTrace> requestTrace = new ThreadLocal<>();

		private MeasuredRequestTrace beginRequest() {
			MeasuredRequestTrace trace = new MeasuredRequestTrace();
			requestTrace.set(trace);
			return trace;
		}

		private void endRequest() {
			requestTrace.remove();
		}

		@Override
		public <T> T execute(Supplier<T> attempt, String event, Long roomId) {
			return execute(attempt, event, roomId, ignoredAttempt -> {});
		}

		@Override
		public <T> T execute(
			Supplier<T> attempt,
			String event,
			Long roomId,
			java.util.function.IntConsumer beforeRetry) {
			return super.execute(attempt, event, roomId, nextAttempt -> {
				MeasuredRequestTrace trace = requestTrace.get();
				if (trace != null) {
					trace.retryCount.incrementAndGet();
				}
				beforeRetry.accept(nextAttempt);
			});
		}
	}

	private static final class RetryLogCapture {

		private final Logger logger;
		private final Level previousLevel;
		private final ListAppender<ILoggingEvent> appender;

		private RetryLogCapture(Logger logger, Level previousLevel, ListAppender<ILoggingEvent> appender) {
			this.logger = Objects.requireNonNull(logger, "logger");
			this.previousLevel = previousLevel;
			this.appender = Objects.requireNonNull(appender, "appender");
		}

		private static RetryLogCapture attach() {
			Logger logger = (Logger)LoggerFactory.getLogger(RoomOptimisticLockRetrier.class);
			Level previousLevel = logger.getLevel();
			logger.setLevel(Level.DEBUG);
			ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			logger.addAppender(appender);
			return new RetryLogCapture(logger, previousLevel, appender);
		}

		private List<RetryLogRecord> retryLogRecords() {
			return appender.list.stream()
				.filter(event -> event.getLevel() != Level.TRACE)
				.map(RetryLogCapture::parse)
				.toList();
		}

		private static RetryLogRecord parse(ILoggingEvent event) {
			Map<String, Object> fields = event.getKeyValuePairs().stream()
				.collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
			if (!fields.keySet().equals(Set.of("event", "attempt", "useCase", "reasonCode"))
				&& !fields.keySet().equals(Set.of("event", "roomId", "attempt", "useCase", "reasonCode"))) {
				throw new AssertionError("재시도 구조화 field 계약이 다릅니다: " + fields);
			}
			Long roomId = fields.containsKey("roomId") ? ((Number)fields.get("roomId")).longValue() : null;
			return new RetryLogRecord(
				(String)fields.get("event"),
				roomId,
				((Number)fields.get("attempt")).intValue(),
				(String)fields.get("useCase"),
				(String)fields.get("reasonCode"),
				event.getLevel());
		}

		private void detach() {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
			appender.stop();
		}
	}
}
