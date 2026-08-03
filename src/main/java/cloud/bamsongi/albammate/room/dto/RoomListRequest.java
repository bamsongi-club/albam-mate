package cloud.bamsongi.albammate.room.dto;

import java.time.Instant;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;

import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** 방 목록 HTTP query parameter를 바인딩·검증한다. */
public class RoomListRequest {

	private RoomType type;

	@Positive private Long gameId;

	private String keyword;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
	private Instant startsAtFrom;

	@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
	private Instant startsAtTo;

	@Min(1) @Max(10) private Integer minRemainingSeats;

	private Set<ExperienceLevel> experienceLevels = Set.of();

	private boolean rulemasterOnly;

	@Min(0) private int page = 0;

	@Min(1) @Max(100) private int size = 10;

	public RoomType getType() {
		return type;
	}

	public void setType(RoomType type) {
		this.type = type;
	}

	public Long getGameId() {
		return gameId;
	}

	public void setGameId(Long gameId) {
		this.gameId = gameId;
	}

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}

	public Instant getStartsAtFrom() {
		return startsAtFrom;
	}

	public void setStartsAtFrom(Instant startsAtFrom) {
		this.startsAtFrom = startsAtFrom;
	}

	public Instant getStartsAtTo() {
		return startsAtTo;
	}

	public void setStartsAtTo(Instant startsAtTo) {
		this.startsAtTo = startsAtTo;
	}

	public Integer getMinRemainingSeats() {
		return minRemainingSeats;
	}

	public void setMinRemainingSeats(Integer minRemainingSeats) {
		this.minRemainingSeats = minRemainingSeats;
	}

	public Set<ExperienceLevel> getExperienceLevels() {
		return experienceLevels;
	}

	public void setExperienceLevels(Set<ExperienceLevel> experienceLevels) {
		this.experienceLevels = experienceLevels == null ? Set.of() : Set.copyOf(experienceLevels);
	}

	public boolean isRulemasterOnly() {
		return rulemasterOnly;
	}

	public void setRulemasterOnly(Boolean rulemasterOnly) {
		if (rulemasterOnly != null) {
			this.rulemasterOnly = rulemasterOnly;
		}
	}

	@AssertTrue public boolean isStartsAtRangeValid() {
		return startsAtFrom == null || startsAtTo == null || startsAtFrom.isBefore(startsAtTo);
	}

	public int getPage() {
		return page;
	}

	public void setPage(Integer page) {
		if (page != null) {
			this.page = page;
		}
	}

	public int getSize() {
		return size;
	}

	public void setSize(Integer size) {
		if (size != null) {
			this.size = size;
		}
	}

}
