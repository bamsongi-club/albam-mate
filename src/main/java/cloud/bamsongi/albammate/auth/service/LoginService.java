package cloud.bamsongi.albammate.auth.service;

import cloud.bamsongi.albammate.auth.dto.LoginRequest;
import cloud.bamsongi.albammate.auth.dto.LoginRequest.Normalized;
import cloud.bamsongi.albammate.auth.exception.InvalidCredentialsException;
import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import cloud.bamsongi.albammate.global.config.PasswordSecurityProperties;
import cloud.bamsongi.albammate.global.security.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.PasswordHashExecutor;
import cloud.bamsongi.albammate.user.dto.UserAccount;
import cloud.bamsongi.albammate.user.dto.UserCredentials;
import cloud.bamsongi.albammate.user.service.UserAccountService;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** 로그인 입력·요청 제한·비밀번호 검증 순서를 조합한다. */
@Service
public class LoginService {

    /* 계정 유무에 따른 빠른 실패를 막기 위해 동일한 bcrypt 형식의 더미 해시를 사용한다. */
    private static final String FALLBACK_DUMMY_PASSWORD_HASH =
            "{bcrypt}$2a$10$lTDGeG4m75G2LLrPtRPtqu.6ThbERr2Srv4Jsjuc6rjQzwU5zPmtC";
    private static final String DUMMY_PASSWORD = "albam-mate-dummy-password";

    private final AuthenticationRequestLimiter requestLimiter;
    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordHashExecutor passwordHashExecutor;
    private final String dummyPasswordHash;

    /** 테스트와 모듈 단위 사용을 위한 기본 cost 생성자다. */
    public LoginService(
            AuthenticationRequestLimiter requestLimiter,
            UserAccountService userAccountService,
            PasswordEncoder passwordEncoder,
            PasswordHashExecutor passwordHashExecutor) {
        this(
                requestLimiter,
                userAccountService,
                passwordEncoder,
                passwordHashExecutor,
                FALLBACK_DUMMY_PASSWORD_HASH);
    }

    @Autowired
    public LoginService(
            AuthenticationRequestLimiter requestLimiter,
            UserAccountService userAccountService,
            PasswordEncoder passwordEncoder,
            PasswordHashExecutor passwordHashExecutor,
            PasswordSecurityProperties properties) {
        this(
                requestLimiter,
                userAccountService,
                passwordEncoder,
                passwordHashExecutor,
                createDummyPasswordHash(properties));
    }

    private LoginService(
            AuthenticationRequestLimiter requestLimiter,
            UserAccountService userAccountService,
            PasswordEncoder passwordEncoder,
            PasswordHashExecutor passwordHashExecutor,
            String dummyPasswordHash) {
        this.requestLimiter = Objects.requireNonNull(requestLimiter, "requestLimiter");
        this.userAccountService = Objects.requireNonNull(userAccountService, "userAccountService");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.passwordHashExecutor =
                Objects.requireNonNull(passwordHashExecutor, "passwordHashExecutor");
        this.dummyPasswordHash = Objects.requireNonNull(dummyPasswordHash, "dummyPasswordHash");
    }

    /** 사전 검증을 통과한 요청만 제한·검증하고, 성공한 자격증명의 공개 요약을 반환한다. */
    public UserAccount login(LoginRequest request, String remoteIp) {
        if (request == null) {
            throw new LoginValidationException();
        }
        Normalized normalized = request.normalizeAndValidate();

        requestLimiter.requireLoginAllowed(remoteIp);
        return requestLimiter.executeLoginVerification(
                normalized.email(),
                remoteIp,
                () -> {
                    requestLimiter.requireLoginFailureAllowed(normalized.email(), remoteIp);
                    return passwordHashExecutor.execute(
                            () -> verifyCredentials(normalized, remoteIp));
                });
    }

    private UserAccount verifyCredentials(Normalized normalized, String remoteIp) {
        Optional<UserCredentials> credentials =
                userAccountService.findCredentialsByEmail(normalized.email());
        String storedHash =
                credentials.map(UserCredentials::passwordHash).orElse(dummyPasswordHash);

        boolean matches = passwordEncoder.matches(normalized.password(), storedHash);
        if (!matches || credentials.isEmpty()) {
            requestLimiter.recordLoginFailure(normalized.email(), remoteIp).throwIfRejected();
            throw new InvalidCredentialsException();
        }

        UserCredentials authenticated = credentials.orElseThrow();
        if (passwordEncoder.upgradeEncoding(storedHash)) {
            String upgradedHash = passwordEncoder.encode(normalized.password());
            userAccountService.updatePasswordHash(authenticated.id(), upgradedHash);
        }
        requestLimiter.resetLoginFailures(normalized.email(), remoteIp);
        return new UserAccount(authenticated.id(), authenticated.nickname());
    }

    private static String createDummyPasswordHash(PasswordSecurityProperties properties) {
        PasswordSecurityProperties validated = Objects.requireNonNull(properties, "properties");
        validated.validate();
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(validated.getBcryptCost());
        return "{bcrypt}" + bcrypt.encode(DUMMY_PASSWORD);
    }
}
