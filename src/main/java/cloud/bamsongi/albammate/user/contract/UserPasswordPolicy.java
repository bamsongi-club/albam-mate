package cloud.bamsongi.albammate.user.contract;

import java.nio.charset.StandardCharsets;

/** 사용자 계정 비밀번호의 Unicode code point와 UTF-8 바이트 한도를 제공한다. */
public final class UserPasswordPolicy {

    public static final int MAX_CODE_POINTS = 64;
    public static final int MAX_UTF8_BYTES = 72;
    public static final int SIGNUP_MIN_CODE_POINTS = 15;

    private UserPasswordPolicy() {}

    public static boolean isValid(String password, int minCodePoints) {
        if (password == null || minCodePoints < 1 || minCodePoints > MAX_CODE_POINTS) {
            return false;
        }

        int codePointCount = password.codePointCount(0, password.length());
        return codePointCount >= minCodePoints
                && codePointCount <= MAX_CODE_POINTS
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    public static boolean isValidSignupPassword(String password) {
        return isValid(password, SIGNUP_MIN_CODE_POINTS);
    }
}
