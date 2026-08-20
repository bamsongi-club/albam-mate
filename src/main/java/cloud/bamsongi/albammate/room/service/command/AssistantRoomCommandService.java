package cloud.bamsongi.albammate.room.service.command;

import java.time.Clock;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.game.contract.GameQuery;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserAccessor;
import cloud.bamsongi.albammate.room.contract.AssistantRoomCommand;
import cloud.bamsongi.albammate.room.contract.AssistantRoomCreationCommand;
import cloud.bamsongi.albammate.room.contract.AssistantRoomCreationResult;
import cloud.bamsongi.albammate.room.contract.RoomCreated;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.Region;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import lombok.RequiredArgsConstructor;

/** AI-03 확인형 입력을 기존 ROOM 생성 트랜잭션으로 전환한다. */
@Service
@RequiredArgsConstructor
public class AssistantRoomCommandService implements AssistantRoomCommand {

	private final RoomRepository roomRepository;
	private final GameQuery gameQuery;
	private final CurrentUserAccessor currentUserAccessor;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Override
	@Transactional
	public AssistantRoomCreationResult createConfirmedRoom(AssistantRoomCreationCommand command) {
		long currentUserId = currentUserAccessor.requireCurrentUserId();
		RoomType roomType = parseRoomType(command.roomType());
		ExperienceLevel experienceLevel = parseExperienceLevel(command.experienceLevel());
		Region region = parseRegion(command.region());
		if (!command.startsAt().isAfter(clock.instant())
			|| command.recruitmentCapacity() < 1 || command.recruitmentCapacity() > 10
			|| command.title().isBlank() || command.title().length() > 100
			|| command.place().isBlank() || command.place().length() > 100
			|| containsControlCharacter(command.title()) || containsControlCharacter(command.description())
			|| containsControlCharacter(command.place())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (roomType == RoomType.GAME_FOCUSED && command.gameId() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (command.gameId() != null && gameQuery.findSummaryById(command.gameId()).isEmpty()) {
			throw new BusinessException(ErrorCode.GAME_NOT_FOUND);
		}
		Room room = roomRepository.save(Room.create(
			currentUserId, roomType, command.title(), command.description(), command.gameId(), experienceLevel,
			command.rulemasterLed(), command.startsAt(), region.value(), command.place(),
			command.recruitmentCapacity()));
		RoomCreated created = new RoomCreated(room.getId());
		eventPublisher.publishEvent(created);
		return new AssistantRoomCreationResult(room.getId(), created.requireChatRoomId());
	}

	private RoomType parseRoomType(String value) {
		try {
			return RoomType.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private ExperienceLevel parseExperienceLevel(String value) {
		try {
			return ExperienceLevel.valueOf(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private Region parseRegion(String value) {
		try {
			return Region.from(value);
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private boolean containsControlCharacter(String value) {
		return value != null && value.codePoints().anyMatch(Character::isISOControl);
	}
}
