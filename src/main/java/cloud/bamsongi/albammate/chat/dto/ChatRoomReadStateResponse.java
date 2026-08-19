package cloud.bamsongi.albammate.chat.dto;

import java.time.Instant;

/** 읽음 처리 API 응답이다. */
public record ChatRoomReadStateResponse(Long roomId, Long lastReadMessageId, Instant updatedAt) {
}
