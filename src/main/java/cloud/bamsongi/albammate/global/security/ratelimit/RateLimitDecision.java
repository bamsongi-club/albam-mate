package cloud.bamsongi.albammate.global.security.ratelimit;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/** 민감한 키를 포함하지 않고 인증 요청 제한 결과만 전달하는 값이다. */
public record RateLimitDecision(boolean allowed, int retryAfterSeconds, RejectionReason rejectionReason) {

	public enum RejectionReason {
		NONE,
		RATE_LIMIT_EXCEEDED,
		CAPACITY_SATURATED
	}

	public RateLimitDecision {
		if (retryAfterSeconds < 0) {
			throw new IllegalArgumentException("retry-after must be non-negative");
		}
		if (allowed && (retryAfterSeconds != 0 || rejectionReason != RejectionReason.NONE)) {
			throw new IllegalArgumentException("an allowed decision has no retry-after");
		}
		if (!allowed && rejectionReason == RejectionReason.RATE_LIMIT_EXCEEDED && retryAfterSeconds < 1) {
			throw new IllegalArgumentException("a rejected decision requires retry-after");
		}
		if (!allowed && rejectionReason == RejectionReason.CAPACITY_SATURATED && retryAfterSeconds != 0) {
			throw new IllegalArgumentException("a capacity rejection has no retry-after");
		}
	}

	public static RateLimitDecision permitted() {
		return new RateLimitDecision(true, 0, RejectionReason.NONE);
	}

	public static RateLimitDecision rejected(int retryAfterSeconds) {
		return new RateLimitDecision(false, Math.max(1, retryAfterSeconds), RejectionReason.RATE_LIMIT_EXCEEDED);
	}

	public static RateLimitDecision capacitySaturated() {
		return new RateLimitDecision(false, 0, RejectionReason.CAPACITY_SATURATED);
	}

	public void throwIfRejected() {
		if (rejectionReason == RejectionReason.RATE_LIMIT_EXCEEDED) {
			throw new RateLimitExceededException(retryAfterSeconds);
		}
		if (rejectionReason == RejectionReason.CAPACITY_SATURATED) {
			throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}
}
