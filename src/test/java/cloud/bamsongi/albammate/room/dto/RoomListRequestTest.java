package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.room.enums.RoomType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class RoomListRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 사람_중심_목록은_누락하거나_빈_페이지의_기본값을_유지한다() {
		RoomListRequest request = new RoomListRequest();
		request.setType(RoomType.PERSON_FOCUSED);
		request.setPage(null);
		request.setSize(null);

		assertEquals(0, request.getPage());
		assertEquals(10, request.getSize());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 게임_중심은_gameId를_명시해야_하고_keyword를_명시하면_안된다() {
		RoomListRequest validRequest = new RoomListRequest();
		validRequest.setType(RoomType.GAME_FOCUSED);
		validRequest.setGameId(1L);

		RoomListRequest missingGameId = new RoomListRequest();
		missingGameId.setType(RoomType.GAME_FOCUSED);
		RoomListRequest keywordProvided = new RoomListRequest();
		keywordProvided.setType(RoomType.GAME_FOCUSED);
		keywordProvided.setGameId(1L);
		keywordProvided.setKeyword("");

		assertTrue(validator.validate(validRequest).isEmpty());
		assertFalse(validator.validate(missingGameId).isEmpty());
		assertFalse(validator.validate(keywordProvided).isEmpty());
	}

	@Test
	void 사람_중심은_gameId의_명시적_빈_값도_허용하지_않는다() {
		RoomListRequest request = new RoomListRequest();
		request.setType(RoomType.PERSON_FOCUSED);
		request.setGameId(null);

		assertFalse(validator.validate(request).isEmpty());
	}

	@Test
	void 필수_유형과_gameId와_페이지_크기_범위를_검증한다() {
		RoomListRequest missingType = new RoomListRequest();
		RoomListRequest invalidGameId = new RoomListRequest();
		invalidGameId.setType(RoomType.GAME_FOCUSED);
		invalidGameId.setGameId(0L);
		RoomListRequest invalidPage = new RoomListRequest();
		invalidPage.setType(RoomType.PERSON_FOCUSED);
		invalidPage.setPage(-1);
		RoomListRequest invalidSize = new RoomListRequest();
		invalidSize.setType(RoomType.PERSON_FOCUSED);
		invalidSize.setSize(101);

		assertFalse(validator.validate(missingType).isEmpty());
		assertFalse(validator.validate(invalidGameId).isEmpty());
		assertFalse(validator.validate(invalidPage).isEmpty());
		assertFalse(validator.validate(invalidSize).isEmpty());
	}
}
