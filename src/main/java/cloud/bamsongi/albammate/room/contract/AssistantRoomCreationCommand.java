package cloud.bamsongi.albammate.room.contract;

import java.time.Instant;
import java.util.Objects;

/** AI 초안 확인이 Room 모듈에 전달하는 검증 완료 입력이다. */
public record AssistantRoomCreationCommand(
	String roomType,
	String title,
	String description,
	Long gameId,
	String experienceLevel,
	boolean rulemasterLed,
	Instant startsAt,
	String region,
	String place,
	int recruitmentCapacity) {

	public AssistantRoomCreationCommand {
		Objects.requireNonNull(roomType, "roomType");
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(experienceLevel, "experienceLevel");
		Objects.requireNonNull(startsAt, "startsAt");
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(place, "place");
	}
}
