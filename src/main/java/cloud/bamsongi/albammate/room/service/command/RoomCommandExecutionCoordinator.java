package cloud.bamsongi.albammate.room.service.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;

/** 재시도하는 ROOM 명령의 기준 시각 고정과 독립 트랜잭션 실행 순서를 조정한다. */
@Service
class RoomCommandExecutionCoordinator {

	private final Clock clock;
	private final RoomOptimisticLockRetrier retrier;

	RoomCommandExecutionCoordinator(Clock clock, RoomOptimisticLockRetrier retrier) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.retrier = Objects.requireNonNull(retrier, "retrier");
	}

	/** 최초에 고정한 같은 시각으로 낙관 락 충돌만 최대 세 번 재시도한다. */
	public <T> T execute(long roomId, String event, Function<Instant, T> command) {
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(command, "command");
		Instant requestTime = Instant.now(clock);
		return retrier.execute(() -> command.apply(requestTime), event, roomId);
	}
}
