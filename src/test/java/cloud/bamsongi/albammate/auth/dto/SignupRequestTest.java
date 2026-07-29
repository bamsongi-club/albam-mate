package cloud.bamsongi.albammate.auth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class SignupRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 이메일과_닉네임은_정규화하고_비밀번호_공백은_보존한다() {
		String password = " 123456789012345 ";

		CreateUserAccountCommand normalized = new SignupRequest(" User@Example.COM ", password, " 닉네임 ").normalize();

		assertEquals("user@example.com", normalized.email().value());
		assertEquals(password, normalized.rawPassword().value());
		assertEquals("닉네임", normalized.nickname().value());
	}

	@Test
	void 비밀번호는_유니코드_code_point와_UTF8_바이트_경계를_컴포넌트_제약으로_검사한다() {
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "12345678901234", "닉네임"))
				.isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "😀".repeat(19), "닉네임"))
				.isEmpty());
		assertEquals(
			15,
			new SignupRequest("user@example.com", "😀".repeat(15), "닉네임")
				.normalize()
				.rawPassword()
				.value()
				.codePointCount(0, 15 * 2));
	}

	@Test
	void UTF8_정확히_72바이트_비밀번호는_허용하고_76바이트는_컴포넌트_제약으로_거절한다() {
		String exactly72Bytes = "a".repeat(56) + "é".repeat(8);
		String exactly76Bytes = "a".repeat(52) + "é".repeat(12);

		assertEquals(
			exactly72Bytes,
			new SignupRequest("user@example.com", exactly72Bytes, "닉네임")
				.normalize()
				.rawPassword()
				.value());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", exactly76Bytes, "닉네임"))
				.isEmpty());
	}

	@Test
	void 이메일_형식과_닉네임_제어문자를_컴포넌트_제약으로_거절한다() {
		assertFalse(
			validator
				.validate(new SignupRequest("not-an-email", "123456789012345", "닉네임"))
				.isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "123456789012345", "닉\n네임"))
				.isEmpty());
	}

	@Test
	void record_컴포넌트_제약이_필수값과_정규화_규칙을_검증한다() {
		assertFalse(
			validator.validate(new SignupRequest(null, "123456789012345", "닉네임")).isEmpty());
		assertFalse(validator.validate(new SignupRequest(" ", "123456789012345", "닉네임")).isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "😀".repeat(19), "닉네임"))
				.isEmpty());
		assertFalse(
			validator
				.validate(new SignupRequest("user@example.com", "123456789012345", "닉\n네임"))
				.isEmpty());
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
