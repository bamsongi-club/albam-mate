package cloud.bamsongi.albammate.auth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cloud.bamsongi.albammate.auth.dto.SignupRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock private AuthenticationRequestLimiter requestLimiter;

    @Mock private UserAccountService userAccountService;

    @Test
    void 사전_DTO_검증이_실패하면_요청제한과_계정생성을_호출하지_않는다() {
        SignupService service = new SignupService(requestLimiter, userAccountService);

        assertThrows(
                SignupValidationException.class,
                () ->
                        service.signup(
                                new SignupRequest("invalid", "123456789012345", "닉네임"),
                                "203.0.113.20"));

        verifyNoInteractions(requestLimiter, userAccountService);
    }

    @Test
    void 검증_뒤_IP_제한을_먼저_확인하고_사용자_모듈을_호출한다() {
        SignupService service = new SignupService(requestLimiter, userAccountService);
        UserAccount account = new UserAccount(7L, "닉네임");
        when(userAccountService.createAccount("user@example.com", "123456789012345", "닉네임"))
                .thenReturn(account);

        UserSummary summary =
                service.signup(
                        new SignupRequest(" USER@example.com ", "123456789012345", " 닉네임 "),
                        "203.0.113.21");

        assertEquals(new UserSummary(7L, "닉네임"), summary);
        InOrder inOrder = org.mockito.Mockito.inOrder(requestLimiter, userAccountService);
        inOrder.verify(requestLimiter).requireSignupAllowed("203.0.113.21");
        inOrder.verify(userAccountService)
                .createAccount("user@example.com", "123456789012345", "닉네임");
    }

    @Test
    void IP_제한을_초과하면_사용자_모듈을_호출하지_않는다() {
        SignupService service = new SignupService(requestLimiter, userAccountService);
        doThrow(new RateLimitExceededException(1))
                .when(requestLimiter)
                .requireSignupAllowed("203.0.113.22");

        assertThrows(
                RateLimitExceededException.class,
                () ->
                        service.signup(
                                new SignupRequest("user@example.com", "123456789012345", "닉네임"),
                                "203.0.113.22"));

        verify(userAccountService, never())
                .createAccount("user@example.com", "123456789012345", "닉네임");
    }
}
