package cloud.bamsongi.albammate.auth.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/** AUTH-03이 인증 상태 변경 때 CSRF 토큰을 서버에서도 무효화하도록 저장소를 감싼다. */
@Configuration(proxyBeanMethods = false)
public class CsrfTokenRepositoryConfiguration {

	@Bean
	@Primary
	CsrfTokenRepository invalidatingCsrfTokenRepository(
		@Qualifier("csrfTokenRepository") CsrfTokenRepository delegate) {
		return new InvalidatingCsrfTokenRepository(delegate);
	}
}
