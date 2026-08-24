package cloud.bamsongi.albammate.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.user.contract.UserEmail;

class LoginCommandTest {

	@Test
	void 문자열_표현은_비밀번호_원문을_노출하지_않는다() {
		String password = "sensitive-password";
		UserEmail email = UserEmail.from("user@example.com").orElseThrow();

		assertFalse(new LoginCommand(email, password).toString().contains(password));
	}
}
