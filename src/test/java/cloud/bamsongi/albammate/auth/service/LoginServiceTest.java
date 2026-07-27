package cloud.bamsongi.albammate.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.auth.dto.LoginRequest;
import cloud.bamsongi.albammate.auth.exception.InvalidCredentialsException;
import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.LoginVerificationPermit;
import cloud.bamsongi.albammate.global.security.PasswordHashConcurrencyLimiter;
import cloud.bamsongi.albammate.global.security.PasswordHashExecutor;
import cloud.bamsongi.albammate.global.security.PasswordHashPermit;
import cloud.bamsongi.albammate.global.security.RateLimitDecision;
import cloud.bamsongi.albammate.user.dto.UserAccount;
import cloud.bamsongi.albammate.user.dto.UserCredentials;
import cloud.bamsongi.albammate.user.service.UserAccountService;
import java.util.Optional;
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
    void 입력_검증이_실패하면_제한과_자격증명_조회가_실행되지_않는다() {
        LoginService service = serviceWithAvailableHashSlot();

        assertThrows(
                LoginValidationException.class,
                () -> service.login(new LoginRequest("not-an-email", "password"), "203.0.113.40"));

        verifyNoInteractions(requestLimiter, userAccountService, passwordEncoder);
    }

    @Test
    void 존재하는_계정의_자격증명이_틀리면_동일한_오류와_실패_기록을_반환한다() {
        LoginService service = serviceWithAvailableHashSlot();
        UserCredentials credentials = new UserCredentials(7L, "닉네임", "{bcrypt}stored");
        when(userAccountService.findCredentialsByEmail("user@example.com"))
                .thenReturn(Optional.of(credentials));
        when(passwordEncoder.matches("wrong", "{bcrypt}stored")).thenReturn(false);

        assertThrows(
                InvalidCredentialsException.class,
                () -> service.login(new LoginRequest("user@example.com", "wrong"), "203.0.113.41"));

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
                () ->
                        service.login(
                                new LoginRequest("missing@example.com", "password"),
                                "203.0.113.42"));

        verify(passwordEncoder)
                .matches(org.mockito.ArgumentMatchers.eq("password"), any(String.class));
        verify(requestLimiter).recordLoginFailure("missing@example.com", "203.0.113.42");
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
                service.login(new LoginRequest("user@example.com", "correct"), "203.0.113.43");

        assertEquals(new UserAccount(8L, "닉네임"), account);
        verify(userAccountService).updatePasswordHash(8L, "{bcrypt}new");
        verify(requestLimiter).resetLoginFailures("user@example.com", "203.0.113.43");
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
                        new PasswordHashExecutor(noSlotLimiter));

        configureLimiter();

        assertThrows(
                RateLimitExceededException.class,
                () ->
                        service.login(
                                new LoginRequest("user@example.com", "password"), "203.0.113.44"));

        verify(userAccountService, never()).findCredentialsByEmail(any());
        verify(requestLimiter, never()).recordLoginFailure(any(), any());
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
                new PasswordHashExecutor(limiter));
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
