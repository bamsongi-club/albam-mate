package cloud.bamsongi.albammate.room.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import cloud.bamsongi.albammate.room.enums.RoomStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/** 방 종료 요청은 종료 상태 하나만 명시적으로 허용한다. */
public record RoomStatusUpdateRequest(@NotNull RoomStatus status) {

	@AssertTrue @JsonIgnore
	public boolean hasFinishedStatus() {
		return status == RoomStatus.FINISHED;
	}
}
