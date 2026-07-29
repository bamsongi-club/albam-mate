package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordSecurityConfigTest {

	private final PasswordSecurityConfig config = new PasswordSecurityConfig();

	@Test
	void 신규_비밀번호_해시는_bcrypt_식별자를_포함하고_원문과_다르다() {
		PasswordEncoder encoder = config.passwordEncoder(properties(10));

		String first = encoder.encode("correct horse battery staple");
		String second = encoder.encode("correct horse battery staple");

		assertTrue(first.startsWith("{bcrypt}"));
		assertNotEquals(first, second);
		assertTrue(encoder.matches("correct horse battery staple", first));
	}

	@Test
	void 비용을_올리면_기존_해시에_upgradeEncoding을_적용할_수_있다() {
		PasswordEncoder oldEncoder = config.passwordEncoder(properties(10));
		PasswordEncoder currentEncoder = config.passwordEncoder(properties(11));
		String oldHash = oldEncoder.encode("password");

		assertTrue(currentEncoder.matches("password", oldHash));
		assertTrue(currentEncoder.upgradeEncoding(oldHash));
		assertEquals(false, currentEncoder.upgradeEncoding(currentEncoder.encode("password")));
	}

	@Test
	void 범위를_벗어난_bcrypt_cost는_애플리케이션_부팅을_실패시킨다() {
		new ApplicationContextRunner()
			.withUserConfiguration(PasswordSecurityConfig.class)
			.withPropertyValues("app.security.password.bcrypt-cost=9")
			.run(context -> assertNotNull(context.getStartupFailure()));
	}

	@Test
	void 범위_안의_bcrypt_cost는_정상적으로_바인딩된다() {
		new ApplicationContextRunner()
			.withUserConfiguration(PasswordSecurityConfig.class)
			.withPropertyValues("app.security.password.bcrypt-cost=11")
			.run(context -> {
				assertNull(context.getStartupFailure());
				assertEquals(
					11, context.getBean(PasswordSecurityProperties.class).getBcryptCost());
			});
	}

	private PasswordSecurityProperties properties(int cost) {
		PasswordSecurityProperties properties = new PasswordSecurityProperties();
		properties.setBcryptCost(cost);
		return properties;
	}
}
