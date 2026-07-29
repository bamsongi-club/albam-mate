package cloud.bamsongi.albammate.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import cloud.bamsongi.albammate.auth.exception.InvalidCredentialsException;
import cloud.bamsongi.albammate.auth.service.LoginCommand;
import cloud.bamsongi.albammate.auth.service.LoginService;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest
class UserAccountServiceIntegrationTest {

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private LoginService loginService;

	@Test
	void 같은_비밀번호도_서로_다른_bcrypt_해시로_저장되고_검증된다() {
		String rawPassword = "123456789012345";
		String firstEmail = "hash-first@example.com";
		String secondEmail = "hash-second@example.com";

		userAccountService.createAccount(command(firstEmail, rawPassword, "첫 사용자"));
		userAccountService.createAccount(command(secondEmail, rawPassword, "둘 사용자"));

		User first = userRepository.findByEmail(firstEmail).orElseThrow();
		User second = userRepository.findByEmail(secondEmail).orElseThrow();

		assertTrue(first.getPasswordHash().startsWith("{bcrypt}"));
		assertTrue(second.getPasswordHash().startsWith("{bcrypt}"));
		assertNotEquals(first.getPasswordHash(), second.getPasswordHash());
		assertTrue(passwordEncoder.matches(rawPassword, first.getPasswordHash()));
		assertTrue(passwordEncoder.matches(rawPassword, second.getPasswordHash()));
	}

	@Test
	void 이전_cost_해시는_올바른_로그인에서만_현재_cost로_갱신된다() {
		String legacyEmail = "legacy-cost-login@example.com";
		String legacyPassword = "legacy-login-password";
		String legacyHash = "{bcrypt}" + new BCryptPasswordEncoder(9).encode(legacyPassword);
		User legacyUser = User.create(legacyEmail, legacyHash, "이전 cost 사용자");
		userRepository.saveAndFlush(legacyUser);

		loginService.login(
			new LoginCommand(UserEmail.from(legacyEmail).orElseThrow(), legacyPassword),
			"198.51.100.121");

		User upgraded = userRepository.findByEmail(legacyEmail).orElseThrow();
		assertNotEquals(legacyHash, upgraded.getPasswordHash());
		assertTrue(upgraded.getPasswordHash().startsWith("{bcrypt}"));
		assertTrue(passwordEncoder.matches(legacyPassword, upgraded.getPasswordHash()));
		assertTrue(!passwordEncoder.upgradeEncoding(upgraded.getPasswordHash()));

		String currentEmail = "current-cost-password@example.com";
		String currentPassword = "current-cost-password";
		userAccountService.createAccount(command(currentEmail, currentPassword, "현재 cost 사용자"));
		User beforeCorrectLogin = userRepository.findByEmail(currentEmail).orElseThrow();
		String currentHash = beforeCorrectLogin.getPasswordHash();

		loginService.login(
			new LoginCommand(UserEmail.from(currentEmail).orElseThrow(), currentPassword),
			"198.51.100.122");

		User afterCorrectLogin = userRepository.findByEmail(currentEmail).orElseThrow();
		assertEquals(currentHash, afterCorrectLogin.getPasswordHash());
		assertTrue(passwordEncoder.matches(currentPassword, afterCorrectLogin.getPasswordHash()));

		assertThrows(
			InvalidCredentialsException.class,
			() -> loginService.login(
				new LoginCommand(
					UserEmail.from(currentEmail).orElseThrow(), "incorrect-password"),
				"198.51.100.123"));

		User afterWrongLogin = userRepository.findByEmail(currentEmail).orElseThrow();
		assertEquals(currentHash, afterWrongLogin.getPasswordHash());
	}

	private CreateUserAccountCommand command(String email, String password, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(password).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}
}
