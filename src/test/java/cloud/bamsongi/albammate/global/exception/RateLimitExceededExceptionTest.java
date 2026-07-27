package cloud.bamsongi.albammate.global.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class RateLimitExceededExceptionTest {

    @Test
    void 제한_초과는_공통_429_봉투와_Retry_After_헤더로_변환한다() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleRateLimitExceeded(new RateLimitExceededException(17));

        assertEquals(429, response.getStatusCode().value());
        assertEquals("17", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(), response.getBody().code());
        assertNull(response.getBody().data());
    }

    @Test
    void Retry_After는_최소_1초다() {
        RateLimitExceededException exception = new RateLimitExceededException(0);

        assertEquals(1, exception.getRetryAfterSeconds());
    }
}
