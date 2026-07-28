package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import cloud.bamsongi.albammate.auth.validation.PasswordValidator;
import cloud.bamsongi.albammate.auth.validation.ValidEmail;
import cloud.bamsongi.albammate.auth.validation.ValidPassword;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import jakarta.validation.constraints.NotNull;

/** 로그인 요청 원문과 정규화된 내부 입력을 분리한다. */
public record LoginRequest(
        @NotNull @ValidEmail String email, @NotNull @ValidPassword String password) {

    public Normalized normalizeAndValidate() {
        String normalizedEmail =
                UserEmail.from(email)
                        .map(UserEmail::value)
                        .orElseThrow(LoginValidationException::new);
        if (!PasswordValidator.isValid(password, 1)) {
            throw new LoginValidationException();
        }
        return new Normalized(normalizedEmail, password);
    }

    public record Normalized(String email, String password) {

        @Override
        public String toString() {
            return "Normalized[email=" + email + ", password=<redacted>]";
        }
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<redacted>]";
    }
}
