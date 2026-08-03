package cloud.bamsongi.albammate.room.statuscorrection;

import java.time.Instant;
import java.util.Objects;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.room.contract.RoomTerminalStateReached;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** 한 번의 상태 보정을 독립된 쓰기 트랜잭션에서 실행한다. */
@Service
class RoomStatusCorrectionExecutor {

	private final RoomRepository roomRepository;
	private final ApplicationEventPublisher eventPublisher;

	RoomStatusCorrectionExecutor(RoomRepository roomRepository, ApplicationEventPublisher eventPublisher) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
	}

	/** 단건 보정 시도마다 최신 방을 다시 읽고, 없는 방은 후속 유스케이스가 판단하도록 건너뛴다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void correctRoom(Long roomId, Instant requestTime) {
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(requestTime, "requestTime");

		Room room = roomRepository.findById(roomId).orElse(null);
		if (room != null && room.reconcileStateAt(requestTime)) {
			roomRepository.save(room);
			roomRepository.flush();
			publishTerminalStateReachedIfFinished(room, requestTime);
		}
	}

	/** due 조건에 맞는 방만 읽어 목록·내 모임 조회 전 상태를 일괄 보정한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int correctDueRooms(Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");
		Instant finishedThreshold = requestTime.minus(Room.AUTOMATIC_FINISH_AFTER_START);
		int changedCount = 0;
		for (Room room : roomRepository.findDueRooms(requestTime, finishedThreshold)) {
			if (room.reconcileStateAt(requestTime)) {
				roomRepository.save(room);
				roomRepository.flush();
				publishTerminalStateReachedIfFinished(room, requestTime);
				changedCount++;
			}
		}
		return changedCount;
	}

	private void publishTerminalStateReachedIfFinished(Room room, Instant requestTime) {
		if (room.getStatus() == RoomStatus.FINISHED) {
			eventPublisher.publishEvent(new RoomTerminalStateReached(room.getId(), requestTime));
		}
	}
}
