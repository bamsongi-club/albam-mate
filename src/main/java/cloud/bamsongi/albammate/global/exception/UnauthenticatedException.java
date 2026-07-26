package cloud.bamsongi.albammate.global.exception;

/** 보호된 작업에 유효한 인증 사용자가 없음을 나타낸다. */
public final class UnauthenticatedException extends BusinessException {

    public UnauthenticatedException() {
        super(ErrorCode.UNAUTHENTICATED);
    }

    public UnauthenticatedException(Throwable cause) {
        super(ErrorCode.UNAUTHENTICATED, cause);
    }
}
