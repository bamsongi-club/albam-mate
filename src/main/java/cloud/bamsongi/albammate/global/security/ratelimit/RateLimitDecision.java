package cloud.bamsongi.albammate.global.security.ratelimit;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/** 민감한 키를 포함하지 않고 인증 요청 제한 결과만 전달하는 값이다. */
public record RateLimitDecision(boolean allowed, int retryAfterSeconds) {

	public RateLimitDecision {
		if (retryAfterSeconds < 0 || (!allowed && retryAfterSeconds < 1)) {
			throw new IllegalArgumentException("retry-after must be non-negative");
		}
		if (allowed && retryAfterSeconds != 0) {
			throw new IllegalArgumentException("an allowed decision has no retry-after");
		}
	}

	public static RateLimitDecision permitted() {
		return new RateLimitDecision(true, 0);
	}

	public static RateLimitDecision rejected(int retryAfterSeconds) {
		return new RateLimitDecision(false, Math.max(1, retryAfterSeconds));
	}

	public boolean isAllowed() {
		return allowed;
	}

	public void throwIfRejected() {
		if (!allowed) {
			throw new RateLimitExceededException(retryAfterSeconds);
		}
	}
}
