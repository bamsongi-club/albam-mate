package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class PasswordSecurityPropertiesTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void 기본_bcrypt_cost는_유효하다() {
		assertTrue(validator.validate(new PasswordSecurityProperties()).isEmpty());
	}

	@Test
	void bcrypt_cost는_10_미만이나_31_초과를_거절하고_경계값은_허용한다() {
		assertFalse(validator.validate(properties(9)).isEmpty());
		assertFalse(validator.validate(properties(32)).isEmpty());
		assertTrue(validator.validate(properties(10)).isEmpty());
		assertTrue(validator.validate(properties(31)).isEmpty());
	}

	private PasswordSecurityProperties properties(int bcryptCost) {
		PasswordSecurityProperties properties = new PasswordSecurityProperties();
		properties.setBcryptCost(bcryptCost);
		return properties;
	}
}
