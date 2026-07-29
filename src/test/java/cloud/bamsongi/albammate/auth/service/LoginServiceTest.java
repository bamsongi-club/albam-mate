package cloud.bamsongi.albammate.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.auth.dto.LoginRequest;
import cloud.bamsongi.albammate.auth.exception.InvalidCredentialsException;
import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
import cloud.bamsongi.albammate.global.config.PasswordSecurityProperties;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.password.PasswordHashConcurrencyLimiter;
import cloud.bamsongi.albammate.global.security.password.PasswordHashExecutor;
import cloud.bamsongi.albammate.global.security.password.PasswordHashPermit;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.ratelimit.InMemoryAuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit;
import cloud.bamsongi.albammate.global.security.ratelimit.RateLimitDecision;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserCredentials;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private AuthenticationRequestLimiter requestLimiter;

    @Mock private UserAccountService userAccountService;

    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void 존재하는_계정의_자격증명이_틀리면_동일한_오류와_실패_기록을_반환한다() {
        LoginService service = serviceWithAvailableHashSlot();
        UserCredentials credentials = new UserCredentials(7L, "닉네임", "{bcrypt}stored");
        when(userAccountService.findCredentialsByEmail("user@example.com"))
                .thenReturn(Optional.of(credentials));
        when(passwordEncoder.matches("wrong", "{bcrypt}stored")).thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login(normalized("user@example.com", "wrong"), "203.0.113.41"));

        verify(requestLimiter).recordLoginFailure("user@example.com", "203.0.113.41");
        verify(requestLimiter, never()).resetLoginFailures(any(), any());
    }

    @Test
    void 계정이_없어도_더미_해시로_검증하고_같은_자격증명_오류를_반환한다() {
        LoginService service = serviceWithAvailableHashSlot();
        when(userAccountService.findCredentialsByEmail("missing@example.com"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.matches(
                        org.mockito.ArgumentMatchers.eq("password"), any(String.class)))
                .thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login(normalized("missing@example.com", "password"), "203.0.113.42"));

        verify(passwordEncoder)
                .matches(org.mockito.ArgumentMatchers.eq("password"), any(String.class));
        verify(requestLimiter).recordLoginFailure("missing@example.com", "203.0.113.42");
    }

    @Test
    void 없는_계정에서_더미_해시가_일치해도_실패를_기록하고_자격증명_오류를_반환한다() {
        LoginService service = serviceWithAvailableHashSlot();
        when(userAccountService.findCredentialsByEmail("missing@example.com"))
                .thenReturn(Optional.empty());
        when(passwordEncoder.matches(
                        org.mockito.ArgumentMatchers.eq("password"), any(String.class)))
                .thenReturn(true);

        assertThrows(
                InvalidCredentialsException.class,
                () ->
                        service.login(
                                normalized("missing@example.com", "password"), "203.0.113.421"));

        verify(requestLimiter).recordLoginFailure("missing@example.com", "203.0.113.421");
        verify(requestLimiter, never()).resetLoginFailures(any(), any());
        verify(passwordEncoder, never()).upgradeEncoding(any());
        verify(passwordEncoder, never()).encode(any());
        verify(userAccountService, never()).updatePasswordHash(any(), any());
    }

    @Test
    void 이전_cost의_해시는_성공한_로그인에서만_재저장하고_실패_버킷을_초기화한다() {
        LoginService service = serviceWithAvailableHashSlot();
        UserCredentials credentials = new UserCredentials(8L, "닉네임", "{bcrypt}old");
        when(userAccountService.findCredentialsByEmail("user@example.com"))
                .thenReturn(Optional.of(credentials));
        when(passwordEncoder.matches("correct", "{bcrypt}old")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("{bcrypt}old")).thenReturn(true);
        when(passwordEncoder.encode("correct")).thenReturn("{bcrypt}new");

        UserAccount account =
                service.login(normalized("user@example.com", "correct"), "203.0.113.43");

        assertEquals(new UserAccount(8L, "닉네임"), account);
        verify(userAccountService).updatePasswordHash(8L, "{bcrypt}new");
        verify(requestLimiter).resetLoginFailures("user@example.com", "203.0.113.43");
    }

    @Test
    void 현재_cost_해시의_성공_로그인은_실패를_초기화하고_해시를_다시_저장하지_않는다() {
        LoginService service = serviceWithAvailableHashSlot();
        UserCredentials credentials = new UserCredentials(9L, "닉네임", "{bcrypt}current");
        when(userAccountService.findCredentialsByEmail("user@example.com"))
                .thenReturn(Optional.of(credentials));
        when(passwordEncoder.matches("correct", "{bcrypt}current")).thenReturn(true);
        when(passwordEncoder.upgradeEncoding("{bcrypt}current")).thenReturn(false);

        UserAccount account =
                service.login(normalized("user@example.com", "correct"), "203.0.113.431");

        assertEquals(new UserAccount(9L, "닉네임"), account);
        verify(requestLimiter).resetLoginFailures("user@example.com", "203.0.113.431");
        verify(passwordEncoder, never()).encode(any());
        verify(userAccountService, never()).updatePasswordHash(any(), any());
    }

    @Test
    void 해시_슬롯이_없으면_자격증명_검증과_실패_기록을_수행하지_않는다() {
        PasswordHashConcurrencyLimiter noSlotLimiter =
                new PasswordHashConcurrencyLimiter() {
                    @Override
                    public Optional<PasswordHashPermit> tryAcquire() {
                        return Optional.empty();
                    }

                    @Override
                    public int maxConcurrent() {
                        return 1;
                    }

                    @Override
                    public int currentConcurrent() {
                        return 1;
                    }
                };
        LoginService service =
                new LoginService(
                        requestLimiter,
                        userAccountService,
                        passwordEncoder,
                        new PasswordHashExecutor(noSlotLimiter),
                        passwordSecurityProperties());

        configureLimiter();

        assertThrows(
                RateLimitExceededException.class,
                () -> service.login(normalized("user@example.com", "password"), "203.0.113.44"));

        verify(userAccountService, never()).findCredentialsByEmail(any());
        verify(requestLimiter, never()).recordLoginFailure(any(), any());
    }

    @Test
    void 동일_정규화_이메일과_IP의_동시_로그인은_하나만_검증하고_실패를_5회까지만_기록한다() throws Exception {
        InMemoryAuthenticationRequestLimiter limiter =
                new InMemoryAuthenticationRequestLimiter(
                        new AuthenticationRequestProtectionProperties(),
                        java.time.Clock.systemUTC());
        PasswordHashConcurrencyLimiter hashLimiter =
                new PasswordHashConcurrencyLimiter() {
                    @Override
                    public Optional<PasswordHashPermit> tryAcquire() {
                        return Optional.of(() -> {});
                    }

                    @Override
                    public int maxConcurrent() {
                        return 1;
                    }

                    @Override
                    public int currentConcurrent() {
                        return 0;
                    }
                };
        CountDownLatch verificationStarted = new CountDownLatch(1);
        CountDownLatch releaseVerification = new CountDownLatch(1);
        AtomicInteger passwordMatches = new AtomicInteger();
        PasswordEncoder blockingPasswordEncoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        when(blockingPasswordEncoder.matches(any(String.class), any(String.class)))
                .thenAnswer(
                        invocation -> {
                            passwordMatches.incrementAndGet();
                            verificationStarted.countDown();
                            if (!releaseVerification.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("로그인 검증이 제때 해제되지 않았습니다.");
                            }
                            return false;
                        });
        when(userAccountService.findCredentialsByEmail("user@example.com"))
                .thenReturn(Optional.of(new UserCredentials(12L, "닉네임", "{bcrypt}stored")));
        LoginService service =
                new LoginService(
                        limiter,
                        userAccountService,
                        blockingPasswordEncoder,
                        new PasswordHashExecutor(hashLimiter),
                        passwordSecurityProperties());
        String remoteIp = "203.0.113.45";

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstLogin =
                    executor.submit(
                            () ->
                                    assertThrows(
                                            InvalidCredentialsException.class,
                                            () ->
                                                    service.login(
                                                            normalized(
                                                                    " User@Example.COM ", "wrong"),
                                                            remoteIp)));
            assertTrue(verificationStarted.await(5, TimeUnit.SECONDS));

            assertThrows(
                    RateLimitExceededException.class,
                    () -> service.login(normalized("user@example.com", "wrong"), remoteIp));
            releaseVerification.countDown();
            firstLogin.get(5, TimeUnit.SECONDS);

            for (int attempt = 0; attempt < 4; attempt++) {
                assertThrows(
                        InvalidCredentialsException.class,
                        () -> service.login(normalized("user@example.com", "wrong"), remoteIp));
            }
            assertThrows(
                    RateLimitExceededException.class,
                    () -> service.login(normalized("user@example.com", "wrong"), remoteIp));

            assertEquals(5, passwordMatches.get());
            assertFalse(limiter.checkLoginFailureAllowed("user@example.com", remoteIp).allowed());
        } finally {
            releaseVerification.countDown();
            executor.shutdownNow();
        }
    }

    private LoginService serviceWithAvailableHashSlot() {
        PasswordHashConcurrencyLimiter limiter =
                new PasswordHashConcurrencyLimiter() {
                    @Override
                    public Optional<PasswordHashPermit> tryAcquire() {
                        return Optional.of(() -> {});
                    }

                    @Override
                    public int maxConcurrent() {
                        return 1;
                    }

                    @Override
                    public int currentConcurrent() {
                        return 0;
                    }
                };
        configureLimiter();
        return new LoginService(
                requestLimiter,
                userAccountService,
                passwordEncoder,
                new PasswordHashExecutor(limiter),
                passwordSecurityProperties());
    }

    private LoginRequest.Normalized normalized(String email, String password) {
        return new LoginRequest(email, password).normalize();
    }

    private PasswordSecurityProperties passwordSecurityProperties() {
        return new PasswordSecurityProperties();
    }

    private void configureLimiter() {
        lenient()
                .when(requestLimiter.checkAndRecordLogin(any(String.class)))
                .thenReturn(RateLimitDecision.permitted());
        lenient()
                .when(requestLimiter.checkLoginFailureAllowed(any(String.class), any(String.class)))
                .thenReturn(RateLimitDecision.permitted());
        lenient()
                .when(requestLimiter.recordLoginFailure(any(String.class), any(String.class)))
                .thenReturn(RateLimitDecision.permitted());
        lenient()
                .when(
                        requestLimiter.tryAcquireLoginVerification(
                                any(String.class), any(String.class)))
                .thenReturn(Optional.of((LoginVerificationPermit) () -> {}));
    }
}
