package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 방 목록 HTTP query parameter를 바인딩·검증한다. */
public class RoomListRequest {

	@NotNull private RoomType type;

	@Positive private Long gameId;

	private String keyword;

	@Min(0) private int page = 0;

	@Min(1) @Max(100) private int size = 10;

	private boolean gameIdProvided;
	private boolean keywordProvided;

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
		this.gameIdProvided = true;
		this.gameId = gameId;
	}

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keywordProvided = true;
		this.keyword = keyword;
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

	@AssertTrue public boolean isSearchConditionValid() {
		if (type == null) {
			return true;
		}
		if (type == RoomType.GAME_FOCUSED) {
			return gameIdProvided && gameId != null && !keywordProvided;
		}
		return !gameIdProvided;
	}
}
