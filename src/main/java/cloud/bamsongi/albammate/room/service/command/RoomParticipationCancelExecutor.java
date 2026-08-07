package cloud.bamsongi.albammate.room.service.command;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ParticipationCanceledEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEventRecorder;
import cloud.bamsongi.albammate.room.contract.WaitlistPromotedEvent;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

/** 참가 취소 한 번을 최신 상태 기준의 독립된 쓰기 트랜잭션에서 처리한다. */
@Service
class RoomParticipationCancelExecutor {

	private final RoomRepository roomRepository;
	private final ParticipationRepository participationRepository;
	private final RoomWaitlistRepository roomWaitlistRepository;
	private final RoomChangeEventRecorder roomChangeEventRecorder;

	RoomParticipationCancelExecutor(
		RoomRepository roomRepository,
		ParticipationRepository participationRepository,
		RoomWaitlistRepository roomWaitlistRepository,
		RoomChangeEventRecorder roomChangeEventRecorder) {
		this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository");
		this.participationRepository = Objects.requireNonNull(participationRepository, "participationRepository");
		this.roomWaitlistRepository = Objects.requireNonNull(roomWaitlistRepository, "roomWaitlistRepository");
		this.roomChangeEventRecorder = Objects.requireNonNull(roomChangeEventRecorder, "roomChangeEventRecorder");
	}

	/** 요청 시각의 방 상태를 보정한 뒤 활성 참가 관계를 취소하고 점유 인원을 갱신한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RoomParticipationResponse cancelParticipation(
		long currentUserId, long roomId, Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");

		Room room = roomRepository
			.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));

		room.reconcileStateAt(requestTime);
		Participation participation = requireCancelableParticipation(room, currentUserId, requestTime);

		room.removeActiveParticipant();
		roomRepository.save(room);
		roomRepository.flush();

		participation.cancel(requestTime);
		participationRepository.save(participation);
		participationRepository.flush();
		Optional<Long> promotedUserId = promoteFirstWaiting(room, requestTime);
		if (promotedUserId.isPresent()) {
			roomChangeEventRecorder.record(
				new WaitlistPromotedEvent(room.getId(), requestTime), List.of(promotedUserId.get()));
		} else if (room.getStatus() == RoomStatus.RECRUITING
			&& room.getRemainingRecruitmentSeats() > 0) {
			roomChangeEventRecorder.record(
				new ParticipationCanceledEvent(room.getId(), requestTime), List.of(room.getHostUserId()));
		}
		return RoomParticipationResponse.from(room, ParticipationStatus.CANCELED);
	}

	/** 현재 ROOM의 빈자리 하나에는 조건부 전이에 성공한 첫 대기자만 활성 참가로 만든다. */
	private Optional<Long> promoteFirstWaiting(Room room, Instant requestTime) {
		if (room.getStatus() != RoomStatus.RECRUITING) {
			return Optional.empty();
		}

		while (true) {
			var candidate = roomWaitlistRepository.findFirstWaitingByRoomId(room.getId());
			if (candidate.isEmpty()) {
				return Optional.empty();
			}
			var waiting = candidate.get();
			if (roomWaitlistRepository.promoteWaiting(
				room.getId(), waiting.getUserId(), waiting.getQueueOrder(), requestTime) == 0) {
				continue;
			}

			room.addActiveParticipant();
			roomRepository.save(room);
			roomRepository.flush();
			Participation promotedParticipation = promotedParticipationOf(room, waiting.getUserId(), requestTime);
			participationRepository.save(promotedParticipation);
			participationRepository.flush();
			return Optional.of(waiting.getUserId());
		}
	}

	/**
	 * 승격 대상의 참가 관계를 준비한다. 취소했던 관계는 새 행 없이 다시 활성화하고, 관계가 없으면 새로 만든다.
	 *
	 * <p>대기자는 활성 참가자가 될 수 없으므로 취소가 아닌 기존 관계는 저장 불변식이 이미 깨진 상태다. 이 경우 인원과
	 * 관계 수가 어긋난 채로 커밋하지 않고 전체 요청을 실패시킨다.
	 */
	private Participation promotedParticipationOf(Room room, Long promotedUserId, Instant requestTime) {
		Participation existing = participationRepository
			.findByRoomIdAndUserId(room.getId(), promotedUserId)
			.orElse(null);
		if (existing == null) {
			return Participation.createActive(room, promotedUserId, requestTime);
		}
		if (existing.getStatus() != ParticipationStatus.CANCELED) {
			throw new IllegalStateException("promoted waiting user already has a non-canceled participation");
		}
		existing.reactivate(requestTime);
		return existing;
	}

	private Participation requireCancelableParticipation(Room room, long currentUserId, Instant requestTime) {
		if (room.getHostUserId() == currentUserId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		Participation participation = participationRepository
			.findByRoomIdAndUserId(room.getId(), currentUserId)
			.filter(candidate -> candidate.getStatus() == ParticipationStatus.ACTIVE)
			.orElseThrow(() -> new BusinessException(ErrorCode.PARTICIPATION_NOT_FOUND));
		if (!requestTime.isBefore(room.getStartAt())) {
			throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
		}
		return participation;
	}

}
