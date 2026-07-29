package cloud.bamsongi.albammate.room.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** 참가 한 번을 최신 상태 기준의 독립된 쓰기 트랜잭션에서 처리한다. */
@Service
public class RoomParticipationExecutor {

	private final RoomRepository roomRepository;
	private final ParticipationRepository participationRepository;

	public RoomParticipationExecutor(
		RoomRepository roomRepository, ParticipationRepository participationRepository) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		this.participationRepository = Objects.requireNonNull(participationRepository, "participationRepository");
	}

	/** 요청 시각의 방 상태를 보정한 뒤 신규 또는 취소된 참가 관계를 활성화한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RoomParticipationResponse participate(
		long currentUserId, long roomId, Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");

		Room room = roomRepository
			.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		Optional<Participation> existingParticipation = participationRepository.findByRoomIdAndUserId(roomId,
			currentUserId);

		room.reconcileStateAt(requestTime);
		validateParticipation(room, currentUserId, existingParticipation, requestTime);

		Participation participation;
		if (existingParticipation.isPresent()) {
			participation = existingParticipation.get();
			participation.reactivate(requestTime);
		} else {
			participation = Participation.createActive(room, currentUserId, requestTime);
		}
		room.addActiveParticipant();

		roomRepository.save(room);
		roomRepository.flush();
		participationRepository.save(participation);
		return RoomParticipationResponse.from(room, ParticipationStatus.ACTIVE);
	}

	private void validateParticipation(
		Room room,
		long currentUserId,
		Optional<Participation> existingParticipation,
		Instant requestTime) {
		if (room.getStatus() == RoomStatus.CANCELED || room.getStatus() == RoomStatus.FINISHED) {
			throw new BusinessException(ErrorCode.ROOM_NOT_RECRUITING);
		}
		if (room.getHostUserId() == currentUserId
			|| existingParticipation
				.map(Participation::getStatus)
				.filter(ParticipationStatus.ACTIVE::equals)
				.isPresent()) {
			throw new BusinessException(ErrorCode.ALREADY_PARTICIPATING);
		}
		if (room.getActiveParticipantCount() >= room.getCapacity()) {
			throw new BusinessException(ErrorCode.CAPACITY_EXCEEDED);
		}
		if (!requestTime.isBefore(room.getStartAt()) || room.getStatus() != RoomStatus.RECRUITING) {
			throw new BusinessException(ErrorCode.ROOM_NOT_RECRUITING);
		}
	}

}
