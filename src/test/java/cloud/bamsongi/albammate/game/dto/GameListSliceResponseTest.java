package cloud.bamsongi.albammate.game.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

class GameListSliceResponseTest {

	@Test
	void T5_Page_조회_결과는_전체건수와_나머지를_올림한_totalPages를_그대로_노출한다() {
		var page = new PageImpl<>(List.of("a", "b"), PageRequest.of(0, 2), 5);

		GameListSliceResponse<String> response = GameListSliceResponse.from(page);

		assertEquals(5L, response.totalElements());
		assertEquals(3, response.totalPages());
	}

	@Test
	void Slice_조회_결과는_totalElements와_totalPages를_노출하지_않는다() {
		var slice = new SliceImpl<>(List.of("a"), PageRequest.of(0, 10), true);

		GameListSliceResponse<String> response = GameListSliceResponse.from(slice);

		assertNull(response.totalElements());
		assertNull(response.totalPages());
	}
}
