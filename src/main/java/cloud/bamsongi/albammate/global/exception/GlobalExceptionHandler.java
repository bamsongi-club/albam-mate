package cloud.bamsongi.albammate.global.exception;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import cloud.bamsongi.albammate.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/** MVC 경계의 예상·예상하지 못한 실패를 공통 API 오류 봉투로 변환한다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class GlobalExceptionHandler {

	@ExceptionHandler({RedisConnectionFailureException.class, RedisSystemException.class})
	public ResponseEntity<ApiResponse<Void>> handleRedisFailure(Exception exception) {
		return error(ErrorCode.SERVICE_UNAVAILABLE, exception);
	}

	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
		if (exception instanceof RateLimitExceededException rateLimitExceededException) {
			return handleRateLimitExceeded(rateLimitExceededException);
		}
		return error(exception.getErrorCode(), exception);
	}

	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiResponse<Void>> handleRateLimitExceeded(
		RateLimitExceededException exception) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(exception.getRetryAfterSeconds()));
		return error(exception.getErrorCode(), headers, exception);
	}

	@ExceptionHandler({
		BindException.class,
		ConstraintViolationException.class,
		HandlerMethodValidationException.class,
		HttpMessageNotReadableException.class,
		IllegalArgumentException.class,
		MaxUploadSizeExceededException.class,
		MethodArgumentTypeMismatchException.class,
		MissingRequestValueException.class,
		MissingServletRequestPartException.class,
		ServletRequestBindingException.class
	})
	public ResponseEntity<ApiResponse<Void>> handleRequestException(Exception exception) {
		return error(ErrorCode.VALIDATION_ERROR, exception);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
		HttpRequestMethodNotSupportedException exception) {
		return error(ErrorCode.METHOD_NOT_ALLOWED, exception.getHeaders(), exception);
	}

	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
		HttpMediaTypeNotSupportedException exception) {
		return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE, exception.getHeaders(), exception);
	}

	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotAcceptable(
		HttpMediaTypeNotAcceptableException exception) {
		return error(ErrorCode.NOT_ACCEPTABLE, exception.getHeaders(), exception);
	}

	@ExceptionHandler(NoHandlerFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(
		NoHandlerFoundException exception) {
		return error(ErrorCode.RESOURCE_NOT_FOUND, exception.getHeaders(), exception);
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
		NoResourceFoundException exception) {
		return error(ErrorCode.RESOURCE_NOT_FOUND, exception.getHeaders(), exception);
	}

	@ExceptionHandler(HttpSessionRequiredException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingSession(
		HttpSessionRequiredException exception) {
		return error(ErrorCode.UNAUTHENTICATED, exception);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnhandledException(Exception exception) {
		if (gameFailureEvent(currentRequest()) == null) {
			log.error(
				"처리하지 않은 예외를 INTERNAL_SERVER_ERROR로 변환합니다. exceptionType={}",
				exception.getClass().getName(),
				sanitizeForLogging(exception));
		}
		return error(ErrorCode.INTERNAL_SERVER_ERROR, exception);
	}

	static Throwable sanitizeForLogging(Throwable exception) {
		SanitizedThrowable sanitized = new SanitizedThrowable(exception.getClass().getName());
		sanitized.setStackTrace(exception.getStackTrace());
		return sanitized;
	}

	private ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode) {
		return error(errorCode, HttpHeaders.EMPTY, null);
	}

	private ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode, HttpHeaders headers) {
		return error(errorCode, headers, null);
	}

	private ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode, Exception exception) {
		return error(errorCode, HttpHeaders.EMPTY, exception);
	}

	private ResponseEntity<ApiResponse<Void>> error(
		ErrorCode errorCode, HttpHeaders headers, Exception exception) {
		logGameFailure(errorCode, exception);
		return ResponseEntity.status(errorCode.getHttpStatus())
			.headers(headers)
			.contentType(MediaType.APPLICATION_JSON)
			.body(ApiResponse.failure(errorCode));
	}

	private void logGameFailure(ErrorCode errorCode, Exception exception) {
		GameFailureEvent failureEvent = gameFailureEvent(currentRequest());
		if (failureEvent == null) {
			return;
		}
		if (errorCode.getHttpStatus().is5xxServerError()) {
			logTechnicalGameFailure(failureEvent, errorCode, exception);
			return;
		}
		if (failureEvent.gameId() == null) {
			log.info(
				"event={}{} outcome=rejected failureCode={}",
				failureEvent.event(),
				failureEvent.actionSuffix(),
				errorCode.getCode());
			return;
		}
		log.info(
			"event={}{} outcome=rejected failureCode={} gameId={}",
			failureEvent.event(),
			failureEvent.actionSuffix(),
			errorCode.getCode(),
			failureEvent.gameId());
	}

	private void logTechnicalGameFailure(
		GameFailureEvent failureEvent, ErrorCode errorCode, Exception exception) {
		String exceptionClass = exception == null ? "unknown" : exception.getClass().getName();
		if (failureEvent.gameId() == null) {
			log.error(
				"event={}{} outcome=failed failureCode={} exceptionClass={}",
				failureEvent.event(),
				failureEvent.actionSuffix(),
				errorCode.getCode(),
				exceptionClass);
			return;
		}
		log.error(
			"event={}{} outcome=failed failureCode={} exceptionClass={} gameId={}",
			failureEvent.event(),
			failureEvent.actionSuffix(),
			errorCode.getCode(),
			exceptionClass,
			failureEvent.gameId());
	}

	private HttpServletRequest currentRequest() {
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
			return attributes.getRequest();
		}
		return null;
	}

	private GameFailureEvent gameFailureEvent(HttpServletRequest request) {
		if (request == null) {
			return null;
		}
		String requestUri = request.getRequestURI();
		if ("GET".equals(request.getMethod()) && "/api/games".equals(requestUri)) {
			return new GameFailureEvent("game_search_failed", null, null);
		}
		if ("GET".equals(request.getMethod())) {
			String gameId = gameIdPathSegment(requestUri, "/api/games/");
			if (gameId != null) {
				return new GameFailureEvent("game_detail_failed", null, positiveGameIdOrNull(gameId));
			}
		}
		if ("PUT".equals(request.getMethod()) || "DELETE".equals(request.getMethod())) {
			String gameId = gameIdPathSegment(requestUri, "/api/users/me/played-games/");
			if (gameId != null) {
				String action = "PUT".equals(request.getMethod()) ? "mark" : "unmark";
				return new GameFailureEvent("game_played_state_change_failed", action, positiveGameIdOrNull(gameId));
			}
		}
		return null;
	}

	private String gameIdPathSegment(String requestUri, String prefix) {
		if (!requestUri.startsWith(prefix)) {
			return null;
		}
		String gameId = requestUri.substring(prefix.length());
		return gameId.isEmpty() || gameId.contains("/") ? null : gameId;
	}

	private Long positiveGameIdOrNull(String gameId) {
		try {
			long value = Long.parseLong(gameId);
			return value > 0 ? value : null;
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private record GameFailureEvent(String event, String action, Long gameId) {

		private String actionSuffix() {
			return action == null ? "" : " action=" + action;
		}
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
