package cloud.bamsongi.albammate.room.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** 목록 API의 공통 페이지 필드만 노출하는 방 목록 응답이다. */
public record RoomPageResponse(
        List<PublicRoomResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static RoomPageResponse from(Page<PublicRoomResponse> result) {
        return new RoomPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }
}
