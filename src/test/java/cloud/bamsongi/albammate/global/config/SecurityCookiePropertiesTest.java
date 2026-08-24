package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class SecurityCookiePropertiesTest {

	@Test
	void 비로컬_프로필에서_쿠키_Secure_설정이_누락되면_기본값은_true다() {
		SecurityCookieProperties properties = bind(new MockEnvironment());

		assertTrue(properties.isSecure());
	}

	@Test
	void 로컬_HTTP_개발을_위한_명시적_false_설정을_적용한다() {
		SecurityCookieProperties properties = bind(
			new MockEnvironment().withProperty("app.security.cookie.secure", "false"));

		assertFalse(properties.isSecure());
	}

	private SecurityCookieProperties bind(MockEnvironment environment) {
		return Binder.get(environment)
			.bindOrCreate("app.security.cookie", Bindable.of(SecurityCookieProperties.class));
	}
}
