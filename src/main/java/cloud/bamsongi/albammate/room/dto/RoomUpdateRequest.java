package cloud.bamsongi.albammate.room.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** 방 부분 수정 요청에서 필드 생략과 명시적 {@code null}을 구분한다. */
public class RoomUpdateRequest {

	@Size(min = 1, max = 100) @Pattern(regexp = "^[^\\p{Cc}]*$") private String title;

	@Size(min = 1, max = 100) @Pattern(regexp = "^[^\\p{Cc}]*$") private String place;

	@Size(max = 255) @Pattern(regexp = "^[^\\p{Cc}]*$") private String description;

	@Positive private Long gameId;

	private ExperienceLevel experienceLevel;

	private Boolean rulemasterLed;

	private Instant startsAt;

	@Min(1) @Max(10) private Integer recruitmentCapacity;

	private boolean titleProvided;
	private boolean placeProvided;
	private boolean descriptionProvided;
	private boolean gameIdProvided;
	private boolean experienceLevelProvided;
	private boolean rulemasterLedProvided;
	private boolean startsAtProvided;
	private boolean recruitmentCapacityProvided;
	private boolean forbiddenFieldProvided;

	@JsonSetter(value = "title", nulls = Nulls.FAIL)
	public void setTitle(String title) {
		titleProvided = true;
		this.title = title.strip();
	}

	@JsonSetter(value = "place", nulls = Nulls.FAIL)
	public void setPlace(String place) {
		placeProvided = true;
		this.place = place.strip();
	}

	@JsonSetter("description")
	public void setDescription(String description) {
		descriptionProvided = true;
		this.description = description;
	}

	@JsonSetter("gameId")
	public void setGameId(Long gameId) {
		gameIdProvided = true;
		this.gameId = gameId;
	}

	@JsonSetter(value = "experienceLevel", nulls = Nulls.FAIL)
	public void setExperienceLevel(ExperienceLevel experienceLevel) {
		experienceLevelProvided = true;
		this.experienceLevel = experienceLevel;
	}

	@JsonSetter(value = "isRulemasterLed", nulls = Nulls.FAIL)
	public void setRulemasterLed(Boolean rulemasterLed) {
		rulemasterLedProvided = true;
		this.rulemasterLed = rulemasterLed;
	}

	@JsonSetter(value = "startsAt", nulls = Nulls.FAIL)
	public void setStartsAt(Instant startsAt) {
		startsAtProvided = true;
		this.startsAt = startsAt;
	}

	@JsonSetter(value = "recruitmentCapacity", nulls = Nulls.FAIL)
	public void setRecruitmentCapacity(Integer recruitmentCapacity) {
		recruitmentCapacityProvided = true;
		this.recruitmentCapacity = recruitmentCapacity;
	}

	@JsonSetter("roomType")
	public void markRoomTypeProvided(JsonNode ignored) {
		forbiddenFieldProvided = true;
	}

	@JsonSetter("region")
	public void markRegionProvided(JsonNode ignored) {
		forbiddenFieldProvided = true;
	}

	@JsonSetter("status")
	public void markStatusProvided(JsonNode ignored) {
		forbiddenFieldProvided = true;
	}

	@JsonIgnore
	public String title() {
		return title;
	}

	@JsonIgnore
	public String place() {
		return place;
	}

	@JsonIgnore
	public String description() {
		return description;
	}

	@JsonIgnore
	public Long gameId() {
		return gameId;
	}

	@JsonIgnore
	public ExperienceLevel experienceLevel() {
		return experienceLevel;
	}

	@JsonIgnore
	public Boolean rulemasterLed() {
		return rulemasterLed;
	}

	@JsonIgnore
	public Instant startsAt() {
		return startsAt;
	}

	@JsonIgnore
	public Integer recruitmentCapacity() {
		return recruitmentCapacity;
	}

	@JsonIgnore
	public boolean hasTitle() {
		return titleProvided;
	}

	@JsonIgnore
	public boolean hasPlace() {
		return placeProvided;
	}

	@JsonIgnore
	public boolean hasDescription() {
		return descriptionProvided;
	}

	@JsonIgnore
	public boolean hasGameId() {
		return gameIdProvided;
	}

	@JsonIgnore
	public boolean hasExperienceLevel() {
		return experienceLevelProvided;
	}

	@JsonIgnore
	public boolean hasRulemasterLed() {
		return rulemasterLedProvided;
	}

	@JsonIgnore
	public boolean hasStartsAt() {
		return startsAtProvided;
	}

	@JsonIgnore
	public boolean hasRecruitmentCapacity() {
		return recruitmentCapacityProvided;
	}

	@AssertTrue @JsonIgnore
	public boolean hasNoForbiddenFields() {
		return !forbiddenFieldProvided;
	}
}
