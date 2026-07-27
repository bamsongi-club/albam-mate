package cloud.bamsongi.albammate.auth.dto;

import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import cloud.bamsongi.albammate.auth.validation.ValidLoginRequest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 로그인 요청 원문과 정규화된 내부 입력을 분리한다. */
@ValidLoginRequest
public record LoginRequest(String email, String password) {

    public Normalized normalizeAndValidate() {
        if (email == null || password == null) {
            throw new LoginValidationException();
        }

        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        if (!isValidEmail(normalizedEmail) || !isValidPassword(password)) {
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

    private boolean isValidEmail(String normalizedEmail) {
        if (normalizedEmail.isEmpty()
                || normalizedEmail.codePoints().anyMatch(Character::isISOControl)
                || normalizedEmail.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        int atIndex = normalizedEmail.indexOf('@');
        return atIndex > 0
                && atIndex == normalizedEmail.lastIndexOf('@')
                && atIndex < normalizedEmail.length() - 1
                && atIndex < 255
                && normalizedEmail.charAt(atIndex - 1) != '.'
                && normalizedEmail.charAt(atIndex + 1) != '.'
                && !normalizedEmail.substring(atIndex + 1).contains("..")
                && normalizedEmail.codePointCount(0, normalizedEmail.length()) <= 255;
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
