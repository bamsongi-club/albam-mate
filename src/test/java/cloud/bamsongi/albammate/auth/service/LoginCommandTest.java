package cloud.bamsongi.albammate.auth.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class LoginCommandTest {

	@Test
	void 문자열_표현은_비밀번호_원문을_노출하지_않는다() {
		String password = "sensitive-password";

		assertFalse(new LoginCommand("user@example.com", password).toString().contains(password));
	}
}
