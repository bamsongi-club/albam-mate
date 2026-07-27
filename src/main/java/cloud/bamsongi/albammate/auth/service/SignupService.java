package cloud.bamsongi.albammate.auth.service;

import cloud.bamsongi.albammate.auth.dto.SignupRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import cloud.bamsongi.albammate.global.security.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.UserAccount;
import cloud.bamsongi.albammate.user.UserAccountService;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 회원가입의 입력 검증·요청 제한과 사용자 모듈 호출 순서를 조합한다. */
@Service
public class SignupService {

    private final AuthenticationRequestLimiter requestLimiter;
    private final UserAccountService userAccountService;

    public SignupService(
            AuthenticationRequestLimiter requestLimiter, UserAccountService userAccountService) {
        this.requestLimiter = Objects.requireNonNull(requestLimiter, "requestLimiter");
        this.userAccountService = Objects.requireNonNull(userAccountService, "userAccountService");
    }

    public UserSummary signup(SignupRequest request, String remoteIp) {
        if (request == null) {
            throw new SignupValidationException();
        }
        SignupRequest.Normalized normalized = request.normalizeAndValidate();
        requestLimiter.requireSignupAllowed(remoteIp);

        UserAccount account =
                userAccountService.createAccount(
                        normalized.email(), normalized.password(), normalized.nickname());
        return new UserSummary(account.id(), account.nickname());
    }
}
