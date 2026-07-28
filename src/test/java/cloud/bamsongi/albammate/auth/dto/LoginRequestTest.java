package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloud.bamsongi.albammate.auth.exception.LoginValidationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class LoginRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void 이메일은_정규화하고_비밀번호_공백은_보존한다() {
        String password = " pass word ";

        LoginRequest.Normalized normalized =
                new LoginRequest(" User@Example.COM ", password).normalizeAndValidate();

        assertEquals("user@example.com", normalized.email());
        assertEquals(password, normalized.password());
    }

    @Test
    void 로그인_비밀번호는_빈값과_code_point_UTF8_경계를_거절한다() {
        assertThrows(
                LoginValidationException.class,
                () -> new LoginRequest("user@example.com", "").normalizeAndValidate());
        assertThrows(
                LoginValidationException.class,
                () -> new LoginRequest("user@example.com", "😀".repeat(19)).normalizeAndValidate());
        assertEquals(
                64,
                new LoginRequest("user@example.com", "a".repeat(64))
                        .normalizeAndValidate()
                        .password()
                        .codePointCount(0, 64));
    }

    @Test
    void UTF8_정확히_72바이트_비밀번호는_허용하고_76바이트는_거절한다() {
        String exactly72Bytes = "a".repeat(56) + "é".repeat(8);
        String exactly76Bytes = "a".repeat(52) + "é".repeat(12);

        assertEquals(
                exactly72Bytes,
                new LoginRequest("user@example.com", exactly72Bytes)
                        .normalizeAndValidate()
                        .password());
        assertThrows(
                LoginValidationException.class,
                () -> new LoginRequest("user@example.com", exactly76Bytes).normalizeAndValidate());
    }

    @Test
    void 이메일_누락과_형식_오류를_거절한다() {
        assertThrows(
                LoginValidationException.class,
                () -> new LoginRequest(null, "password").normalizeAndValidate());
        assertThrows(
                LoginValidationException.class,
                () -> new LoginRequest("not-an-email", "password").normalizeAndValidate());
    }

    @Test
    void 회원가입_후_로그인은_보충_평면_문자를_포함한_정확히_255_code_point_이메일을_같이_허용한다() {
        String email = "😀😀" + "a".repeat(251) + "@b";
        String password = "123456789012345";
        SignupRequest.Normalized signup =
                new SignupRequest(email, password, "닉네임").normalizeAndValidate();
        LoginRequest.Normalized login =
                new LoginRequest(signup.email(), password).normalizeAndValidate();

        assertEquals(255, signup.email().codePointCount(0, signup.email().length()));
        assertEquals(signup.email(), login.email());
    }

    @Test
    void record_컴포넌트_제약이_필수값과_로그인_비밀번호_정책을_검증한다() {
        assertFalse(validator.validate(new LoginRequest(null, "password")).isEmpty());
        assertFalse(validator.validate(new LoginRequest(" ", "password")).isEmpty());
        assertFalse(
                validator
                        .validate(new LoginRequest("user@example.com", "😀".repeat(19)))
                        .isEmpty());
    }
}
