package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class SignupRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 이메일과_닉네임은_정규화한다() {
		String password = " password 한글😀 ";

		CreateUserAccountCommand normalized = new SignupRequest(" User@Example.COM ", password, " 닉네임 ").normalize();

		assertEquals("user@example.com", normalized.email().value());
		assertEquals(password, normalized.rawPassword().value());
		assertEquals("닉네임", normalized.nickname().value());
	}

	@Test
	void 비밀번호는_15에서_64_code_point와_UTF8_72바이트_한도로_컴포넌트_제약을_검사한다() {
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "12345678901234", "닉네임"))
				.isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "a".repeat(65), "닉네임"))
				.isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "가".repeat(25), "닉네임"))
				.isEmpty());
		assertTrue(
			validator
				.validate(new SignupRequest("user@example.com", " 가😀라마바사아자차카타파하 ", "닉네임"))
				.isEmpty());
	}

	@Test
	void 이메일_형식과_닉네임_제어문자를_컴포넌트_제약으로_거절한다() {
		assertFalse(
			validator
				.validate(new SignupRequest("not-an-email", "ValidPassword123!", "닉네임"))
				.isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "ValidPassword123!", "닉\n네임"))
				.isEmpty());
	}

	@Test
	void record_컴포넌트_제약이_필수값과_정규화_규칙을_검증한다() {
		assertFalse(
			validator.validate(new SignupRequest(null, "ValidPassword123!", "닉네임")).isEmpty());
		assertFalse(validator.validate(new SignupRequest(" ", "ValidPassword123!", "닉네임")).isEmpty());
	}

	@Test
	void 문자열_표현은_비밀번호_원문을_노출하지_않는다() {
		String password = "sensitive-password";
		SignupRequest request = new SignupRequest("user@example.com", password, "닉네임");
		CreateUserAccountCommand command = request.normalize();

		assertFalse(request.toString().contains(password));
		assertFalse(command.toString().contains(password));
	}
}
