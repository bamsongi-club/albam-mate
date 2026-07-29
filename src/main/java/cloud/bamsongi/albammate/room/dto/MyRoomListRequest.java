package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 내 모임 목록 HTTP query parameter를 바인딩·검증한다. */
public class MyRoomListRequest {

	@NotNull private MyRoomRole role;

	@Min(0) private int page = 0;

	@Min(1) @Max(100) private int size = 10;

	public MyRoomRole getRole() {
		return role;
	}

	public void setRole(MyRoomRole role) {
		this.role = role;
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
