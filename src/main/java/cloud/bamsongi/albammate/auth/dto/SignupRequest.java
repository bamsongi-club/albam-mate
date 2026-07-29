package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.validation.ValidEmail;
import cloud.bamsongi.albammate.auth.validation.ValidNickname;
import cloud.bamsongi.albammate.auth.validation.ValidPassword;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.contract.UserPasswordPolicy;
import jakarta.validation.constraints.NotNull;

/** 회원가입 HTTP 요청 원문을 표현한다. */
public record SignupRequest(
        @NotNull @ValidEmail String email,
        @NotNull @ValidPassword(minCodePoints = UserPasswordPolicy.SIGNUP_MIN_CODE_POINTS)
                String password,
        @NotNull @ValidNickname String nickname) {

    public CreateUserAccountCommand normalize() {
        return new CreateUserAccountCommand(
                UserEmail.normalize(email), password, UserNickname.normalize(nickname));
    }

    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=[REDACTED], nickname=" + nickname + "]";
    }
}
