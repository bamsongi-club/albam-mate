package cloud.bamsongi.albammate.global.exception;

/** 상태를 변경하는 요청에 CSRF 토큰이 없거나 유효하지 않음을 나타낸다. */
public final class CsrfTokenInvalidException extends BusinessException {

	public CsrfTokenInvalidException() {
		super(ErrorCode.CSRF_TOKEN_INVALID);
	}

	public CsrfTokenInvalidException(Throwable cause) {
		super(ErrorCode.CSRF_TOKEN_INVALID, cause);
	}
}
