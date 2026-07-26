package cloud.bamsongi.albammate.global.exception;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpSessionRequiredException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void BusinessException은_오류_코드의_HTTP_상태와_메시지로_변환한다() throws Exception {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.ROOM_NOT_FOUND));

        assertEquals(404, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.ROOM_NOT_FOUND);
    }

    @Test
    void Bean_Validation_실패는_상세값을_노출하지_않고_검증_오류로_변환한다() throws Exception {
        BindException exception = new BindException(new Object(), "request");

        ResponseEntity<ApiResponse<Void>> response = handler.handleRequestException(exception);

        assertEquals(400, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.VALIDATION_ERROR);
    }

    @Test
    void 세션_필수_예외는_인증_필요_오류로_변환한다() throws Exception {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleMissingSession(new HttpSessionRequiredException("session"));

        assertEquals(401, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 처리하지_않은_예외는_원인_메시지_없이_서버_오류로_변환한다() throws Exception {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleUnhandledException(
                        new IllegalStateException("password=secret, userId=42"));

        assertEquals(500, response.getStatusCode().value());
        assertErrorBody(response.getBody(), ErrorCode.INTERNAL_SERVER_ERROR);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));
        assertTrue(json.toString().contains(ErrorCode.INTERNAL_SERVER_ERROR.getMessage()));
        assertTrue(!json.toString().contains("password"));
        assertTrue(!json.toString().contains("userId"));
        assertTrue(!json.toString().contains("secret"));
    }

    @Test
    void 로그용_예외는_원본_스택과_클래스명만_보존한다() {
        IllegalStateException source =
                new IllegalStateException(
                        "password=secret", new IllegalArgumentException("userId=42"));

        Throwable sanitized = GlobalExceptionHandler.sanitizeForLogging(source);

        assertEquals(source.getClass().getName(), sanitized.toString());
        assertNull(sanitized.getMessage());
        assertNull(sanitized.getCause());
        assertArrayEquals(source.getStackTrace(), sanitized.getStackTrace());
    }

    @Test
    void 인증과_권한_실패용_예외는_공통_코드로_변환한다() {
        assertEquals(
                401,
                handler.handleBusinessException(new UnauthenticatedException())
                        .getStatusCode()
                        .value());
        assertEquals(
                403,
                handler.handleBusinessException(new ForbiddenException()).getStatusCode().value());
        assertEquals(
                ErrorCode.CSRF_TOKEN_INVALID.getCode(),
                handler.handleBusinessException(new CsrfTokenInvalidException()).getBody().code());
    }

    private void assertErrorBody(ApiResponse<Void> body, ErrorCode expected) {
        assertEquals(expected.getStatus(), body.status());
        assertEquals(expected.getCode(), body.code());
        assertEquals(expected.getMessage(), body.message());
        assertTrue(body.data() == null);
    }
}
