package cloud.bamsongi.albammate.room.service;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.persistence.OptimisticLockException;
import lombok.extern.slf4j.Slf4j;

/** 트랜잭션 경계 밖에서 ROOM 낙관 락 충돌만 제한적으로 재시도한다. */
@Service
@Slf4j
public class RoomOptimisticLockRetrier {

	private static final int MAX_ATTEMPTS = 3;
	private final BoundedFullJitter boundedFullJitter;

	public RoomOptimisticLockRetrier() {
		this(new BoundedFullJitter());
	}

	RoomOptimisticLockRetrier(BoundedFullJitter boundedFullJitter) {
		this.boundedFullJitter = Objects.requireNonNull(boundedFullJitter, "boundedFullJitter");
	}

	/** 낙관 락 충돌만 최대 세 번 시도하고, 두 번째·세 번째 시도 전에 bounded full jitter를 적용한다. */
	public <T> T execute(Supplier<T> attempt, String event, Long roomId) {
		return execute(attempt, event, roomId, ignoredAttempt -> {});
	}

	/** 재시도 전 작업이 필요한 호출자만 다음 시도 직전에 hook을 실행한다. */
	public <T> T execute(Supplier<T> attempt, String event, Long roomId, IntConsumer beforeRetry) {
		Objects.requireNonNull(attempt, "attempt");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(beforeRetry, "beforeRetry");

		long requestSeed = boundedFullJitter.nextRequestSeed();
		RuntimeException lastConflict = null;
		for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
			try {
				return attempt.get();
			} catch (OptimisticLockException | ObjectOptimisticLockingFailureException exception) {
				lastConflict = exception;
			}
			if (attemptNumber < MAX_ATTEMPTS) {
				int nextAttempt = attemptNumber + 1;
				RetryDelay retryDelay = boundedFullJitter.calculate(requestSeed, event, roomId, nextAttempt);
				boundedFullJitter.waitBeforeRetry(retryDelay);
				beforeRetry.accept(nextAttempt);
				logJitter(event, roomId, retryDelay);
				logRetry(event, roomId, nextAttempt, false);
			}
		}

