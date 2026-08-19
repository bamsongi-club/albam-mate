package cloud.bamsongi.albammate.monitoring;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/** local·production proxy 응답에 검증된 Spring 역할만 노출한다. */
@Component
@Profile({"local", "production"})
@Slf4j
public final class UpstreamRoleResponseFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Albam-Mate-Upstream";
	private static final Set<String> ALLOWED_ROLES = Set.of("app1", "app2");
	private static final String REQUEST_ID_ATTRIBUTE = UpstreamRoleResponseFilter.class.getName() + ".REQUEST_ID";
	private static final String FAILURE_LOGGED_ATTRIBUTE = UpstreamRoleResponseFilter.class.getName()
		+ ".FAILURE_LOGGED";

	private final String role;

	public UpstreamRoleResponseFilter(@Value("${app.monitoring.upstream-role:}")
	String role) {
		if (!ALLOWED_ROLES.contains(role)) {
			throw new IllegalArgumentException("ALBAM_MATE_ROLE must be exactly app1 or app2");
		}
		this.role = role;
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		String requestId = requestIdFor(request);
		String previousRequestId = MDC.get("requestId");
		boolean failureLogged = false;
		try {
			MDC.put("requestId", requestId);
			response.setHeader(HEADER_NAME, role);
			filterChain.doFilter(request, response);
		} catch (ServletException | RuntimeException exception) {
			logFailureOnce(request);
			failureLogged = true;
			throw exception;
		} catch (IOException exception) {
			throw exception;
		} finally {
			if (!failureLogged && response.getStatus() >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR) {
				logFailureOnce(request);
			}
			restorePreviousRequestId(previousRequestId);
		}
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterNestedErrorDispatch(
		HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
		throws ServletException, IOException {
		String requestId = requestIdFor(request);
		String previousRequestId = MDC.get("requestId");
		try {
			MDC.put("requestId", requestId);
			filterChain.doFilter(request, response);
		} finally {
			restorePreviousRequestId(previousRequestId);
		}
	}

	private static String requestIdFor(HttpServletRequest request) {
		Object requestId = request.getAttribute(REQUEST_ID_ATTRIBUTE);
		if (requestId instanceof String serverRequestId) {
			return serverRequestId;
		}
		String serverRequestId = UUID.randomUUID().toString();
		request.setAttribute(REQUEST_ID_ATTRIBUTE, serverRequestId);
		return serverRequestId;
	}

	private static void restorePreviousRequestId(String previousRequestId) {
		if (previousRequestId != null) {
			MDC.put("requestId", previousRequestId);
		} else {
			MDC.remove("requestId");
		}
	}

	private void logFailureOnce(HttpServletRequest request) {
		if (request.getAttribute(FAILURE_LOGGED_ATTRIBUTE) != null) {
			return;
		}
		request.setAttribute(FAILURE_LOGGED_ATTRIBUTE, Boolean.TRUE);
		log.atError().addKeyValue("event", "http_request_failed")
			.addKeyValue("failureCode", "HTTP_SERVER_ERROR").log("http request failed");
	}
}
