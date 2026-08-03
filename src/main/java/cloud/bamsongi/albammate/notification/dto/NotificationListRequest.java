package cloud.bamsongi.albammate.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 알림 목록의 offset 페이지 query parameter를 바인딩·검증한다. */
public class NotificationListRequest {

	@Min(0) private int page = 0;
	@Min(1) @Max(100) private int size = 10;

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
