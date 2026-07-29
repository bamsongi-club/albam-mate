package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.service.LoginCommand;
import cloud.bamsongi.albammate.auth.validation.ValidEmail;
import cloud.bamsongi.albammate.auth.validation.ValidPassword;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import jakarta.validation.constraints.NotNull;

/** 로그인 HTTP 요청 원문을 표현한다. */
public record LoginRequest(
        @NotNull @ValidEmail String email, @NotNull @ValidPassword String password) {

    public LoginCommand normalize() {
        return new LoginCommand(UserEmail.normalize(email), password);
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<redacted>]";
    }
}
