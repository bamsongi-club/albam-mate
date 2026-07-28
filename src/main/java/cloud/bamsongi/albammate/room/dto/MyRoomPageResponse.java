package cloud.bamsongi.albammate.room.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** 내 모임 목록 API가 노출하는 페이지 정보다. */
public record MyRoomPageResponse(
        List<MyRoomListItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static MyRoomPageResponse from(Page<MyRoomListItem> result) {
        return new MyRoomPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }
}
