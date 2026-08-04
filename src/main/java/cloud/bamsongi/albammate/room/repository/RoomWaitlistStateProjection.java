package cloud.bamsongi.albammate.room.repository;

import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;

/** 한 snapshot에서 계산한 사용자의 최신 대기 상태와 화면 순번이다. */
public interface RoomWaitlistStateProjection {

	RoomWaitlistStatus getStatus();

	Long getQueueOrder();

	Long getPosition();
}
