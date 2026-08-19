package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

/** 한 번의 상태 보정을 독립된 쓰기 트랜잭션에서 실행한다. */
@Service
class RoomStatusCorrectionExecutor {

	private final RoomRepository roomRepository;
	private final RoomWaitlistRepository roomWaitlistRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final ObjectProvider<RoomStatusCorrectionExecutor> executorProvider;
	private final boolean postgresDatabase;

	RoomStatusCorrectionExecutor(
		RoomRepository roomRepository,
		RoomWaitlistRepository roomWaitlistRepository,
		ApplicationEventPublisher eventPublisher,
		ObjectProvider<RoomStatusCorrectionExecutor> executorProvider,
		Environment environment) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		this.roomWaitlistRepository = Objects.requireNonNull(roomWaitlistRepository, "roomWaitlistRepository");
		this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
		this.executorProvider = Objects.requireNonNull(executorProvider, "executorProvider");
		Objects.requireNonNull(environment, "environment");
		String jdbcUrl = environment.getProperty("spring.datasource.url", "");
		this.postgresDatabase = !jdbcUrl.startsWith("jdbc:h2:");
	}

	/** 단건 보정 시도마다 최신 방을 다시 읽고, 없는 방은 후속 유스케이스가 판단하도록 건너뛴다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean correctRoom(Long roomId, Instant requestTime) {
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(requestTime, "requestTime");

		Room room = lockRoom(roomId);
		if (room == null) {
			return false;
		}

		return correctRoom(room, requestTime);
	}

	/** due 조건에 맞는 방만 읽어 목록·내 모임 조회 전 상태를 일괄 보정한다. */
	public int correctDueRooms(Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");
		Instant finishedThreshold = requestTime.minus(Room.AUTOMATIC_FINISH_AFTER_START);
		int changedCount = 0;
		for (Room candidate : roomRepository.findDueRooms(requestTime, finishedThreshold)) {
			if (executorProvider.getObject().correctRoom(candidate.getId(), requestTime)) {
				changedCount++;
			}
		}
		return changedCount;
	}

	private Room lockRoom(Long roomId) {
		setLocalWriteLockTimeout();
		return roomRepository.findByIdForWrite(roomId).orElse(null);
	}

	private void setLocalWriteLockTimeout() {
		if (postgresDatabase) {
			roomRepository.setLocalWriteLockTimeout();
		}
	}

	/** 한 ROOM의 상태와 시작 경계 대기열을 같은 트랜잭션에서 보정한다. */
	private boolean correctRoom(Room room, Instant requestTime) {
		boolean startBoundaryReached = isStartBoundaryReached(room, requestTime);
		boolean stateChanged = room.reconcileStateAt(requestTime);
		if (stateChanged) {
			roomRepository.save(room);
			roomRepository.flush();
		}
		int expiredWaitingCount = 0;
		if (startBoundaryReached) {
			expiredWaitingCount = roomWaitlistRepository.expireAllWaiting(room.getId(), requestTime);
		}
		if (stateChanged) {
			publishTerminalStateReachedIfFinished(room, requestTime);
		}
		return stateChanged || expiredWaitingCount > 0;
	}

	private void publishTerminalStateReachedIfFinished(Room room, Instant requestTime) {
		if (room.getStatus() == RoomStatus.FINISHED) {
			eventPublisher.publishEvent(new RoomTerminalStateReached(room.getId(), requestTime));
		}
	}

	private boolean isStartBoundaryReached(Room room, Instant requestTime) {
		return (room.getStatus() == RoomStatus.RECRUITING || room.getStatus() == RoomStatus.CLOSED)
			&& !requestTime.isBefore(room.getStartAt());
	}
}
