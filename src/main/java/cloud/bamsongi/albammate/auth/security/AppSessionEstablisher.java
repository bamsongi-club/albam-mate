package cloud.bamsongi.albammate.auth.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.measurement.AuthNotificationMeasurementRecorder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;

/**
 * 로그인 성공 사용자를 서버 세션 인증으로 등록한다.
 *
 * <p>이메일 로그인과 소셜 로그인이 같은 인증 주체와 세션 계약을 쓰도록 이 클래스만 사용한다. 소셜 로그인은 외부 principal을 애플리케이션 권한
 * 주체로 남기지 않으므로, 인증을 만들지 않는 결과에서는 {@link #discard}로 남은 인증 컨텍스트를 지운다.
 */
@Component
public final class AppSessionEstablisher {

	@NonNull private final SecurityContextRepository securityContextRepository;
	private final AuthNotificationMeasurementRecorder measurementRecorder;

	public AppSessionEstablisher(SecurityContextRepository securityContextRepository) {
		this(securityContextRepository, (AuthNotificationMeasurementRecorder)null);
	}

	public AppSessionEstablisher(
		SecurityContextRepository securityContextRepository,
		AuthNotificationMeasurementRecorder measurementRecorder) {
		this.securityContextRepository = securityContextRepository;
		this.measurementRecorder = measurementRecorder;
	}

	@org.springframework.beans.factory.annotation.Autowired
	public AppSessionEstablisher(
		SecurityContextRepository securityContextRepository,
		ObjectProvider<AuthNotificationMeasurementRecorder> measurementRecorder) {
		this(securityContextRepository, measurementRecorder.getIfAvailable());
	}

	/** 세션 ID를 교체해 세션 고정 공격을 막고 현재 사용자 인증을 저장한다. */
	public void establish(Long userId, HttpServletRequest request, HttpServletResponse response) {
		// changeSessionId()는 세션이 있어야 하므로 먼저 만든다. JSESSIONID 발급은 컨테이너가 담당한다.
		request.getSession(true);
		request.changeSessionId();

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
		SecurityContextHolder.setContext(context);
		saveContext(context, request, response);
	}

	/** 저장된 인증을 지워 이 요청이 인증 세션을 남기지 않게 한다. */
	public void discard(HttpServletRequest request, HttpServletResponse response) {
		SecurityContext emptyContext = SecurityContextHolder.createEmptyContext();
		SecurityContextHolder.setContext(emptyContext);
		saveContext(emptyContext, request, response);
	}

	private void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
		if (measurementRecorder == null) {
			securityContextRepository.saveContext(context, request, response);
		} else {
			measurementRecorder.authStage("session-context-save",
				() -> securityContextRepository.saveContext(context, request, response));
		}
	}
}
