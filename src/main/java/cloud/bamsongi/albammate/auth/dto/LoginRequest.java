package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidLoginRequest;
import java.nio.charset.StandardCharsets;

/** 로그인 요청 원문과 정규화된 내부 입력을 분리한다. */
@ValidLoginRequest
public record LoginRequest(String email, String password) {

    public Normalized normalizeAndValidate() {
        if (email == null || password == null) {
            throw new LoginValidationException();
        }

        String normalizedEmail =
                EmailNormalizer.normalize(email).orElseThrow(LoginValidationException::new);
        if (!isValidPassword(password)) {
            throw new LoginValidationException();
        }
        return new Normalized(normalizedEmail, password);
    }

    private boolean isValidPassword(String rawPassword) {
        int codePointCount = rawPassword.codePointCount(0, rawPassword.length());
        return codePointCount >= 1
                && codePointCount <= 64
                && rawPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
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
