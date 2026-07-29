package cloud.bamsongi.albammate.global.response;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

	@Test
	void Page를_페이지네이션_계약_필드로_변환한다() {
		PageResponse<String> response = PageResponse.from(new PageImpl<>(List.of("first"), PageRequest.of(1, 1), 3));

		assertEquals(List.of("first"), response.content());
		assertEquals(1, response.page());
		assertEquals(1, response.size());
		assertEquals(3, response.totalElements());
		assertEquals(3, response.totalPages());
		assertEquals(true, response.hasNext());
	}
}
