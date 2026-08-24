package cloud.bamsongi.albammate.room.service.query;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.dto.PublicRoomResponse;
import cloud.bamsongi.albammate.room.dto.RoomDetailResponse;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.service.RoomActionAvailability;
import cloud.bamsongi.albammate.room.service.RoomActionAvailabilityEvaluator;
import cloud.bamsongi.albammate.room.service.RoomActionAvailabilityFacts;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoomDetailService {

	@NonNull private final RoomStatusCorrectionCoordinator statusCorrectionCoordinator;
	@NonNull private final RoomDetailReadService roomDetailReadService;
	@NonNull private final GameQuery gameQuery;
	@NonNull private final UserQuery userQuery;
	@NonNull private final Clock clock;
	@NonNull private final RoomActionAvailabilityEvaluator roomActionAvailabilityEvaluator;

	/** 요청 시각으로 상태를 먼저 보정한 뒤, 요청자와 방 관계에 맞는 상세 응답을 조립한다. */
	public RoomDetailResponse findRoomDetail(long roomId, Optional<Long> currentUserId) {
		Instant requestTime = Instant.now(clock);
		statusCorrectionCoordinator.correctRoom(roomId, requestTime);

		RoomDetailReadService.RoomDetailReadResult readResult = roomDetailReadService.findRoomDetail(
			roomId, currentUserId.orElse(null));
		Room room = readResult.room();
		List<Participation> activeParticipations = readResult.activeParticipations();
		boolean isHost = currentUserId.filter(room.getHostUserId()::equals).isPresent();
		boolean isActiveParticipant = readResult.currentUserIsActiveParticipant();

		if (isFinal(room.getStatus()) && !isHost && !isActiveParticipant) {
			throw new BusinessException(ErrorCode.ROOM_NOT_FOUND);
		}

		GameSummary game = findGameSummary(room);
		RoomActionAvailability availability = roomActionAvailabilityEvaluator.evaluate(
			new RoomActionAvailabilityFacts(
				room,
				requestTime,
				currentUserId.isPresent(),
				isHost,
				isActiveParticipant,
				readResult.currentUserWaiting()));

		if (!isHost && !isActiveParticipant) {
			return PublicRoomResponse.from(room, game, availability);
		}

		List<Long> userIds = new ArrayList<>(activeParticipations.size() + 1);
		userIds.add(room.getHostUserId());
		for (Participation participation : activeParticipations) {
			userIds.add(participation.getUserId());
		}
		Map<Long, UserQuery.UserSummary> summariesById = userQuery.findUserSummariesByIds(userIds);

		NicknameSummary host = nicknameSummary(summariesById, room.getHostUserId());
		List<NicknameSummary> participants = new ArrayList<>();
		participants.add(host);
		for (Participation participation : activeParticipations) {
			participants.add(nicknameSummary(summariesById, participation.getUserId()));
		}
		return ParticipantRoomResponse.from(
			room,
			game,
			availability,
			isHost ? MyRole.HOST : MyRole.JOINED,
			host,
			List.copyOf(participants));
	}

	private GameSummary findGameSummary(Room room) {
		if (room.getGameId() == null) {
			return null;
		}
		return gameQuery
			.findSummaryById(room.getGameId())
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
	}

	private NicknameSummary nicknameSummary(Map<Long, UserQuery.UserSummary> summariesById, Long userId) {
		UserQuery.UserSummary summary = summariesById.get(userId);
		if (summary == null) {
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return new NicknameSummary(summary.nickname(), summary.profileImageUrl());
	}

	private boolean isFinal(RoomStatus status) {
		return status == RoomStatus.CANCELED || status == RoomStatus.FINISHED;
	}
}
