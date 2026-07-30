package cloud.bamsongi.albammate.game.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 빈 query parameter가 {@code null}로 바인딩돼도 nullable wrapper setter가
 * {@code page=0}, {@code size=10}, {@code upcomingOnly=false} 기본값을 보존한다.
 */
public class GameListRequest {

	private String keyword;
	private boolean upcomingOnly;

	@Min(0) private int page = 0;

	@Min(1) @Max(100) private int size = 10;

	public String getKeyword() {
		return keyword;
	}

	public void setKeyword(String keyword) {
		this.keyword = keyword;
	}

	public boolean isUpcomingOnly() {
		return upcomingOnly;
	}

	public void setUpcomingOnly(Boolean upcomingOnly) {
		this.upcomingOnly = Boolean.TRUE.equals(upcomingOnly);
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
