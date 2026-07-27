package cloud.bamsongi.albammate.global.exception;

/** 인증 요청 제한 결과를 HTTP 경계까지 전달한다. 요청 키나 인증정보는 보관하지 않는다. */
public class RateLimitExceededException extends BusinessException {

    private final int retryAfterSeconds;

    public RateLimitExceededException(int retryAfterSeconds) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
