package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.matching.entity.MatchRequest;

class MatchRequestTest {

	@Test
	void 인원_범위는_SMALLINT_표현_범위까지_저장한다() {
		MatchRequest request = MatchRequest.create(
			1L, 1L, 1, Short.MAX_VALUE, MatchRequestStatus.WAITING);

		assertEquals(1, request.getMinPartySize());
		assertEquals(Short.MAX_VALUE, request.getMaxPartySize());
	}

	@Test
	void 인원_범위는_저장_타입을_넘거나_유효하지_않으면_거절한다() {
		assertThrows(
			IllegalArgumentException.class,
			() -> MatchRequest.create(1L, 1L, 0, 2, MatchRequestStatus.WAITING));
		assertThrows(
			IllegalArgumentException.class,
			() -> MatchRequest.create(1L, 1L, 3, 2, MatchRequestStatus.WAITING));
		assertThrows(
			IllegalArgumentException.class,
			() -> MatchRequest.create(1L, 1L, 1, Short.MAX_VALUE + 1, MatchRequestStatus.WAITING));
	}
}
