package cloud.bamsongi.albammate.user.contract;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class UserCredentialsTest {

	@Test
	void id_null_0_음수는_거절한다() {
		assertInvalid(null, "닉네임", "secret-hash");
		assertInvalid(0L, "닉네임", "secret-hash");
		assertInvalid(-1L, "닉네임", "secret-hash");
	}

	@Test
	void nickname과_password_hash의_null_빈값은_거절한다() {
		assertInvalid(1L, null, "secret-hash");
		assertInvalid(1L, "", "secret-hash");
		assertInvalid(1L, "닉네임", null);
		assertInvalid(1L, "닉네임", "");
	}

	@Test
	void to_string은_비밀번호_해시를_노출하지_않는다() {
		UserCredentials credentials = new UserCredentials(1L, "닉네임", "very-secret-hash");

		assertDoesNotThrow(credentials::toString);
		assertFalse(credentials.toString().contains("very-secret-hash"));
	}

	private void assertInvalid(Long id, String nickname, String passwordHash) {
		assertThrows(
			IllegalArgumentException.class,
			() -> new UserCredentials(id, nickname, passwordHash));
	}
}
