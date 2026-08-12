package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import jakarta.persistence.OptimisticLockException;

class RoomCommandExecutionCoordinatorTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-12T00:00:00Z");
	private static final Instant RETRY_WALL_CLOCK_TIME = REQUEST_TIME.plusSeconds(90);

	@Test
	void 재시도_중_벽시계가_시작_시각을_지나도_최초_요청_시각을_모든_시도에_전달한다() {
		SteppingClock clock = new SteppingClock(REQUEST_TIME, RETRY_WALL_CLOCK_TIME);
		RoomCommandExecutionCoordinator coordinator = new RoomCommandExecutionCoordinator(
			clock,
			new RoomOptimisticLockRetrier());
		List<Instant> requestTimes = new ArrayList<>();
		AtomicInteger attempts = new AtomicInteger();

		Instant result = coordinator.execute(1L, "room_command_request_time", requestTime -> {
			requestTimes.add(requestTime);
			if (attempts.getAndIncrement() == 0) {
				throw new OptimisticLockException("deterministic conflict");
			}
			return requestTime;
		});

		assertEquals(REQUEST_TIME, result);
		assertEquals(List.of(REQUEST_TIME, REQUEST_TIME), requestTimes);
		assertEquals(1, clock.instantCallCount());
	}

	@Test
	void 첫_낙관적_락_충돌_뒤_업무_오류를_재시도_소진_오류보다_우선한다() {
		RoomCommandExecutionCoordinator coordinator = new RoomCommandExecutionCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC),
			new RoomOptimisticLockRetrier());
		AtomicInteger attempts = new AtomicInteger();

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> coordinator.execute(1L, "room_command_business_failure", requestTime -> {
				if (attempts.getAndIncrement() == 0) {
					throw new OptimisticLockException("deterministic conflict");
				}
				throw new BusinessException(ErrorCode.ROOM_NOT_RECRUITING);
			}));

		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, exception.getErrorCode());
		assertEquals(2, attempts.get());
	}

	@Test
	void 세_시도가_모두_낙관적_락_충돌일_때만_동시_수정_오류를_반환한다() {
		RoomCommandExecutionCoordinator coordinator = new RoomCommandExecutionCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC),
			new RoomOptimisticLockRetrier());
		AtomicInteger attempts = new AtomicInteger();

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> coordinator.execute(1L, "room_command_exhausted", requestTime -> {
				attempts.incrementAndGet();
				throw new OptimisticLockException("deterministic conflict");
			}));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		assertEquals(3, attempts.get());
	}

	private static final class SteppingClock extends Clock {

		private final List<Instant> instants;
		private int instantCallCount;

		private SteppingClock(Instant... instants) {
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

		private int instantCallCount() {
			return instantCallCount;
		}
	}
}
