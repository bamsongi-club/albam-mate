package cloud.bamsongi.albammate.global.config;

import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** P0 비밀번호 저장 계약과 인증 요청 보호 구성이다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
	PasswordSecurityProperties.class,
	AuthenticationRequestProtectionProperties.class
})
public class PasswordSecurityConfig {

	@Bean
	PasswordEncoder passwordEncoder(PasswordSecurityProperties properties) {
		BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(properties.getBcryptCost());
		return new DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", bcrypt));
	}

}
