package cloud.bamsongi.albammate.global.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ApiResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void 성공_응답은_status와_data만_직렬화한다() throws Exception {
		ApiResponse<Map<String, String>> response = ApiResponse.success(HttpStatus.OK, Map.of("result", "ok"));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertEquals(200, json.get("status").intValue());
		assertEquals("ok", json.get("data").get("result").textValue());
		assertFalse(json.has("code"));
		assertFalse(json.has("message"));
	}

	@Test
	void 생성_성공_응답은_status_201을_직렬화한다() throws Exception {
		ApiResponse<Map<String, String>> response = ApiResponse.success(HttpStatus.CREATED,
			Map.of("result", "created"));

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertEquals(201, json.get("status").intValue());
		assertEquals("created", json.get("data").get("result").textValue());
	}

	@Test
	void 실패_응답은_계약된_필드와_null_data를_직렬화한다() throws Exception {
		ApiResponse<Void> response = ApiResponse.failure(ErrorCode.VALIDATION_ERROR);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertEquals(400, json.get("status").intValue());
		assertEquals("VALIDATION_ERROR", json.get("code").textValue());
		assertEquals("요청값 검증에 실패했습니다.", json.get("message").textValue());
		assertTrue(json.has("data"));
		assertTrue(json.get("data").isNull());
	}

	@Test
	void 데이터가_없는_성공_응답은_빈_객체를_사용한다() throws Exception {
		ApiResponse<Map<String, Object>> response = ApiResponse.success(HttpStatus.OK);

		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

		assertEquals(200, json.get("status").intValue());
		assertTrue(json.get("data").isObject());
		assertEquals(0, json.get("data").size());
	}

	@Test
	void 계약에_없는_204_성공_응답은_거부한다() {
		assertThrows(
			IllegalArgumentException.class,
			() -> ApiResponse.success(HttpStatus.NO_CONTENT, Map.of("result", "ok")));
		assertThrows(
			IllegalArgumentException.class, () -> ApiResponse.success(HttpStatus.NO_CONTENT));
	}
}
