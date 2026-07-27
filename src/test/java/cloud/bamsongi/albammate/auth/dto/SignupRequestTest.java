package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloud.bamsongi.albammate.auth.exception.SignupValidationException;
import org.junit.jupiter.api.Test;

class SignupRequestTest {

    @Test
    void 이메일과_닉네임은_정규화하고_비밀번호_공백은_보존한다() {
        String password = " 123456789012345 ";

        SignupRequest.Normalized normalized =
                new SignupRequest(" User@Example.COM ", password, " 닉네임 ").normalizeAndValidate();

        assertEquals("user@example.com", normalized.email());
        assertEquals(password, normalized.password());
        assertEquals("닉네임", normalized.nickname());
    }

    @Test
    void 비밀번호는_유니코드_code_point와_UTF8_바이트_경계를_모두_검사한다() {
        assertThrows(
                SignupValidationException.class,
                () ->
                        new SignupRequest("user@example.com", "12345678901234", "닉네임")
                                .normalizeAndValidate());
        assertThrows(
                SignupValidationException.class,
                () ->
                        new SignupRequest("user@example.com", "😀".repeat(19), "닉네임")
                                .normalizeAndValidate());
        assertEquals(
                15,
                new SignupRequest("user@example.com", "😀".repeat(15), "닉네임")
                        .normalizeAndValidate()
                        .password()
                        .codePointCount(0, 15 * 2));
    }

    @Test
    void 이메일_형식과_닉네임_제어문자를_거절한다() {
        assertThrows(
                SignupValidationException.class,
                () ->
                        new SignupRequest("not-an-email", "123456789012345", "닉네임")
                                .normalizeAndValidate());
        assertThrows(
                SignupValidationException.class,
                () ->
                        new SignupRequest("user@example.com", "123456789012345", "닉\n네임")
                                .normalizeAndValidate());
    }
}
