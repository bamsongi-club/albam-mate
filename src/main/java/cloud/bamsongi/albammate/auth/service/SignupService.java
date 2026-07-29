package cloud.bamsongi.albammate.auth.service;

import cloud.bamsongi.albammate.auth.dto.SignupRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 회원가입의 입력 검증·요청 제한과 사용자 모듈 호출 순서를 조합한다. */
@Service
@RequiredArgsConstructor
public class SignupService {

    @NonNull private final AuthenticationRequestLimiter requestLimiter;
    @NonNull private final UserAccountService userAccountService;

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
