package cloud.bamsongi.albammate.room.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class MyRoomListRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 누락하거나_빈_페이지_파라미터는_기본값을_유지한다() {
		MyRoomListRequest request = new MyRoomListRequest();
		request.setRole("all");
		request.setPage(null);
		request.setSize(null);

		assertEquals(0, request.getPage());
		assertEquals(10, request.getSize());
		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 역할과_페이지_크기의_유효한_경계를_허용한다() {
		MyRoomListRequest request = new MyRoomListRequest();
		request.setRole("joined");
		request.setPage(0);
		request.setSize(100);

		assertTrue(validator.validate(request).isEmpty());
	}

	@Test
	void 역할_누락과_페이지_크기_범위를_거절한다() {
		MyRoomListRequest missingRole = new MyRoomListRequest();
		MyRoomListRequest invalidRole = new MyRoomListRequest();
		invalidRole.setRole("ALL");
		MyRoomListRequest negativePage = new MyRoomListRequest();
		negativePage.setRole("all");
		negativePage.setPage(-1);
		MyRoomListRequest zeroSize = new MyRoomListRequest();
		zeroSize.setRole("all");
		zeroSize.setSize(0);
		MyRoomListRequest oversize = new MyRoomListRequest();
		oversize.setRole("all");
		oversize.setSize(101);

		assertFalse(validator.validate(missingRole).isEmpty());
		assertFalse(validator.validate(invalidRole).isEmpty());
		assertFalse(validator.validate(negativePage).isEmpty());
		assertFalse(validator.validate(zeroSize).isEmpty());
		assertFalse(validator.validate(oversize).isEmpty());
	}
}
