package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.auth.service.LoginCommand;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class LoginRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 이메일은_정규화하고_비밀번호_공백은_보존한다() {
		String password = " pass word ";

		LoginCommand normalized = new LoginRequest(" User@Example.COM ", password).normalize();

		assertEquals("user@example.com", normalized.email().value());
		assertEquals(password, normalized.password());
	}

	@Test
	void 회원가입_후_로그인은_보충_평면_문자를_포함한_정확히_255_code_point_이메일을_같이_허용한다() {
		String email = "😀😀" + "a".repeat(251) + "@b";
		String password = "123456789012345";
		CreateUserAccountCommand signup = new SignupRequest(email, password, "닉네임").normalize();
		LoginCommand login = new LoginRequest(signup.email().value(), password).normalize();

		assertEquals(
			255, signup.email().value().codePointCount(0, signup.email().value().length()));
		assertEquals(signup.email().value(), login.email().value());
	}

	@Test
	void 문자열_표현은_비밀번호_원문을_노출하지_않는다() {
		String password = "sensitive-password";
		LoginCommand command = new LoginRequest("user@example.com", password).normalize();

		assertFalse(command.toString().contains(password));
	}

	@Test
	void record_컴포넌트_제약이_필수값과_로그인_비밀번호_정책을_검증한다() {
		String exactly64CodePointsAnd72Utf8Bytes = "a".repeat(56) + "é".repeat(8);

		assertEquals(
			64,
			exactly64CodePointsAnd72Utf8Bytes.codePointCount(
				0, exactly64CodePointsAnd72Utf8Bytes.length()));
		assertEquals(72, exactly64CodePointsAnd72Utf8Bytes.getBytes(StandardCharsets.UTF_8).length);
		assertTrue(
			validator
				.validate(
					new LoginRequest(
						"user@example.com", exactly64CodePointsAnd72Utf8Bytes))
				.isEmpty());
		assertFalse(validator.validate(new LoginRequest(null, "password")).isEmpty());
		assertFalse(validator.validate(new LoginRequest(" ", "password")).isEmpty());
		assertFalse(validator.validate(new LoginRequest("not-an-email", "password")).isEmpty());
		assertFalse(validator.validate(new LoginRequest("user@example.com", "")).isEmpty());
		assertFalse(
			validator
				.validate(new LoginRequest("user@example.com", "😀".repeat(19)))
				.isEmpty());
		assertFalse(
			validator.validate(new LoginRequest("user@example.com", "a".repeat(65))).isEmpty());
		assertFalse(
			validator
				.validate(
					new LoginRequest(
						"user@example.com", "a".repeat(52) + "é".repeat(12)))
				.isEmpty());
	}
}
