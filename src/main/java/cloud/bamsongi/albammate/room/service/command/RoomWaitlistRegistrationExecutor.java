package cloud.bamsongi.albammate.room.service.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistStateProjection;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 한 번의 대기 활성화를 최신 ROOM 상태 기준의 독립 트랜잭션에서 처리한다. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RoomWaitlistRegistrationExecutor {

	@NonNull private final RoomRepository roomRepository;
	@NonNull private final ParticipationRepository participationRepository;
	@NonNull private final RoomWaitlistRepository roomWaitlistRepository;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RoomWaitlistCommandService.RegistrationResult register(
		long currentUserId, long roomId, Instant requestTime) {
		Objects.requireNonNull(requestTime, "requestTime");
		Room room = roomRepository.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		Optional<Participation> participation = participationRepository.findByRoomIdAndUserId(roomId, currentUserId);
		Optional<RoomWaitlistStateProjection> waitlist = roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, currentUserId);

		room.reconcileStateAt(requestTime);
		validateEligibility(room, currentUserId, participation, waitlist, requestTime);
		if (waitlist.filter(state -> state.getStatus() == RoomWaitlistStatus.WAITING).isPresent()) {
			return new RoomWaitlistCommandService.RegistrationResult(
				toResponse(roomId, waitlist.orElseThrow()), false);
		}

		if (roomRepository.claimVersion(roomId, room.getVersion()) == 0) {
			throw new ObjectOptimisticLockingFailureException(Room.class, roomId);
		}
		long queueOrder = roomWaitlistRepository.getNextQueueOrder();
		if (waitlist.isPresent()) {
			roomWaitlistRepository.reactivateWaiting(roomId, currentUserId, queueOrder, requestTime);
		} else {
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, currentUserId, queueOrder, requestTime));
		}
		RoomWaitlistStateProjection activated = roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, currentUserId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		return new RoomWaitlistCommandService.RegistrationResult(toResponse(roomId, activated), true);
	}

	private void validateEligibility(
		Room room,
		long currentUserId,
		Optional<Participation> participation,
		Optional<RoomWaitlistStateProjection> waitlist,
		Instant requestTime) {
		if (!requestTime.isBefore(room.getStartAt())
			|| room.getStatus() == RoomStatus.CANCELED
			|| room.getStatus() == RoomStatus.FINISHED) {
			throw new BusinessException(ErrorCode.WAITLIST_NOT_AVAILABLE);
		}
		if (room.getHostUserId() == currentUserId
			|| participation.map(Participation::getStatus).filter(ParticipationStatus.ACTIVE::equals).isPresent()) {
			throw new BusinessException(ErrorCode.ALREADY_PARTICIPATING);
		}
		if (waitlist.filter(state -> state.getStatus() == RoomWaitlistStatus.WAITING).isPresent()) {
			return;
		}
		if (waitlist.map(RoomWaitlistStateProjection::getStatus)
			.filter(status -> status == RoomWaitlistStatus.EXPIRED || status == RoomWaitlistStatus.ROOM_CANCELED)
			.isPresent()) {
			throw new BusinessException(ErrorCode.WAITLIST_NOT_AVAILABLE);
		}
		if (room.getRemainingRecruitmentSeats() > 0) {
			throw new BusinessException(ErrorCode.WAITLIST_NOT_AVAILABLE);
		}
	}

	private MyRoomWaitlistResponse toResponse(long roomId, RoomWaitlistStateProjection state) {
		return new MyRoomWaitlistResponse(roomId, state.getStatus(), state.getPosition());
	}
}
