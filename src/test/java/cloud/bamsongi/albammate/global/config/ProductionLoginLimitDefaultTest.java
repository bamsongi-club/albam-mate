package cloud.bamsongi.albammate.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 부하 측정 창에서만 로그인 제한을 올리기 위해 운영 프로파일이 환경 변수를 받는다.
 * 변수를 주지 않은 평소 운영에서 기존 제한이 그대로 유지되는지 확인한다.
 */
class ProductionLoginLimitDefaultTest {

	@Test
	void 로그인_제한_환경변수를_주지_않으면_기존_기본값_30을_유지한다() throws IOException {
		StandardEnvironment environment = 운영_프로파일_환경();

		AuthenticationRequestProtectionProperties properties = 인증_요청_설정을_바인딩한다(environment);

		assertThat(properties.getLoginLimit()).isEqualTo(30);
	}

	@Test
	void 로그인_제한_환경변수를_주면_그_값을_따른다() throws IOException {
		StandardEnvironment environment = 운영_프로파일_환경();
		environment.getPropertySources()
			.addFirst(new MapPropertySource("override", Map.of("ALBAM_MATE_LOGIN_LIMIT", "300")));

		AuthenticationRequestProtectionProperties properties = 인증_요청_설정을_바인딩한다(environment);

		assertThat(properties.getLoginLimit()).isEqualTo(300);
	}

	private AuthenticationRequestProtectionProperties 인증_요청_설정을_바인딩한다(StandardEnvironment environment) {
		return Binder.get(environment)
			.bind("app.security.auth-request", AuthenticationRequestProtectionProperties.class)
			.get();
	}

	/**
	 * 운영 프로파일을 먼저 얹어 기본 설정보다 우선하게 만든다. 실제 OS 환경 변수는 제거해
	 * 실행 환경에 남은 값이 결과를 바꾸지 못하게 한다.
	 */
	private StandardEnvironment 운영_프로파일_환경() throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources()
			.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		for (String name : List.of("application-production.yml", "application.yml")) {
			environment.getPropertySources().addLast(new MapPropertySource(name, yaml을_읽는다(name)));
		}
		return environment;
	}

	private Map<String, Object> yaml을_읽는다(String name) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new ClassPathResource(name));
		factory.afterPropertiesSet();
		Properties properties = factory.getObject();
		Map<String, Object> source = new HashMap<>();
		if (properties != null) {
			properties.forEach((key, value) -> source.put(String.valueOf(key), value));
		}
		return source;
	}
}
