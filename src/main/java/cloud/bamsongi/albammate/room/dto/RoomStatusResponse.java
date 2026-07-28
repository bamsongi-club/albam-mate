package cloud.bamsongi.albammate.room.dto;

import cloud.bamsongi.albammate.room.enums.RoomStatus;

/** 방 취소·종료 뒤 변경된 상태만 반환하는 응답이다. */
public record RoomStatusResponse(long roomId, RoomStatus roomStatus) {}
