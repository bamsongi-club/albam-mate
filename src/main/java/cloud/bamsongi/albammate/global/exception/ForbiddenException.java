package cloud.bamsongi.albammate.global.exception;

/** 인증된 사용자가 작업을 수행할 권한이 없음을 나타낸다. */
public final class ForbiddenException extends BusinessException {

	public ForbiddenException() {
		super(ErrorCode.FORBIDDEN);
	}

	public ForbiddenException(Throwable cause) {
		super(ErrorCode.FORBIDDEN, cause);
	}
}
