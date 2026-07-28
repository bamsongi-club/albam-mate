package cloud.bamsongi.albammate.global.response;

import java.util.List;
import org.springframework.data.domain.Page;

/** 목록 API가 공유하는 페이지 정보다. */
public record PageResponse<T>(
        List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }
}
