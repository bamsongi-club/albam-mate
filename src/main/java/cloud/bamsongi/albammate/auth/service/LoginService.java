package cloud.bamsongi.albammate.auth.service;

import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.auth.exception.InvalidCredentialsException;
import cloud.bamsongi.albammate.global.config.PasswordSecurityProperties;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.password.PasswordHashExecutor;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserCredentials;

/** 로그인 입력·요청 제한·비밀번호 검증 순서를 조합한다. */
@Service
public class LoginService {

	// 계정 유무에 따른 빠른 실패를 막기 위해 동일한 bcrypt 형식의 더미 해시를 사용한다.
	private static final String DUMMY_PASSWORD = "albam-mate-dummy-password";

	private final AuthenticationRequestLimiter requestLimiter;
	private final UserAccountService userAccountService;
	private final PasswordEncoder passwordEncoder;
	private final PasswordHashExecutor passwordHashExecutor;
	private final String dummyPasswordHash;
	private final AuthNotificationMeasurementRecorder measurementRecorder;

	public LoginService(
		AuthenticationRequestLimiter requestLimiter,
		UserAccountService userAccountService,
		PasswordEncoder passwordEncoder,
		PasswordHashExecutor passwordHashExecutor,
		PasswordSecurityProperties properties) {
		this(requestLimiter, userAccountService, passwordEncoder, passwordHashExecutor, properties,
			(AuthNotificationMeasurementRecorder)null);
	}

	public LoginService(
		AuthenticationRequestLimiter requestLimiter,
		UserAccountService userAccountService,
		PasswordEncoder passwordEncoder,
		PasswordHashExecutor passwordHashExecutor,
		PasswordSecurityProperties properties,
		AuthNotificationMeasurementRecorder measurementRecorder) {
		this.requestLimiter = Objects.requireNonNull(requestLimiter, "requestLimiter");
		this.userAccountService = Objects.requireNonNull(userAccountService, "userAccountService");
		this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
		this.passwordHashExecutor = Objects.requireNonNull(passwordHashExecutor, "passwordHashExecutor");
		this.dummyPasswordHash = createDummyPasswordHash(properties);
		this.measurementRecorder = measurementRecorder;
	}

	@Autowired
	public LoginService(
		AuthenticationRequestLimiter requestLimiter,
		UserAccountService userAccountService,
		PasswordEncoder passwordEncoder,
		PasswordHashExecutor passwordHashExecutor,
		PasswordSecurityProperties properties,
		ObjectProvider<AuthNotificationMeasurementRecorder> measurementRecorder) {
		this(requestLimiter, userAccountService, passwordEncoder, passwordHashExecutor, properties,
			measurementRecorder.getIfAvailable());
	}

	/**
	 * 사전 검증을 통과한 요청만 제한·검증하고, 성공한 자격증명의 공개 요약을 반환한다.
	 *
	 * <p>네 겹의 제한은 각각 다른 것을 막으므로 하나로 줄일 수 없다.
	 *
	 * <ol>
	 * <li>IP 요청 총량: 한 IP가 여러 이메일을 훑는 시도를 막는다.
	 * <li>같은 이메일·IP의 동시 검증 1건: 병렬 요청이 실패 기록을 우회하는 것을 막는다.
	 * <li>실패 버킷 여유: 한 계정에 대한 반복 추측을 막는다.
	 * <li>전역 bcrypt 슬롯: 해시 비용으로 CPU가 고갈되는 것을 막는다.
	 * </ol>
	 *
	 * <p>동시 검증 게이트가 실패 버킷 확인을 감싸는 순서는 바꿀 수 없다. 실패는 검증이 끝난 뒤에 기록하므로, 같은 키의 병렬
	 * 요청을 먼저 막지 않으면 여러 요청이 모두 여유 확인을 통과한 뒤 각자 한 번씩 추측한다.
	 */
	public UserAccount login(LoginCommand command, String remoteIp) {
		String normalizedEmail = command.email().value();
		try {
			measure("request-limit", () -> requestLimiter.requireLoginAllowed(remoteIp));
		} catch (RuntimeException exception) {
			recordRejection(exception, "ip-limit");
			throw exception;
		}
		cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit permit;
		try {
			permit = measure("verification-gate",
				() -> requestLimiter.requireLoginVerification(normalizedEmail, remoteIp));
		} catch (RuntimeException exception) {
			recordRejection(exception, "verification-gate");
			throw exception;
		}
		try (permit) {
			try {
				measure("failure-limit", () -> requestLimiter.requireLoginFailureAllowed(normalizedEmail, remoteIp));
			} catch (RuntimeException exception) {
				recordRejection(exception, "failure-limit");
				throw exception;
			}
			return passwordHashExecutor.execute(() -> verifyCredentials(command, remoteIp));
		}
	}

	private UserAccount verifyCredentials(LoginCommand command, String remoteIp) {
		String normalizedEmail = command.email().value();
		Optional<UserCredentials> credentials = measure("user-lookup",
			() -> userAccountService.findCredentialsByEmail(command.email()));
		String storedHash = credentials.map(UserCredentials::passwordHash).orElse(dummyPasswordHash);

		// 계정이 없어도 더미 해시로 bcrypt를 돌린 뒤 계정 유무를 AND한다. 순서를 바꿔 계정 유무를 먼저 보면
		// 해시를 건너뛰어 응답 시간 차이로 계정 존재 여부가 드러난다.
		boolean matches = measure("bcrypt-verify", () -> passwordEncoder.matches(command.password(), storedHash));
		boolean credentialsVerified = matches && credentials.isPresent();
		if (!credentialsVerified) {
			measure("failure-record",
				() -> requestLimiter.recordLoginFailure(normalizedEmail, remoteIp).throwIfRejected());
			throw new InvalidCredentialsException();
		}

		UserCredentials authenticated = credentials.orElseThrow();
		if (measure("bcrypt-upgrade-check", () -> passwordEncoder.upgradeEncoding(storedHash))) {
			String upgradedHash = measure("bcrypt-upgrade-encode", () -> passwordEncoder.encode(command.password()));
			measure("password-hash-update",
				() -> userAccountService.updatePasswordHash(authenticated.id(), upgradedHash));
		}
		measure("failure-reset", () -> requestLimiter.resetLoginFailures(normalizedEmail, remoteIp));
		return UserAccount.from(authenticated);
	}

	private static String createDummyPasswordHash(PasswordSecurityProperties properties) {
		Objects.requireNonNull(properties, "properties");
		BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(properties.getBcryptCost());
		return "{bcrypt}" + bcrypt.encode(DUMMY_PASSWORD);
	}

	private <T> T measure(String stage, java.util.function.Supplier<T> work) {
		return measurementRecorder == null ? work.get() : measurementRecorder.authStage(stage, work);
	}

	private void measure(String stage, Runnable work) {
		if (measurementRecorder == null) {
			work.run();
		} else {
			measurementRecorder.authStage(stage, work);
		}
	}

	private void recordRejection(RuntimeException exception, String rateLimitSource) {
		if (measurementRecorder != null) {
			measurementRecorder.authRejection(
				exception instanceof RateLimitExceededException ? rateLimitSource : "redis-unavailable");
		}
	}
}
