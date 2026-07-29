package cloud.bamsongi.albammate.auth.service;

import java.util.Objects;
import java.util.Optional;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.auth.exception.InvalidCredentialsException;
import cloud.bamsongi.albammate.global.config.PasswordSecurityProperties;
import cloud.bamsongi.albammate.global.security.password.PasswordHashExecutor;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserCredentials;

/** 로그인 입력·요청 제한·비밀번호 검증 순서를 조합한다. */
@Service
public class LoginService {

	/* 계정 유무에 따른 빠른 실패를 막기 위해 동일한 bcrypt 형식의 더미 해시를 사용한다. */
	private static final String DUMMY_PASSWORD = "albam-mate-dummy-password";

	private final AuthenticationRequestLimiter requestLimiter;
	private final UserAccountService userAccountService;
	private final PasswordEncoder passwordEncoder;
	private final PasswordHashExecutor passwordHashExecutor;
	private final String dummyPasswordHash;

	public LoginService(
		AuthenticationRequestLimiter requestLimiter,
		UserAccountService userAccountService,
		PasswordEncoder passwordEncoder,
		PasswordHashExecutor passwordHashExecutor,
		PasswordSecurityProperties properties) {
		this.requestLimiter = Objects.requireNonNull(requestLimiter, "requestLimiter");
		this.userAccountService = Objects.requireNonNull(userAccountService, "userAccountService");
		this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
		this.passwordHashExecutor = Objects.requireNonNull(passwordHashExecutor, "passwordHashExecutor");
		this.dummyPasswordHash = createDummyPasswordHash(properties);
	}

	/** 사전 검증을 통과한 요청만 제한·검증하고, 성공한 자격증명의 공개 요약을 반환한다. */
	public UserAccount login(LoginCommand command, String remoteIp) {
		requestLimiter.requireLoginAllowed(remoteIp);
		return requestLimiter.executeLoginVerification(
			command.email(),
			remoteIp,
			() -> {
				requestLimiter.requireLoginFailureAllowed(command.email(), remoteIp);
				return passwordHashExecutor.execute(() -> verifyCredentials(command, remoteIp));
			});
	}

	private UserAccount verifyCredentials(LoginCommand command, String remoteIp) {
		Optional<UserCredentials> credentials = userAccountService.findCredentialsByEmail(command.email());
		String storedHash = credentials.map(UserCredentials::passwordHash).orElse(dummyPasswordHash);

		boolean matches = passwordEncoder.matches(command.password(), storedHash);
		if (!matches || credentials.isEmpty()) {
			requestLimiter.recordLoginFailure(command.email(), remoteIp).throwIfRejected();
			throw new InvalidCredentialsException();
		}

		UserCredentials authenticated = credentials.orElseThrow();
		if (passwordEncoder.upgradeEncoding(storedHash)) {
			String upgradedHash = passwordEncoder.encode(command.password());
			userAccountService.updatePasswordHash(authenticated.id(), upgradedHash);
		}
		requestLimiter.resetLoginFailures(command.email(), remoteIp);
		return new UserAccount(authenticated.id(), authenticated.nickname());
	}

	private static String createDummyPasswordHash(PasswordSecurityProperties properties) {
		PasswordSecurityProperties validated = Objects.requireNonNull(properties, "properties");
		validated.validate();
		BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(validated.getBcryptCost());
		return "{bcrypt}" + bcrypt.encode(DUMMY_PASSWORD);
	}
}
