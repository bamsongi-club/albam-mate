package cloud.bamsongi.albammate.room.service.command;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/** 방 수정 한 번을 상태 보정과 함께 독립된 쓰기 트랜잭션에서 실행한다. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class RoomUpdateExecutor {

	private final RoomRepository roomRepository;
	private final GameQuery gameQuery;
	private final UserQuery userQuery;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ParticipantRoomResponse updateRoom(
		long currentUserId, long roomId, RoomUpdateRequest request, Instant requestTime) {
		Room room = roomRepository
			.findById(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		if (room.getHostUserId() != currentUserId) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}

		room.reconcileStateAt(requestTime);
		if (room.getActiveParticipantCount() > 0) {
			throw new BusinessException(ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS);
		}
		if (room.getStatus() != RoomStatus.RECRUITING || !requestTime.isBefore(room.getStartAt())) {
			throw new BusinessException(ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
		}

		GameSummary game = resolveGame(room, request);
		validateStartsAt(request, requestTime);
		room.update(
			request.hasTitle() ? request.title() : room.getTitle(),
			request.hasDescription() ? request.description() : room.getDescription(),
			request.hasGameId() ? request.gameId() : room.getGameId(),
			request.hasExperienceLevel()
				? request.experienceLevel()
				: room.getExperienceLevel(),
			request.hasRulemasterLed() ? request.rulemasterLed() : room.isRulemasterLed(),
			request.hasStartsAt() ? request.startsAt() : room.getStartAt(),
			request.hasPlace() ? request.place() : room.getPlace(),
			request.hasRecruitmentCapacity()
				? request.recruitmentCapacity()
				: room.getCapacity());

		NicknameSummary host = new NicknameSummary(
			userQuery
				.findNicknameById(currentUserId)
				.orElseThrow(UnauthenticatedException::new));
		return ParticipantRoomResponse.from(
			room,
			game,
			room.getActiveParticipantCount(),
			false,
			MyRole.HOST,
			host,
			java.util.List.of(host));
	}

	private GameSummary resolveGame(Room room, RoomUpdateRequest request) {
		Long gameId = request.hasGameId() ? request.gameId() : room.getGameId();
		if (room.getRoomType() == RoomType.GAME_FOCUSED && gameId == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (gameId == null) {
			return null;
		}
		return gameQuery
			.findSummaryById(gameId)
			.orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
	}

	private void validateStartsAt(RoomUpdateRequest request, Instant requestTime) {
		if (request.hasStartsAt() && !request.startsAt().isAfter(requestTime)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

}
