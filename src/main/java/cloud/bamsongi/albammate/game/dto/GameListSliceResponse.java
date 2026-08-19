package cloud.bamsongi.albammate.game.dto;

import java.util.List;

import org.springframework.data.domain.Slice;

public record GameListSliceResponse<T>(List<T> content, int page, int size, boolean hasNext) {

	public static <T> GameListSliceResponse<T> from(Slice<T> slice) {
		return new GameListSliceResponse<>(
			slice.getContent(),
			slice.getNumber(),
			slice.getSize(),
			slice.hasNext());
	}
}