		logRetry(event, roomId, MAX_ATTEMPTS, true);
		throw new BusinessException(ErrorCode.ROOM_CONCURRENT_MODIFICATION, lastConflict);
	}

	private void logJitter(String event, Long roomId, RetryDelay retryDelay) {
		if (roomId == null) {
			log.trace("event={} attempt={} requestSeed={} jitterSeed={} maxDelayMillis={} delayMillis={}",
				event,
				retryDelay.nextAttempt(),
				retryDelay.requestSeed(),
				retryDelay.seed(),
				retryDelay.maxDelayMillis(),
				retryDelay.delayMillis());
			return;
		}
		log.trace("event={} roomId={} attempt={} requestSeed={} jitterSeed={} maxDelayMillis={} delayMillis={}",
			event,
			roomId,
			retryDelay.nextAttempt(),
			retryDelay.requestSeed(),
			retryDelay.seed(),
			retryDelay.maxDelayMillis(),
			retryDelay.delayMillis());
	}

	private void logRetry(String event, Long roomId, int attempt, boolean exhausted) {
		String useCase = resolveUseCase(event);
		String reasonCode = exhausted ? "OPTIMISTIC_LOCK_EXHAUSTED" : "OPTIMISTIC_LOCK_CONFLICT";
		if (roomId == null) {
			if (exhausted) {
				log.atWarn().addKeyValue("event", event).addKeyValue("attempt", attempt)
					.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry exhausted");
			} else {
				log.atDebug().addKeyValue("event", event).addKeyValue("attempt", attempt)
					.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry conflict");
			}
			return;
		}
		if (exhausted) {
			log.atWarn().addKeyValue("event", event).addKeyValue("roomId", roomId).addKeyValue("attempt", attempt)
				.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry exhausted");
		} else {
			log.atDebug().addKeyValue("event", event).addKeyValue("roomId", roomId).addKeyValue("attempt", attempt)
				.addKeyValue("useCase", useCase).addKeyValue("reasonCode", reasonCode).log("room retry conflict");
		}
	}

	private String resolveUseCase(String event) {
		return switch (event) {
			case "room_update_retry" -> "ROOM_UPDATE";
			case "room_cancel_retry" -> "ROOM_CANCEL";
			case "room_finish_retry" -> "ROOM_FINISH";
			case "room_participation_retry" -> "ROOM_PARTICIPATION";
			case "room_participation_cancel_retry" -> "ROOM_PARTICIPATION_CANCEL";
			case "room_waitlist_cancel_retry" -> "ROOM_WAITLIST_CANCEL";
			case "room_state_reconciliation_retry" -> "ROOM_STATUS_CORRECTION";
			default -> "ROOM_STATUS_CORRECTION";
		};
	}

	@FunctionalInterface
	public interface JitterSource {

		long nextDelayMillis(long seed, long maxDelayMillis);
	}

	@FunctionalInterface
	public interface DelaySleeper {

		void sleep(long delayMillis);
	}

	@FunctionalInterface
	public interface RequestSeedSource {

		long nextSeed();
	}

	public record RetryDelay(
		int nextAttempt,
		long requestSeed,
		long seed,
		long maxDelayMillis,
		long delayMillis) {
	}

	/** retry 2와 retry 3의 고정 상한만 사용하는 bounded full jitter 정책이다. */
	public static final class BoundedFullJitter {

		private final JitterSource jitterSource;
		private final DelaySleeper delaySleeper;
		private final RequestSeedSource requestSeedSource;

		public BoundedFullJitter() {
			this(
				(seed, maxDelayMillis) -> maxDelayMillis == 0
					? 0
					: new SplittableRandom(seed).nextLong(maxDelayMillis + 1),
				delayMillis -> {
					if (delayMillis == 0) {
						return;
					}
					try {
						Thread.sleep(delayMillis);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException("retry delay 중 인터럽트되었습니다.", exception);
					}
				},
				() -> ThreadLocalRandom.current().nextLong());
		}

		public BoundedFullJitter(JitterSource jitterSource, DelaySleeper delaySleeper) {
			this(jitterSource, delaySleeper, () -> ThreadLocalRandom.current().nextLong());
		}

		public BoundedFullJitter(
			JitterSource jitterSource,
			DelaySleeper delaySleeper,
			RequestSeedSource requestSeedSource) {
			this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
			this.delaySleeper = Objects.requireNonNull(delaySleeper, "delaySleeper");
			this.requestSeedSource = Objects.requireNonNull(requestSeedSource, "requestSeedSource");
		}

		public long nextRequestSeed() {
			return requestSeedSource.nextSeed();
		}

		public RetryDelay calculate(String event, Long roomId, int nextAttempt) {
			Objects.requireNonNull(event, "event");
			return calculate(requestSeedFor(event, roomId), event, roomId, nextAttempt);
		}

		public RetryDelay calculate(long requestSeed, String event, Long roomId, int nextAttempt) {
			Objects.requireNonNull(event, "event");
			long maxDelayMillis = maxDelayMillisFor(nextAttempt);
			long seed = seedFor(requestSeed, nextAttempt);
			long delayMillis = jitterSource.nextDelayMillis(seed, maxDelayMillis);
			if (delayMillis < 0 || delayMillis > maxDelayMillis) {
				throw new IllegalStateException("bounded jitter가 허용 범위를 벗어났습니다.");
			}
			return new RetryDelay(nextAttempt, requestSeed, seed, maxDelayMillis, delayMillis);
		}

		public void waitBeforeRetry(RetryDelay retryDelay) {
			Objects.requireNonNull(retryDelay, "retryDelay");
			delaySleeper.sleep(retryDelay.delayMillis());
		}

		private long maxDelayMillisFor(int nextAttempt) {
			if (nextAttempt == 2) {
				return 5L;
			}
			if (nextAttempt == 3) {
				return 10L;
			}
			throw new IllegalArgumentException("bounded jitter는 retry 2와 retry 3에서만 계산합니다.");
		}

		private long requestSeedFor(String event, Long roomId) {
			long seed = event.hashCode();
			seed = seed * 31 + (roomId == null ? 0 : roomId);
			return seed;
		}

		private long seedFor(long requestSeed, int nextAttempt) {
			return requestSeed * 31 + nextAttempt;
		}
	}
}
