package cloud.bamsongi.albammate.global.security.session;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import cloud.bamsongi.albammate.global.security.error.SecurityErrorResponseWriter;

/**
 * {@code SecurityConfig}의 {@code httpSecurityCustomizers} 확장점으로 CHAT-03 세션 저장소 가용성 gate를
 * {@code SecurityContextHolderFilter}보다 앞에 추가한다.
 *
 * <p>이 gate가 Spring Security 인증이 세션을 실제로 조회하기 전에 같은 저장소를 직접 확인하므로, 저장소를 확인할 수 없을 때
 * 인증·인가 판정으로 넘어가지 않고 바로 503으로 거절한다.
 */
@Configuration(proxyBeanMethods = false)
class ChatSessionStoreAvailabilitySecurityConfiguration {

	@Bean
	Customizer<HttpSecurity> chatSessionStoreAvailabilityCustomizer(
		SessionRepository<? extends Session> sessionRepository, SecurityErrorResponseWriter responseWriter) {
		ChatSessionStoreAvailabilityFilter filter = new ChatSessionStoreAvailabilityFilter(
			sessionRepository, responseWriter);
		return http -> http.addFilterBefore(filter, SecurityContextHolderFilter.class);
	}
}
