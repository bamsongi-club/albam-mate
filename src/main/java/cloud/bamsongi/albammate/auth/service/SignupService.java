package cloud.bamsongi.albammate.auth.service;

import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 회원가입의 요청 제한과 사용자 모듈 호출 순서를 조합한다. */
@Service
@RequiredArgsConstructor
public class SignupService {

    @NonNull private final AuthenticationRequestLimiter requestLimiter;
    @NonNull private final UserAccountService userAccountService;

    public UserAccount signup(CreateUserAccountCommand command, String remoteIp) {
        requestLimiter.requireSignupAllowed(remoteIp);
        return userAccountService.createAccount(command);
    }
}
