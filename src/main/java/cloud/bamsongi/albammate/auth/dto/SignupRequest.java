package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidEmail;
import cloud.bamsongi.albammate.auth.validation.ValidNickname;
import cloud.bamsongi.albammate.auth.validation.ValidPassword;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.contract.UserPasswordPolicy;
import jakarta.validation.constraints.NotNull;

/** 회원가입 요청 원문과 정규화된 내부 입력을 분리한다. */
public record SignupRequest(
        @NotNull @ValidEmail String email,
        @NotNull @ValidPassword(minCodePoints = UserPasswordPolicy.SIGNUP_MIN_CODE_POINTS)
                String password,
        @NotNull @ValidNickname String nickname) {

    public Normalized normalizeAndValidate() {
        String normalizedEmail =
                UserEmail.from(email)
                        .map(UserEmail::value)
                        .orElseThrow(SignupValidationException::new);
        String normalizedNickname =
                UserNickname.from(nickname)
                        .map(UserNickname::value)
                        .orElseThrow(SignupValidationException::new);
        if (!UserPasswordPolicy.isValidSignupPassword(password)) {
            throw new SignupValidationException();
        }

        return new Normalized(normalizedEmail, password, normalizedNickname);
    }

    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=[REDACTED], nickname=" + nickname + "]";
    }

    public record Normalized(String email, String password, String nickname) {

        @Override
        public String toString() {
            return "Normalized[email="
                    + email
                    + ", password=[REDACTED], nickname="
                    + nickname
                    + "]";
        }
    }
}
