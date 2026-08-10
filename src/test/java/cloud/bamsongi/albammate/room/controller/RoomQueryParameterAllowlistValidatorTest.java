package cloud.bamsongi.albammate.room.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class RoomQueryParameterAllowlistValidatorTest {

	@Test
	void 방_목록과_내_모임_목록의_허용_parameter_이름을_통과시킨다() {
		MockHttpServletRequest roomRequest = new MockHttpServletRequest();
		roomRequest.addParameter("type", "GAME_FOCUSED");
		roomRequest.addParameter("status", "RECRUITING");
		roomRequest.addParameter("gameId", "1");
		roomRequest.addParameter("keyword", "카탄");
		roomRequest.addParameter("startsAtFrom", "2099-01-01T00:00:00Z");
		roomRequest.addParameter("startsAtTo", "2099-01-02T00:00:00Z");
		roomRequest.addParameter("minRemainingSeats", "2");
		roomRequest.addParameter("experienceLevels", "ALL_LEVELS");
		roomRequest.addParameter("rulemasterOnly", "true");
		roomRequest.addParameter("page", "0");
		roomRequest.addParameter("size", "10");
		MockHttpServletRequest falseRulemasterOnlyRequest = new MockHttpServletRequest();
		falseRulemasterOnlyRequest.addParameter("rulemasterOnly", "false");
		MockHttpServletRequest myRoomRequest = new MockHttpServletRequest();
		myRoomRequest.addParameter("role", "all");
		myRoomRequest.addParameter("page", "0");
		myRoomRequest.addParameter("size", "10");

		assertDoesNotThrow(() -> RoomQueryParameterAllowlistValidator.validateRoomList(roomRequest));
		assertDoesNotThrow(() -> RoomQueryParameterAllowlistValidator.validateRoomList(falseRulemasterOnlyRequest));
		assertDoesNotThrow(() -> RoomQueryParameterAllowlistValidator.validateMyRoomList(myRoomRequest));
	}

	@Test
	void 허용하지_않은_parameter_이름은_VALIDATION_ERROR다() {
		MockHttpServletRequest roomRequest = new MockHttpServletRequest();
		roomRequest.addParameter("sort", "startsAt");
		MockHttpServletRequest myRoomRequest = new MockHttpServletRequest();
		myRoomRequest.addParameter("keyword", "모임");

		BusinessException roomException = assertThrows(
			BusinessException.class, () -> RoomQueryParameterAllowlistValidator.validateRoomList(roomRequest));
		BusinessException myRoomException = assertThrows(
			BusinessException.class, () -> RoomQueryParameterAllowlistValidator.validateMyRoomList(myRoomRequest));

		assertEquals(ErrorCode.VALIDATION_ERROR, roomException.getErrorCode());
		assertEquals(ErrorCode.VALIDATION_ERROR, myRoomException.getErrorCode());
	}

	@Test
	void 방_목록의_rulemasterOnly는_true_false_단일값만_허용한다() {
		for (String value : List.of("yes", "on", "1")) {
			MockHttpServletRequest request = new MockHttpServletRequest();
			request.addParameter("rulemasterOnly", value);

			BusinessException exception = assertThrows(
				BusinessException.class, () -> RoomQueryParameterAllowlistValidator.validateRoomList(request));

			assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
		}

		MockHttpServletRequest duplicateRequest = new MockHttpServletRequest();
		duplicateRequest.addParameter("rulemasterOnly", "true");
		duplicateRequest.addParameter("rulemasterOnly", "false");

		BusinessException exception = assertThrows(
			BusinessException.class, () -> RoomQueryParameterAllowlistValidator.validateRoomList(duplicateRequest));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}
}
