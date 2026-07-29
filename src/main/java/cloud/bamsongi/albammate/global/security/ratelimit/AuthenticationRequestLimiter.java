package cloud.bamsongi.albammate.global.security.ratelimit;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/** 회원가입·로그인 업무 모듈이 인증 요청 제한을 조합하는 기술 계약이다. */
public interface AuthenticationRequestLimiter {

	/** 사전 요청 검증 뒤 회원가입 IP 횟수를 원자적으로 확인하고 기록한다. */
	RateLimitDecision checkAndRecordSignup(String remoteIp);

	/** 사전 요청 검증 뒤 로그인 IP 횟수를 원자적으로 확인하고 기록한다. */
	RateLimitDecision checkAndRecordLogin(String remoteIp);

	/** 비밀번호 검증 전에 로그인 실패 버킷의 여유를 확인한다. */
	RateLimitDecision checkLoginFailureAllowed(String normalizedEmail, String remoteIp);

	/** 자격증명 불일치가 확정된 뒤 실패를 한 건 기록한다. */
	RateLimitDecision recordLoginFailure(String normalizedEmail, String remoteIp);

	/** 성공한 로그인의 이메일·IP 실패 버킷을 초기화한다. */
	void resetLoginFailures(String normalizedEmail, String remoteIp);

	/** 같은 이메일·IP 검증이 진행 중이면 빈 값을 반환하며, 해시 전에 호출한다. */
	Optional<LoginVerificationPermit> tryAcquireLoginVerification(
		String normalizedEmail, String remoteIp);

	default void requireSignupAllowed(String remoteIp) {
		checkAndRecordSignup(remoteIp).throwIfRejected();
	}

	default void requireLoginAllowed(String remoteIp) {
		checkAndRecordLogin(remoteIp).throwIfRejected();
	}

	default void requireLoginFailureAllowed(String normalizedEmail, String remoteIp) {
		checkLoginFailureAllowed(normalizedEmail, remoteIp).throwIfRejected();
	}

	/** 동일한 로그인 키의 검증을 하나만 진행하도록 예약하고, 이미 진행 중이면 즉시 거절한다. */
	default LoginVerificationPermit requireLoginVerification(
		String normalizedEmail, String remoteIp) {
		return tryAcquireLoginVerification(normalizedEmail, remoteIp)
			.orElseThrow(() -> new RateLimitExceededException(1));
	}

	/** 로그인 검증 권한을 얻은 동안만 작업하고 성공·실패·예외와 관계없이 권한을 반환한다. */
	default <T> T executeLoginVerification(
		String normalizedEmail, String remoteIp, Supplier<T> verificationWork) {
		Objects.requireNonNull(verificationWork, "verificationWork");
		try (LoginVerificationPermit ignored = requireLoginVerification(normalizedEmail, remoteIp)) {
			return verificationWork.get();
		}
	}

	default void executeLoginVerification(
		String normalizedEmail, String remoteIp, Runnable verificationWork) {
		Objects.requireNonNull(verificationWork, "verificationWork");
		executeLoginVerification(
			normalizedEmail,
			remoteIp,
			() -> {
				verificationWork.run();
				return null;
			});
	}
}
