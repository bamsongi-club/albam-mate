package cloud.bamsongi.albammate.game;

import java.util.List;
import org.springframework.data.domain.Page;

public record GamePageResponse(
        List<GameListItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    static GamePageResponse from(Page<GameListItem> result) {
        return new GamePageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }
}
