package cloud.bamsongi.albammate.room.service.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.UnauthenticatedException;
import cloud.bamsongi.albammate.room.dto.CreateRoomRequest;
import cloud.bamsongi.albammate.room.dto.NicknameSummary;
import cloud.bamsongi.albammate.room.dto.ParticipantRoomResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.MyRole;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoomCreateService {

	private final RoomRepository roomRepository;
	private final GameQuery gameQuery;
	private final UserQuery userQuery;
	private final Clock clock;

	/** 로그인한 사용자를 주최자로 기록하고 모집 중인 방을 생성한다. */
	@Transactional
	public ParticipantRoomResponse createRoom(long currentUserId, CreateRoomRequest request) {
		String hostNickname = userQuery
			.findNicknameById(currentUserId)
			.orElseThrow(UnauthenticatedException::new);
		GameSummary game = resolveGame(request);
		validateStartsAt(request.startsAt());

		Room room = Room.create(
			currentUserId,
			request.roomType(),
			request.title(),
			request.description(),
			request.gameId(),
			request.experienceLevel(),
			request.isRulemasterLed(),
			request.startsAt(),
			request.place(),
			request.recruitmentCapacity());
		Room savedRoom = roomRepository.save(room);
		NicknameSummary host = new NicknameSummary(hostNickname);
		return ParticipantRoomResponse.from(
			savedRoom,
			game,
			savedRoom.getActiveParticipantCount(),
			false,
			MyRole.HOST,
			host,
			java.util.List.of(host));
	}

	private GameSummary resolveGame(CreateRoomRequest request) {
		if (request.roomType() == RoomType.GAME_FOCUSED && request.gameId() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (request.gameId() == null) {
			return null;
		}
		Optional<GameSummary> game = gameQuery.findSummaryById(request.gameId());
		return game.orElseThrow(() -> new BusinessException(ErrorCode.GAME_NOT_FOUND));
	}

	private void validateStartsAt(Instant startsAt) {
		if (!startsAt.isAfter(Instant.now(clock))) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

}
