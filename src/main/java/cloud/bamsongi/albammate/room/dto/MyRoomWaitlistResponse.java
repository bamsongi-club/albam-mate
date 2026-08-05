package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;

/** 현재 인증 사용자의 ROOM별 최신 대기 상태와 조회 시점 순번이다. */
public record MyRoomWaitlistResponse(Long roomId, RoomWaitlistStatus waitlistStatus, Long position) {
}
