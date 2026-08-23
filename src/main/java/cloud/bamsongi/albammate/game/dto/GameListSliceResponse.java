package cloud.bamsongi.albammate.game.dto;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Slice;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 게임 목록 응답이다.
 *
 * <p>{@code totalElements}·{@code totalPages}는 필터·검색어가 전혀 없는 요청에서만 저장소 조회가 {@link Page}를
 * 반환할 때 채워진다({@code #1055}·{@code #1056} 결정). 그 외에는 {@code null}이며 응답에서 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GameListSliceResponse<T>(
	List<T> content, int page, int size, boolean hasNext, Long totalElements, Integer totalPages) {

	public static <T> GameListSliceResponse<T> from(Slice<T> slice) {
		if (slice instanceof Page<T> page) {
			return new GameListSliceResponse<>(
				page.getContent(),
				page.getNumber(),
				page.getSize(),
				page.hasNext(),
				page.getTotalElements(),
				page.getTotalPages());
		}
		return new GameListSliceResponse<>(
			slice.getContent(),
			slice.getNumber(),
			slice.getSize(),
			slice.hasNext(),
			null,
			null);
	}
}
