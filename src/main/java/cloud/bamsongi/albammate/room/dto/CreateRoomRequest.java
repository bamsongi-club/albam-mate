package cloud.bamsongi.albammate.room.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 방 생성 요청의 입력 경계 계약이다. */
public record CreateRoomRequest(
	@NotNull RoomType roomType,
	@NotNull @Size(min = 1, max = 100) @Pattern(regexp = "^[^\\p{Cc}]*$") String title,
	@Size(max = 255) @Pattern(regexp = "^[^\\p{Cc}]*$") String description,
	@Positive Long gameId,
	@NotNull ExperienceLevel experienceLevel,
	@NotNull Boolean isRulemasterLed,
	@NotNull Instant startsAt,
	@NotNull @Size(min = 1, max = 100) @Pattern(regexp = "^[^\\p{Cc}]*$") String place,
	@NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(10) Integer recruitmentCapacity) {

	public CreateRoomRequest {
		title = title == null ? null : title.strip();
		place = place == null ? null : place.strip();
	}
}
