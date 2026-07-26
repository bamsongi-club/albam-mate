package cloud.bamsongi.albammate.global.exception;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** MVC 경계의 예상·예상하지 못한 실패를 공통 API 오류 봉투로 변환한다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        return error(exception.getErrorCode());
    }

    @ExceptionHandler({
        BindException.class,
        ConstraintViolationException.class,
        HandlerMethodValidationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestValueException.class,
        MissingServletRequestPartException.class,
        ServletRequestBindingException.class,
        HttpRequestMethodNotSupportedException.class,
        HttpMediaTypeNotSupportedException.class,
        HttpMediaTypeNotAcceptableException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleRequestException(Exception exception) {
        return error(ErrorCode.VALIDATION_ERROR);
    }

    @ExceptionHandler(HttpSessionRequiredException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingSession(
            HttpSessionRequiredException exception) {
        return error(ErrorCode.UNAUTHENTICATED);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnhandledException(Exception exception) {
        log.error(
                "처리하지 않은 예외를 INTERNAL_SERVER_ERROR로 변환합니다. exceptionType={}",
                exception.getClass().getName(),
                sanitizeForLogging(exception));
        return error(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    static Throwable sanitizeForLogging(Throwable exception) {
        SanitizedThrowable sanitized = new SanitizedThrowable(exception.getClass().getName());
        sanitized.setStackTrace(exception.getStackTrace());
        return sanitized;
    }

    private ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode));
    }

    private static final class SanitizedThrowable extends Throwable {

        private static final long serialVersionUID = 1L;

        private final String typeName;

        private SanitizedThrowable(String typeName) {
            super(null, null, false, true);
            this.typeName = typeName;
        }

        @Override
        public String toString() {
            return typeName;
        }
    }
}
