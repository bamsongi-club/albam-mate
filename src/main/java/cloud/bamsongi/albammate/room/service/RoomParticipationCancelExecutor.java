package cloud.bamsongi.albammate.room.service;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** 참가 취소 한 번을 최신 상태 기준의 독립된 쓰기 트랜잭션에서 처리한다. */
@Service
public class RoomParticipationCancelExecutor {

	private final RoomRepository roomRepository;
	private final ParticipationRepository participationRepository;

	public RoomParticipationCancelExecutor(
		RoomRepository roomRepository, ParticipationRepository participationRepository) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		this.participationRepository = Objects.requireNonNull(participationRepository, "participationRepository");
	}

	/** 요청 시각의 방 상태를 보정한 뒤 활성 참가 관계를 취소하고 점유 인원을 갱신한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RoomParticipationResponse cancelParticipation(
		long currentUserId, long roomId, Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");

		Room room = roomRepository
			.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		Participation participation = participationRepository.findByRoomIdAndUserId(roomId, currentUserId).orElse(null);

		room.reconcileStateAt(requestTime);
		validateCancellation(room, currentUserId, participation, requestTime);

		participation.cancel(requestTime);
		room.removeActiveParticipant();

		roomRepository.save(room);
		roomRepository.flush();
		participationRepository.save(participation);
		return toResponse(room);
	}

	private void validateCancellation(
		Room room, long currentUserId, Participation participation, Instant requestTime) {
		if (room.getHostUserId() == currentUserId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		if (participation == null || participation.getStatus() != ParticipationStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.PARTICIPATION_NOT_FOUND);
		}
		if (!requestTime.isBefore(room.getStartAt())) {
			throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
		}
	}

	private RoomParticipationResponse toResponse(Room room) {
		return new RoomParticipationResponse(
			room.getId(),
			ParticipationStatus.CANCELED,
			room.getStatus(),
			room.getActiveParticipantCount() + 1,
			room.getCapacity() - room.getActiveParticipantCount());
	}
}
